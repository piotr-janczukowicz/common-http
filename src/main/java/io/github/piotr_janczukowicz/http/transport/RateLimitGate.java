package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Remembers the rate limits that responses reported, so that a call does not send a request that an earlier response
 * already showed to be doomed. Owned by one {@link RetryingHttpTransport}; thread-safe without a global lock.
 *
 * <p>A limit is kept under the origin (scheme, host, port), the credential and the bucket it applies to. The credential
 * is the one {@link HttpRequest#credentialKey()} names for the request: its {@link HttpRequest#credentialId() identity}
 * when it has one, otherwise a digest of its {@code Authorization} header, otherwise anonymous. The credential itself
 * is never stored. A limit without a bucket applies to every bucket of its origin and credential. Each limit keeps the
 * rejection that reported it, to be replayed to a caller that is not allowed to wait. Expired entries are dropped
 * whenever the gate is written, and ignored when it is read, so tokens that rotate cannot make it grow.
 *
 * <p>Each limit is bounded in time and in size. A limit holds later calls back for at most {@code maxBlock}
 * ({@link RetryConfiguration.WaitCaps#maxRateLimitBlock()}), however long the response asked for, so that one response
 * with an absurd {@code Retry-After} cannot disable a credential until the transport is recreated. A stored rejection
 * keeps at most {@link HttpErrorBodies#ERROR_BODY_SCAN_BYTES} bytes of its body, which is all that the classification
 * of a rate limit reads. The number of limits is not capped: there is at most one per origin, credential and bucket
 * that was limited within the last {@code maxBlock}.
 *
 * <p>The wait of a limit that names neither a {@code Retry-After} nor a reset escalates per credential and bucket: see
 * {@link #unnamedWait(HttpRequest, String)}.
 */
final class RateLimitGate {

    private final Clock clock;
    private final Duration maxBlock;
    private final Duration minWait;
    private final Duration maxWait;
    private final ConcurrentMap<Key, Block> blocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, Escalation> escalations = new ConcurrentHashMap<>();

    /**
     * Gate whose limits last at most {@code maxBlock}, and whose unnamed limits wait {@code minWait} at first, up to
     * {@code maxWait}.
     *
     * @param clock clock that limits end on; the transport passes a monotonic one, so a step of the system clock does
     *     not stretch or end a block
     * @param maxBlock longest time a limit holds calls back
     * @param minWait wait of the first unnamed limit of a credential and bucket
     * @param maxWait longest wait of an unnamed limit; also the quiet period after which its escalation starts again
     * @throws NullPointerException if an argument is {@code null}
     */
    RateLimitGate(final Clock clock, final Duration maxBlock, final Duration minWait, final Duration maxWait) {
        this.clock = requireNonNull(clock, "clock");
        this.maxBlock = requireNonNull(maxBlock, "maxBlock");
        this.minWait = requireNonNull(minWait, "minWait");
        this.maxWait = requireNonNull(maxWait, "maxWait");
    }

    /**
     * Records that requests like {@code request} must not be sent for {@code wait} from now, but for {@code maxBlock}
     * at most. A record for the same key that ends later is kept. Only the first
     * {@link HttpErrorBodies#ERROR_BODY_SCAN_BYTES} bytes of the body of {@code rejection} are kept, and then without
     * its {@code Content-Length}.
     *
     * @param request request that was rejected
     * @param bucket quota the limit applies to, or {@code null} for all of them
     * @param wait how long the limit lasts
     * @param rejection the rejecting response
     * @return when this record ends, on the local clock
     */
    Instant block(
            final HttpRequest request,
            final @Nullable String bucket,
            final Duration wait,
            final HttpResponse rejection) {
        dropExpired();
        final Block fresh = new Block(bucket, endOf(wait), truncated(rejection));
        blocks.merge(
                new Key(owner(request), bucket),
                fresh,
                (old, added) -> added.until().isAfter(old.until()) ? added : old);
        return fresh.until();
    }

    /**
     * The wait of a limit that names neither a {@code Retry-After} nor a reset, for {@code request} on {@code bucket}.
     * The wait is {@code minWait} for the first such limit of a credential and bucket, doubled for each further one,
     * and cut to {@code maxWait}. A limit that arrives while an earlier one of the same credential and bucket still
     * holds calls back, or while the wait of the last unnamed limit still runs, does not raise the level: the requests
     * that were already in flight when the limit was hit would otherwise each escalate it, whether or not the first of
     * them has recorded its block yet. The level starts again at {@code minWait} once {@code maxWait} has passed since
     * the wait of the last such limit ended.
     *
     * @param request request that was limited
     * @param bucket bucket of the limit, or {@code null} for all of them
     * @return the wait for this limit, never longer than {@code maxWait}
     */
    Duration unnamedWait(final HttpRequest request, final @Nullable String bucket) {
        dropExpired();
        final Instant now = clock.instant();
        final Key key = new Key(owner(request), bucket);
        final boolean isBlocked = isHolding(key);
        return escalations
                .compute(key, (sameKey, previous) -> escalate(previous, isBlocked || isWaiting(previous, now), now))
                .delay();
    }

    /**
     * The limit in force for {@code request}: the one on its bucket or on all buckets, whichever ends later.
     *
     * @param request request about to be sent
     * @param bucket bucket of {@code request}
     * @return the limit, or empty when the request may be sent
     */
    Optional<Block> blockFor(final HttpRequest request, final String bucket) {
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        final Instant now = clock.instant();
        final String owner = owner(request);
        final Block specific = inForce(blocks.get(new Key(owner, bucket)), now);
        final Block global = inForce(blocks.get(new Key(owner, null)), now);
        if (specific == null) {
            return Optional.ofNullable(global);
        }
        return Optional.of(global != null && global.until().isAfter(specific.until()) ? global : specific);
    }

    /**
     * Time until {@code block} ends.
     *
     * @param block limit in force
     * @return remaining time, never negative
     */
    Duration remaining(final Block block) {
        final Duration left = Duration.between(clock.instant(), block.until());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * The stored rejection of {@code block}, to return instead of sending a request. Status, body and headers are those
     * of the original (the body cut to {@link HttpErrorBodies#ERROR_BODY_SCAN_BYTES} bytes, with no
     * {@code Content-Length} then), except that {@code Date} is removed, {@code Retry-After} is the time left in whole
     * seconds (rounded up) and {@link HttpHeaderNames#X_CLIENT_RATE_LIMIT_GATE} marks it as not coming from the server.
     *
     * @param block limit in force
     * @param remaining time left, from {@link #remaining(Block)}
     * @return rewritten rejection
     */
    HttpResponse rejection(final Block block, final Duration remaining) {
        final HttpResponse stored = block.rejection();
        final long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
        final HttpHeaders headers = replayHeaders(
                stored.headers().without(DATE),
                Map.of(
                        RETRY_AFTER,
                        Long.toString(seconds),
                        HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE,
                        RateLimitGateHeaders.BLOCKED));
        return new HttpResponse(stored.statusCode(), headers, stored.body());
    }

    /**
     * Number of limits held, expired ones included until the next write drops them.
     *
     * @return entry count
     */
    int size() {
        return blocks.size();
    }

    private Instant endOf(final Duration wait) {
        return clock.instant().plus(wait.compareTo(maxBlock) > 0 ? maxBlock : wait);
    }

    // Reads ignore an expired limit instead of sweeping: only writes sweep, so a read stays cheap.
    private static @Nullable Block inForce(final @Nullable Block block, final Instant now) {
        return block != null && block.until().isAfter(now) ? block : null;
    }

    private boolean isHolding(final Key key) {
        // dropExpired() ran just before, so every block still held is in force.
        return blocks.containsKey(key);
    }

    // The wait of the last unnamed limit of the key is still running. Decided inside compute, so that requests that
    // were in flight together see each other's limit before any of them has recorded its block.
    private static boolean isWaiting(final @Nullable Escalation previous, final Instant now) {
        return previous != null && now.isBefore(previous.waitEnd());
    }

    // A limit that arrives while the key is already held does not escalate it; a quiet period resets the level.
    private Escalation escalate(final @Nullable Escalation previous, final boolean isHolding, final Instant now) {
        // dropExpired() ran just before, so a quiet period has already reset the level (previous is null then).
        final int level = previous == null ? 0 : previous.level();
        final int next = isHolding ? Math.max(level, 1) : Math.min(level + 1, RetryPolicy.MAX_LEVEL);
        final Duration delay = RetryPolicy.escalatedWait(minWait, maxWait, next);
        return new Escalation(next, delay, now.plus(delay));
    }

    // Built like response headers are, because those may carry names (Content-Length, Transfer-Encoding) that
    // the builder refuses.
    private static HttpHeaders replayHeaders(final HttpHeaders source, final Map<String, String> added) {
        final Map<String, List<String>> wire = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        source.asMap().forEach((name, value) -> wire.put(name, List.of(value)));
        added.forEach((name, value) -> wire.put(name, List.of(value)));
        return HttpHeaders.fromWire(wire);
    }

    private static HttpResponse truncated(final HttpResponse response) {
        final int limit = HttpErrorBodies.ERROR_BODY_SCAN_BYTES;
        return response.body()
                .mapBytes(
                        bytes -> bytes.length <= limit
                                ? response
                                : new HttpResponse(
                                        response.statusCode(),
                                        response.headers().without(CONTENT_LENGTH),
                                        HttpBody.of(Arrays.copyOf(bytes, limit))),
                        () -> response);
    }

    private void dropExpired() {
        final Instant now = clock.instant();
        blocks.values().removeIf(block -> !block.until().isAfter(now));
        escalations.values().removeIf(escalation -> isQuiet(escalation, now));
    }

    // A quiet period of maxWait after the wait of the last unnamed limit of the key starts the escalation again.
    private boolean isQuiet(final Escalation escalation, final Instant now) {
        return !now.isBefore(escalation.waitEnd().plus(maxWait));
    }

    private static String owner(final HttpRequest request) {
        final ParsedUrl url = request.url();
        return url.scheme() + "://" + url.host() + ":" + url.port() + "#" + request.credentialKey();
    }

    /** Where a limit is kept: {@code owner} is origin plus credential, a {@code null} bucket means all. */
    private record Key(String owner, @Nullable String bucket) {}

    /**
     * The level of the unnamed limits of a key, and the wait and end of the last of them.
     *
     * @param level 1-based level of the last unnamed limit
     * @param delay the wait that the last unnamed limit gave
     * @param waitEnd when that wait ends
     */
    private record Escalation(int level, Duration delay, Instant waitEnd) {}

    /**
     * A limit in force.
     *
     * @param bucket quota it applies to, or {@code null} for all
     * @param until end of the limit on the local clock
     * @param rejection the response that reported it
     */
    record Block(@Nullable String bucket, Instant until, HttpResponse rejection) {}
}
