package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ETAG;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LAST_MODIFIED;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.TRANSFER_ENCODING;
import static pl.piotrjanczukowicz.util.ConcurrencyUtils.toInterruptedIo;
import static pl.piotrjanczukowicz.util.IoUtils.closeSuppressing;
import static pl.piotrjanczukowicz.util.ValidationUtils.isNonPositive;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;

/**
 * {@link HttpTransport} that retries a delegate according to {@link RetryConfiguration}.
 *
 * <p>Rate-limited responses, retryable 5xx responses, and I/O failures are retried when the configuration allows it,
 * each with its own attempt budget; the rate-limit budget is spent on every wait for a rate limit, a call's own and the
 * gate's. Which responses count as rate-limited is decided by the {@link RetryConfiguration#rateLimitDetector()
 * detector}: the default one recognizes HTTP 429 only, so a provider that signals limits otherwise (some APIs use HTTP
 * 403) needs a provider-specific detector. {@link RetryConfiguration#totalDeadline()} bounds one {@link #execute} call:
 * once the next attempt would begin after it, the last response is returned or the last failure thrown. The budget
 * starts afresh on every call, so each redirect hop and each pagination page issued through this transport has its own.
 * {@link #close()} closes the delegate.
 *
 * <p>Failures that are not retried, because repeating the call cannot succeed or must not happen: a
 * {@link NonRetryableIOException} (a request body source that failed or announced the wrong length, or a transport that
 * was closed, which also ends a retry loop in progress), {@link ResponseTooLargeException}, a
 * {@link FileNotFoundException} (a file body that is missing), an {@link UnknownHostException} (treated as a
 * deterministic configuration or name error rather than a transient DNS fault), TLS validation failures
 * ({@link SSLHandshakeException}, {@link SSLPeerUnverifiedException}, {@link SSLKeyException}), OkHttp's
 * {@code IOException("Canceled")} after the call was cancelled, an {@link InterruptedIOException} that is not a
 * {@link SocketTimeoutException} (including the {@code timeout} raised when an overall call timeout expires, which is a
 * deadline and not a transient fault), and any failure once the thread is interrupted. A {@link SocketTimeoutException}
 * (connect, read or write inactivity timeout), a connection reset and a plain {@code SSLException} are retried like
 * other I/O failures, that is for GET, HEAD and OPTIONS by default. A {@link java.net.ConnectException} or
 * {@link java.net.NoRouteToHostException} means the connection was never opened, so no request byte was sent, and it is
 * retried for every method. A transport below this one must therefore throw these two only when no byte of the request
 * was sent, and give a failure after sending began another type, such as a {@link java.net.SocketException}.
 *
 * <p><strong>Coordination across calls.</strong> Retry state is otherwise per call, so after one call met a rate limit
 * every other call would still be sent and rejected, which some providers warn can get an integration banned. Unless
 * {@link RetryConfiguration#coordinateRateLimits()} is off, the transport therefore remembers each limit that a
 * response reported, per origin, credential (the {@link HttpRequest#credentialId() identity} of the request, or else a
 * digest of its {@code Authorization} header, never the value) and bucket. A limit that names a
 * {@link RateLimitSignal#bucket() bucket} holds back only requests of that bucket; one without applies to all of them.
 * Before every attempt of every call the transport checks for a limit that applies: if one does, the call pauses until
 * it ends, plus {@code rateLimitJitter} so that the threads released together do not resume together, and checks again;
 * the pause is not an attempt, but it uses the rate-limit budget like waiting out a rate-limited response: a call waits
 * at most {@code maxRateLimitAttempts - 1} times in total for rate limits. If the pause would exceed
 * {@code maxRateLimitWait} or the total deadline, or rate-limit retries are off, or that budget is used up, the call
 * returns the stored rejection without sending anything: same status and the first 8 KiB of the body, no {@code Date},
 * {@code Retry-After} set to the time left in whole seconds, and the header
 * {@value pl.piotrjanczukowicz.http.HttpHeaderNames#X_CLIENT_RATE_LIMIT_GATE} with the value
 * {@value RateLimitGateHeaders#BLOCKED}. This check happens before the delegate, so a pause holds no slot of a
 * concurrency limit placed under this transport. A limit holds later calls back for
 * {@link RetryConfiguration.WaitCaps#maxRateLimitBlock()} at most (one hour by default), however long the response that
 * reported it asked for, so that one absurd {@code Retry-After} cannot disable a credential. A limit that names neither
 * a {@code Retry-After} nor a reset waits {@code minRateLimitWait}, doubled for each further such limit of the same
 * credential and bucket while they keep coming, and capped at {@code maxRateLimitWait}; a quiet period of
 * {@code maxRateLimitWait} starts it again. A limit that names its wait neither uses nor changes that escalation.
 *
 * <p>A response that is not a rejection can also report that its quota is used up, which the detector sees through
 * {@link RateLimitDetector#detectExhaustion}: a 2xx or 3xx with the remaining quota at zero and a reset in the future.
 * Then the transport returns that response unchanged, and holds back the bucket of its request until the reset, under
 * the same limits as a rejection (the wait is capped by {@code maxRateLimitBlock}, and a later call fails fast or waits
 * as described above). A streamed success is held back the same way, once its headers have arrived.
 *
 * <p>Each retry is logged at debug level, and a wait for a rate limit at info level, with the method, scheme, host,
 * path, status or exception class, attempt, and delay. A decision not to wait out a rate limit is logged at info level,
 * a wait or fail-fast of the gate at debug level. Headers, query strings, bodies and credentials are never logged. The
 * same rate-limit steps are reported to the {@link RetryConfiguration#rateLimitEventListener() listener}.
 */
public final class RetryingHttpTransport implements HttpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingHttpTransport.class);
    // Lower-cased, because isReplayable compares the lower-cased name. Every Content-* header describes the body.
    private static final String CONTENT_HEADER_PREFIX = "content-";
    // Lower-cased, because isReplayable compares the lower-cased name.
    private static final Set<String> NOT_REPLAYED = Stream.of(DATE, ETAG, LAST_MODIFIED, TRANSFER_ENCODING)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private final HttpTransport delegate;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final @Nullable RateLimitGate gate;
    private final RetryEvents events;

    /**
     * Retrying wrapper over {@code delegate} with {@link RetryConfiguration#defaults()}.
     *
     * @param delegate transport that performs each attempt
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public RetryingHttpTransport(final HttpTransport delegate) {
        this(delegate, RetryConfiguration.defaults());
    }

    /**
     * Retrying wrapper over {@code delegate} with {@code configuration}.
     *
     * @param delegate transport that performs each attempt
     * @param configuration retry policy data
     * @throws NullPointerException if {@code delegate} or {@code configuration} is {@code null}
     */
    public RetryingHttpTransport(final HttpTransport delegate, final RetryConfiguration configuration) {
        this(delegate, configuration, Thread::sleep);
    }

    /**
     * Package-private so tests can inject a sleeper. The retry policy reads the system clock, because it turns the
     * absolute reset times that a server names into waits; the rate-limit gate measures how long its blocks last on a
     * monotonic clock, so that a step of the system clock does not stretch or end a block.
     *
     * @param delegate transport that performs one attempt
     * @param configuration retry policy data
     * @param sleeper pause between attempts
     */
    RetryingHttpTransport(final HttpTransport delegate, final RetryConfiguration configuration, final Sleeper sleeper) {
        this(delegate, configuration, sleeper, Clock.systemUTC(), new MonotonicClock(), RetryPolicy.defaultRandom());
    }

    RetryingHttpTransport(
            final HttpTransport delegate,
            final RetryConfiguration configuration,
            final Sleeper sleeper,
            final Clock clock) {
        this(delegate, configuration, sleeper, clock, RetryPolicy.defaultRandom());
    }

    RetryingHttpTransport(
            final HttpTransport delegate,
            final RetryConfiguration configuration,
            final Sleeper sleeper,
            final Clock clock,
            final RandomGenerator random) {
        this(delegate, configuration, sleeper, clock, clock, random);
    }

    RetryingHttpTransport(
            final HttpTransport delegate,
            final RetryConfiguration configuration,
            final Sleeper sleeper,
            final Clock policyClock,
            final Clock gateClock,
            final RandomGenerator random) {
        this(
                delegate,
                new RetryPolicy(configuration, policyClock, random),
                gateFor(configuration, gateClock),
                new RetryEvents(
                        configuration.rateLimitEventListener(),
                        configuration.waits().maxRateLimitWait()),
                sleeper);
    }

    /**
     * Retrying wrapper over {@code delegate} whose collaborators are all supplied, so tests can inject a gate and a
     * listener. Creates nothing itself; the constructors that take a {@link RetryConfiguration} build these from it.
     *
     * @param delegate transport that performs one attempt
     * @param policy retry decisions and delays
     * @param gate rate-limit gate, or {@code null} when limits are not coordinated across calls
     * @param events reports retry and rate-limit steps to the listener and the log
     * @param sleeper pause between attempts
     * @throws NullPointerException if {@code delegate}, {@code policy}, {@code events} or {@code sleeper} is
     *     {@code null}
     */
    RetryingHttpTransport(
            final HttpTransport delegate,
            final RetryPolicy policy,
            final @Nullable RateLimitGate gate,
            final RetryEvents events,
            final Sleeper sleeper) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.policy = requireNonNull(policy, "policy");
        this.sleeper = requireNonNull(sleeper, "sleeper");
        this.gate = gate;
        this.events = requireNonNull(events, "events");
    }

    /**
     * Executes {@code request} on the delegate, retrying rate-limited responses, retryable 5xx, and I/O failures
     * according to the configuration.
     *
     * @param request request to send
     * @return first response that is not retried, or the stored rejection of a rate limit that is still in force
     * @throws IOException if the delegate fails and no retry remains, or a retry wait is interrupted
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Override
    public HttpResponse execute(final HttpRequest request) throws IOException {
        requireNonNull(request, "request");

        final Instant deadline = policy.deadline();
        final RetryAttempts attempts = new RetryAttempts();
        while (true) {
            final Optional<HttpResponse> blocked = awaitGate(request, attempts, deadline);
            if (blocked.isPresent()) {
                return blocked.get();
            }
            final Optional<HttpResponse> response = attempt(request, attempts, deadline);
            if (response.isPresent() && isFinal(request, response.get(), attempts, deadline)) {
                return response.get();
            }
        }
    }

    /**
     * Opens {@code request} as a stream on the delegate, retrying like {@link #execute}: I/O failures, rate-limited
     * responses and retryable 5xx are retried until the response headers of a successful (2xx) response arrive. From
     * then on nothing is retried: the body streams to the caller and a failure while reading it surfaces there, because
     * bytes already consumed cannot be replayed. Non-2xx responses are buffered by the delegate and returned unchanged
     * when they are not retried. A rate limit that is still in force is answered like in {@link #execute}, with the
     * stored rejection as a buffered response.
     *
     * @param request request to send
     * @return first response that is not retried; the caller must close it
     * @throws IOException if the delegate fails and no retry remains, or a retry wait is interrupted
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Override
    public HttpStreamResponse stream(final HttpRequest request) throws IOException {
        requireNonNull(request, "request");

        final Instant deadline = policy.deadline();
        final RetryAttempts attempts = new RetryAttempts();
        while (true) {
            final Optional<HttpResponse> blocked = awaitGate(request, attempts, deadline);
            if (blocked.isPresent()) {
                return HttpStreamResponse.buffered(blocked.get());
            }
            final Optional<HttpStreamResponse> attempted = attemptStream(request, attempts, deadline);
            if (attempted.isEmpty()) {
                continue;
            }
            final HttpStreamResponse response = attempted.get();
            if (response.isStreaming()) {
                try {
                    blockOnStreamedExhaustion(request, response);
                } catch (final RuntimeException | Error failure) {
                    closeSuppressing(response, failure);
                    throw failure;
                }
                return response;
            }
            final HttpResponse buffered = response.bufferedResponse();
            if (isFinal(request, buffered, attempts, deadline)) {
                return response;
            }
        }
    }

    /** Closes the delegate transport. */
    @Override
    public void close() {
        delegate.close();
    }

    /** Number of rate limits the gate holds, for tests. */
    int gateSize() {
        return gate == null ? 0 : gate.size();
    }

    private static @Nullable RateLimitGate gateFor(final RetryConfiguration configuration, final Clock clock) {
        if (!configuration.coordinateRateLimits()) {
            return null;
        }
        final RetryConfiguration.WaitCaps waits = configuration.waits();
        return new RateLimitGate(clock, waits.maxRateLimitBlock(), waits.minRateLimitWait(), waits.maxRateLimitWait());
    }

    /**
     * Wait for a limit that names neither a {@code Retry-After} nor a reset. The gate escalates it per credential and
     * bucket; without a gate, each call escalates its own waits as before.
     */
    private Duration unnamedWait(
            final HttpRequest request, final RetryAttempts attempts, final @Nullable String bucket) {
        return gate == null
                ? policy.unnamedWaitBackoff(attempts.rateLimitAttempt())
                : gate.unnamedWait(request, bucket);
    }

    /**
     * Waits for the rate limits that apply to {@code request} to end. Returns the stored rejection instead when the
     * wait is not allowed, and empty when the request may be sent.
     */
    private Optional<HttpResponse> awaitGate(
            final HttpRequest request, final RetryAttempts attempts, final @Nullable Instant deadline)
            throws IOException {
        if (gate == null) {
            return Optional.empty();
        }
        final String bucket = policy.bucketOf(request);
        while (true) {
            final Optional<RateLimitGate.Block> found = gate.blockFor(request, bucket);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            final RateLimitGate.Block block = found.get();
            if (attempts.hasWaitedFor(block.until())) {
                return Optional.empty();
            }
            final Duration remaining = gate.remaining(block);
            final Duration delay = policy.withRateLimitJitter(remaining);
            if (!policy.isRetryingRateLimits()
                    || policy.isRateLimitBudgetUsed(attempts.rateLimitWaits())
                    || remaining.compareTo(policy.maxRateLimitWait()) > 0
                    || policy.isBeyondDeadline(deadline, delay)) {
                events.logGate(request, "is not sent", block, remaining);
                events.publish(RateLimitEvent.Type.GATE_FAIL_FAST, request, 0, remaining, attempts, block.bucket());
                return Optional.of(gate.rejection(block, remaining));
            }
            events.logGate(request, "waits", block, delay);
            events.publish(RateLimitEvent.Type.GATE_WAIT, request, 0, delay, attempts, block.bucket());
            attempts.countRateLimitWait();
            pause(delay, attempts.lastFailure());
            attempts.waitedFor(block.until());
        }
    }

    /**
     * Runs one delegate call. Only the delegate call is guarded: a failure is either rethrown or turned into a wait
     * (and an empty result), while response handling, including an interrupted wait, happens outside the guard.
     */
    private Optional<HttpResponse> attempt(
            final HttpRequest request, final RetryAttempts attempts, final @Nullable Instant deadline)
            throws IOException {
        try {
            return Optional.of(delegate.execute(request));
        } catch (final IOException e) {
            retryAfterFailure(request, e, attempts, deadline);
            return Optional.empty();
        }
    }

    private Optional<HttpStreamResponse> attemptStream(
            final HttpRequest request, final RetryAttempts attempts, final @Nullable Instant deadline)
            throws IOException {
        try {
            return Optional.of(delegate.stream(request));
        } catch (final IOException e) {
            retryAfterFailure(request, e, attempts, deadline);
            return Optional.empty();
        }
    }

    /**
     * Whether {@code response} is the one to return. When it is not, waits out the retry delay and advances the
     * counters, so the caller attempts again.
     */
    private boolean isFinal(
            final HttpRequest request,
            final HttpResponse response,
            final RetryAttempts attempts,
            final @Nullable Instant deadline)
            throws IOException {
        final Optional<RetryPolicy.RateLimitVerdict> rateLimit = policy.rateLimit(
                request, response, attempts.rateLimitWaits(), bucket -> unnamedWait(request, attempts, bucket));
        if (rateLimit.isPresent()) {
            return isFinalRateLimit(request, response, rateLimit.get(), attempts, deadline);
        }
        blockOnExhaustion(request, response);
        final Optional<Duration> delay = policy.serverErrorDelay(request, response, attempts.serverAttempt());
        if (delay.isEmpty() || policy.isBeyondDeadline(deadline, delay.get())) {
            return true;
        }
        events.logRetry(request, false, "HTTP " + response.statusCode(), attempts.finished(), delay.get());
        retry(new RetryPolicy.RetryWait(delay.get(), RetryPolicy.RetryWait.Reason.SERVER_ERROR), attempts);
        return false;
    }

    private void blockOnStreamedExhaustion(final HttpRequest request, final HttpStreamResponse response) {
        blockOnExhaustion(request, new HttpResponse(response.statusCode(), response.headers(), HttpBody.absent()));
    }

    /**
     * Holds back the bucket of {@code request} until the reset of a response that was not rejected but reports its
     * quota as used up. The response is not retried; it is returned as it is.
     */
    private void blockOnExhaustion(final HttpRequest request, final HttpResponse response) {
        if (gate == null) {
            return;
        }
        final Optional<RateLimitSignal> exhausted = policy.exhaustion(request, response);
        if (exhausted.isEmpty() || exhausted.get().resetAt() == null) {
            return;
        }
        gate.block(
                request,
                exhausted.get().bucket(),
                policy.untilReset(exhausted.get().resetAt(), response),
                exhaustedRejection(response));
    }

    /**
     * The rejection that a gate replays for an exhausted quota: HTTP 429 with the headers of the response that reported
     * it, except the ones that describe its body or its validity ({@code Content-*}, {@code ETag},
     * {@code Last-Modified}, {@code Transfer-Encoding}, {@code Date}), and no body.
     */
    private static HttpResponse exhaustedRejection(final HttpResponse response) {
        final Map<String, List<String>> wire = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().asMap().forEach((name, value) -> {
            if (isReplayable(name)) {
                wire.put(name, List.of(value));
            }
        });
        return new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.fromWire(wire), HttpBody.absent());
    }

    private static boolean isReplayable(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(CONTENT_HEADER_PREFIX) && !NOT_REPLAYED.contains(lower);
    }

    private boolean isFinalRateLimit(
            final HttpRequest request,
            final HttpResponse response,
            final RetryPolicy.RateLimitVerdict verdict,
            final RetryAttempts attempts,
            final @Nullable Instant deadline)
            throws IOException {
        final Instant until = gate == null ? null : gate.block(request, verdict.bucket(), verdict.required(), response);
        return switch (verdict.outcome()) {
            case NOT_RETRIED -> true;
            case ATTEMPTS_EXHAUSTED ->
                giveUp(RateLimitEvent.Type.GIVE_UP_ATTEMPTS_EXHAUSTED, request, response, verdict, attempts);
            case WAIT_TOO_LONG ->
                giveUp(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG, request, response, verdict, attempts);
            case RETRY ->
                policy.isBeyondDeadline(deadline, verdict.delay())
                        ? giveUp(RateLimitEvent.Type.GIVE_UP_DEADLINE, request, response, verdict, attempts)
                        : waitOutRateLimit(request, response, verdict, until, attempts);
        };
    }

    private boolean waitOutRateLimit(
            final HttpRequest request,
            final HttpResponse response,
            final RetryPolicy.RateLimitVerdict verdict,
            final @Nullable Instant until,
            final RetryAttempts attempts)
            throws IOException {
        events.publish(
                RateLimitEvent.Type.WAIT, request, response.statusCode(), verdict.delay(), attempts, verdict.bucket());
        events.logRetry(request, true, "HTTP " + response.statusCode(), attempts.finished(), verdict.delay());
        retry(new RetryPolicy.RetryWait(verdict.delay(), RetryPolicy.RetryWait.Reason.RATE_LIMIT), attempts);
        if (until != null) {
            attempts.waitedFor(until);
        }
        return false;
    }

    /** Reports that the rate-limited {@code response} is returned instead of waited out; always {@code true}. */
    private boolean giveUp(
            final RateLimitEvent.Type type,
            final HttpRequest request,
            final HttpResponse response,
            final RetryPolicy.RateLimitVerdict verdict,
            final RetryAttempts attempts) {
        final Duration wait = type == RateLimitEvent.Type.GIVE_UP_DEADLINE ? verdict.delay() : verdict.required();
        events.publish(type, request, response.statusCode(), wait, attempts, verdict.bucket());
        LOG.atInfo()
                .setMessage("{} {} gave HTTP {}; not retrying after rate-limit attempt {}: {}")
                .addArgument(request.method())
                .addArgument(request.url().describeSafe())
                .addArgument(response.statusCode())
                .addArgument(attempts.rateLimitAttempt())
                .addArgument(() -> events.describeGiveUp(type, wait))
                .log();
        return true;
    }

    private void retryAfterFailure(
            final HttpRequest request,
            final IOException failure,
            final RetryAttempts attempts,
            final @Nullable Instant deadline)
            throws IOException {
        // Classified before the earlier failure is attached, so a failure that is never retried is thrown as it is.
        if (policy.isNeverRetried(failure) || Thread.currentThread().isInterrupted()) {
            throw failure;
        }
        attempts.remember(withSuppressed(failure, attempts.lastFailure()));
        final Optional<Duration> decision = policy.afterIoFailure(request, failure, attempts.ioAttempt());
        if (decision.isEmpty()) {
            throw failure;
        }
        final Duration wait = decision.orElseThrow();
        if (policy.isBeyondDeadline(deadline, wait)) {
            throw failure;
        }
        events.logRetry(request, false, failure.getClass().getSimpleName(), attempts.finished(), wait);
        retry(new RetryPolicy.RetryWait(wait, RetryPolicy.RetryWait.Reason.IO), attempts);
    }

    private void retry(final RetryPolicy.RetryWait wait, final RetryAttempts attempts) throws IOException {
        pause(wait.delay(), attempts.lastFailure());
        attempts.advance(wait.reason());
    }

    private static IOException withSuppressed(final IOException failure, final @Nullable IOException previous) {
        if (previous != null) {
            failure.addSuppressed(previous);
        }
        return failure;
    }

    private void pause(final Duration delay, final @Nullable IOException lastFailure) throws IOException {
        if (isNonPositive(delay)) {
            return;
        }

        try {
            sleeper.sleep(delay);
        } catch (final InterruptedException e) {
            throw interrupted(lastFailure, e);
        }
    }

    private static InterruptedIOException interrupted(
            final @Nullable IOException lastFailure, final InterruptedException cause) {
        if (lastFailure == null) {
            return toInterruptedIo(cause, "Retry interrupted");
        }
        // The last failure is the cause here, which toInterruptedIo cannot express, so the flag is restored here and
        // the interrupt is kept as a suppressed exception.
        Thread.currentThread().interrupt();
        final InterruptedIOException interrupted = new InterruptedIOException("Retry interrupted");
        interrupted.initCause(lastFailure);
        interrupted.addSuppressed(cause);
        return interrupted;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
