package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.isNonPositive;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;

/**
 * Applies {@link RetryConfiguration} to a failed attempt. Used only by {@link RetryingHttpTransport}.
 *
 * <p>I/O failures, rate limits and retryable 5xx each have their own attempt budget ({@code maxIoAttempts},
 * {@code maxRateLimitAttempts}, {@code maxServerErrorAttempts}). I/O and 5xx share {@code ioRetryableMethods}. The
 * rate-limit budget allows {@code maxRateLimitAttempts - 1} waits in total, whether a call waits out its own
 * rate-limited response or pauses for a limit that another call reported.
 *
 * <p>The wait for a rate limit is the later of the {@code Retry-After} and the reset time, when the signal carries
 * both. The reset is measured against the server's {@code Date} header, plus {@code clockSkewMargin}. A signal with
 * neither waits for the escalating wait that the caller supplies: the gate's per credential and bucket, or, without a
 * gate, {@code minRateLimitWait} doubled for each earlier rate limit of the same call, cut to {@code maxRateLimitWait}.
 * A wait that the server asked for and that exceeds {@code maxRateLimitWait} is not taken.
 *
 * <p>A response that the rate-limit detector flags is handled only by the rate-limit settings, even when its status is
 * also listed in {@code retryableServerStatuses}: if the rate limit is not retried, the response is returned and the
 * 5xx rules are not applied to it.
 */
final class RetryPolicy {

    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";
    private static final String OKHTTP_CANCELED = "Canceled";
    private static final int MAX_SHIFT = 16;

    /** The highest level of an escalating wait; a higher level gives the wait of this one. */
    static final int MAX_LEVEL = MAX_SHIFT + 1;

    private static final RandomGenerator DEFAULT_RANDOM = new RandomGenerator() {
        @Override
        @SuppressFBWarnings(
                value = "PREDICTABLE_RANDOM",
                justification = "retry jitter only needs uncorrelated delays, not cryptographic randomness")
        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }
    };

    private final RetryConfiguration.AttemptBudgets attempts;
    private final RetryConfiguration.WaitCaps waits;
    private final RetryConfiguration.Jitter jitter;
    private final RetryConfiguration.Retryable retryable;
    private final RateLimitDetector rateLimitDetector;
    private final @Nullable Duration totalDeadline;
    private final Clock clock;
    private final RandomGenerator random;

    RetryPolicy(final RetryConfiguration configuration) {
        this(configuration, Clock.systemUTC(), DEFAULT_RANDOM);
    }

    RetryPolicy(final RetryConfiguration configuration, final Clock clock) {
        this(configuration, clock, DEFAULT_RANDOM);
    }

    RetryPolicy(final RetryConfiguration configuration, final Clock clock, final RandomGenerator random) {
        requireNonNull(configuration, "configuration");
        this.attempts = configuration.attempts();
        this.waits = configuration.waits();
        this.jitter = configuration.jitter();
        this.retryable = configuration.retryable();
        this.rateLimitDetector = configuration.rateLimitDetector();
        this.totalDeadline = configuration.totalDeadline();
        this.clock = requireNonNull(clock, "clock");
        this.random = requireNonNull(random, "random");
    }

    static RandomGenerator defaultRandom() {
        return DEFAULT_RANDOM;
    }

    /**
     * How to treat {@code response} when it is a rate limit.
     *
     * @param request request that {@code response} answers
     * @param response last response
     * @param attempt 1-based rate-limit attempt that just finished; it sets the backoff of a limit that names no wait
     * @param rateLimitWaits how many times the call has already waited for a rate limit, whether its own or one that
     *     the gate held it back for; the wait budget is shared
     * @return the verdict, or empty when the detector does not see a rate limit in {@code response}
     */
    Optional<RateLimitVerdict> rateLimit(
            final HttpRequest request, final HttpResponse response, final int attempt, final int rateLimitWaits) {
        return rateLimit(request, response, rateLimitWaits, bucket -> unnamedWaitBackoff(attempt));
    }

    /**
     * How to treat {@code response} when it is a rate limit, with the wait of a limit that names none supplied by the
     * caller. The gate keeps that wait per credential and bucket, so it does not depend on the attempt of one call.
     *
     * @param request request that {@code response} answers
     * @param response last response
     * @param rateLimitWaits how many times the call has already waited for a rate limit, whether its own or one that
     *     the gate held it back for; the wait budget is shared
     * @param unnamedWait the wait for a limit that names neither a {@code Retry-After} nor a reset, given the bucket of
     *     that limit; called once, and only for such a limit
     * @return the verdict, or empty when the detector does not see a rate limit in {@code response}
     */
    Optional<RateLimitVerdict> rateLimit(
            final HttpRequest request,
            final HttpResponse response,
            final int rateLimitWaits,
            final Function<@Nullable String, Duration> unnamedWait) {
        requireNonNull(request, REQUEST);
        requireNonNull(response, RESPONSE);
        return rateLimitDetector.detect(response, request, clock).map(signal -> {
            final Duration required = rateLimitWait(signal, response, unnamedWait);
            final RateLimitVerdict.Outcome outcome = outcome(hasRequestedWait(signal), required, rateLimitWaits);
            // The jitter is drawn only for a wait that is taken: a huge requested wait could overflow when it is added.
            final Duration delay = outcome == RateLimitVerdict.Outcome.RETRY ? withRateLimitJitter(required) : required;
            return new RateLimitVerdict(signal.bucket(), required, delay, outcome);
        });
    }

    /**
     * Wait before retrying a response that is a retryable 5xx, or empty when the call should not be retried.
     *
     * @param request last request
     * @param response last response
     * @param attempt 1-based 5xx attempt that just finished
     * @return delay when another try should run
     */
    Optional<Duration> serverErrorDelay(final HttpRequest request, final HttpResponse response, final int attempt) {
        requireNonNull(request, REQUEST);
        requireNonNull(response, RESPONSE);
        if (attempt >= attempts.maxServerErrorAttempts()
                || retryable.retryableServerStatuses().isEmpty()
                || isNotRetryableMethod(request.method())
                || !retryable.retryableServerStatuses().contains(response.statusCode())) {
            return Optional.empty();
        }
        final Duration wait = nonNegative(serverErrorWait(response, attempt));
        if (wait.compareTo(waits.maxServerErrorWait()) > 0) {
            return Optional.empty();
        }
        return Optional.of(wait);
    }

    /**
     * Wait after an I/O failure, or empty when that failure should not be retried. A failure to open the connection is
     * retried for every method, because no request byte was sent; any other failure only for the
     * {@code ioRetryableMethods}, because the server may have applied the request.
     *
     * @param request failed request
     * @param failure the failure
     * @param attempt 1-based I/O attempt that just finished
     * @return delay when another try should run
     */
    Optional<Duration> afterIoFailure(final HttpRequest request, final IOException failure, final int attempt) {
        requireNonNull(request, REQUEST);
        requireNonNull(failure, "failure");
        if (attempt >= attempts.maxIoAttempts()
                || !retryable.retryIoExceptions()
                || isNeverRetried(failure)
                || isNotRetryableMethod(request.method()) && !isConnectFailure(failure)) {
            return Optional.empty();
        }
        return Optional.of(withJitter(backoffForAttempt(attempt)));
    }

    /**
     * Whether {@code failure} is never retried, for any method or attempt, because repeating the call cannot succeed or
     * must not happen: a request body that failed, a missing file, an unknown host, a TLS certificate or handshake
     * failure, a call cancelled by OkHttp, a response over its size limit, a deadline-style interrupt, and a closed
     * transport. The transport checks this before it attaches an earlier failure as suppressed, so such a failure is
     * thrown unchanged.
     *
     * @param failure the failure
     * @return {@code true} when the failure must not be retried
     */
    boolean isNeverRetried(final IOException failure) {
        return failure instanceof ResponseTooLargeException
                || failure instanceof NonRetryableIOException
                || failure instanceof FileNotFoundException
                || failure instanceof UnknownHostException
                || isTlsValidationFailure(failure)
                || isOkHttpCancellation(failure)
                || failure instanceof InterruptedIOException && !(failure instanceof SocketTimeoutException);
    }

    /**
     * Instant after which no further attempt may begin.
     *
     * @return deadline measured from now, or {@code null} when the configuration sets none
     */
    @Nullable Instant deadline() {
        if (totalDeadline == null) {
            return null;
        }
        try {
            return clock.instant().plus(totalDeadline);
        } catch (final DateTimeException | ArithmeticException unbounded) {
            return null;
        }
    }

    /**
     * Whether waiting {@code delay} would start the next attempt after {@code deadline}.
     *
     * @param deadline value from {@link #deadline()}, or {@code null} for none
     * @param delay planned wait before the next attempt
     * @return {@code true} when the retry must not run
     */
    boolean isBeyondDeadline(final @Nullable Instant deadline, final Duration delay) {
        return deadline != null && delay.compareTo(Duration.between(clock.instant(), deadline)) > 0;
    }

    Duration backoffForAttempt(final int failedAttempt) {
        final int shift = Math.min(failedAttempt - 1, MAX_SHIFT);
        // defaultBackoff fits in nanoseconds and the multiplier is at most 2^MAX_SHIFT, so the product cannot overflow.
        return cap(waits.defaultBackoff().multipliedBy(1L << shift));
    }

    /**
     * Wait for a rate limit that names none: {@code minRateLimitWait} doubled for every earlier rate limit, cut to
     * {@code maxRateLimitWait}.
     */
    Duration unnamedWaitBackoff(final int rateLimitAttempt) {
        return escalatedWait(waits.minRateLimitWait(), waits.maxRateLimitWait(), rateLimitAttempt);
    }

    /**
     * {@code minimum} doubled for each level above the first, cut to {@code maximum}. Levels above the
     * {@value #MAX_LEVEL}th give the wait of that level, so the product cannot overflow.
     *
     * @param minimum wait of the first level
     * @param maximum longest wait
     * @param level 1-based level
     * @return the wait of {@code level}
     */
    static Duration escalatedWait(final Duration minimum, final Duration maximum, final int level) {
        final int shift = Math.min(level - 1, MAX_SHIFT);
        // minimum fits in nanoseconds and the multiplier is at most 2^MAX_SHIFT, so no overflow.
        final Duration backoff = minimum.multipliedBy(1L << shift);
        return backoff.compareTo(maximum) > 0 ? maximum : backoff;
    }

    /** The jitter a thread released from a rate limit adds to its wait. */
    Duration withRateLimitJitter(final Duration base) {
        final Duration nonNegativeBase = nonNegative(base);
        final Duration rateLimitJitter = jitter.rateLimitJitter();
        if (isNonPositive(rateLimitJitter)) {
            return nonNegativeBase;
        }
        // Jitter rejects a rate-limit jitter beyond the nanosecond range, so toNanos cannot throw here.
        return nonNegativeBase.plusNanos(random.nextLong(rateLimitJitter.toNanos()));
    }

    Duration maxRateLimitWait() {
        return waits.maxRateLimitWait();
    }

    boolean isRetryingRateLimits() {
        return retryable.retryRateLimitedResponses();
    }

    /**
     * Whether a call that has waited {@code rateLimitWaits} times for rate limits, its own or the gate's, may not wait
     * again. A call waits at most {@code maxRateLimitAttempts - 1} times in total.
     */
    boolean isRateLimitBudgetUsed(final int rateLimitWaits) {
        return rateLimitWaits >= attempts.maxRateLimitAttempts() - 1;
    }

    /**
     * Whether a call must not wait at a rate-limit gate and fails fast instead: rate-limit retries are off, the budget
     * is spent, the block lasts longer than {@link #maxRateLimitWait()}, or the wait would pass the deadline.
     *
     * @param rateLimitWaits rate-limit waits of the call so far, its own and the gate's
     * @param remaining time the gate still blocks the bucket
     * @param deadline value from {@link #deadline()}, or {@code null} for none
     * @param delay the jittered wait that would follow
     * @return {@code true} when the call must not wait
     */
    boolean isGateWaitRefused(
            final int rateLimitWaits,
            final Duration remaining,
            final @Nullable Instant deadline,
            final Duration delay) {
        return !isRetryingRateLimits()
                || isRateLimitBudgetUsed(rateLimitWaits)
                || remaining.compareTo(maxRateLimitWait()) > 0
                || isBeyondDeadline(deadline, delay);
    }

    String bucketOf(final HttpRequest request) {
        return rateLimitDetector.bucketOf(request);
    }

    /**
     * The quota that {@code response} reports as used up although it was not rejected, or empty.
     *
     * @param request request that {@code response} answers
     * @param response response that was not rejected
     * @return the signal of the detector, if any
     */
    Optional<RateLimitSignal> exhaustion(final HttpRequest request, final HttpResponse response) {
        requireNonNull(request, REQUEST);
        requireNonNull(response, RESPONSE);
        return rateLimitDetector.detectExhaustion(response, request, clock);
    }

    /**
     * Wait until a reset instant, as the server's clock reads it in {@code response}: the time from the {@code Date}
     * header (or the local clock) to {@code resetAt}, plus {@link RetryConfiguration.Jitter#clockSkewMargin()}.
     *
     * @param resetAt instant at which the quota is restored
     * @param response response that carries the {@code Date} header, if any
     * @return the wait, never negative
     */
    Duration untilReset(final Instant resetAt, final HttpResponse response) {
        final Instant serverNow = response.serverDate().orElseGet(clock::instant);
        return nonNegative(Duration.between(serverNow, resetAt)).plus(jitter.clockSkewMargin());
    }

    private Duration serverErrorWait(final HttpResponse response, final int failedAttempt) {
        return response.retryAfter(clock).orElseGet(() -> withJitter(backoffForAttempt(failedAttempt)));
    }

    private static boolean hasRequestedWait(final RateLimitSignal signal) {
        return positive(signal.retryAfter()) != null || signal.resetAt() != null;
    }

    private Duration rateLimitWait(
            final RateLimitSignal signal,
            final HttpResponse response,
            final Function<@Nullable String, Duration> unnamedWait) {
        final Duration retryAfter = positive(signal.retryAfter());
        final Instant resetAt = signal.resetAt();
        final Duration untilReset = resetAt == null ? null : untilReset(resetAt, response);
        if (retryAfter == null && untilReset == null) {
            return unnamedWait.apply(signal.bucket());
        }
        if (retryAfter == null) {
            return untilReset;
        }
        return untilReset != null && untilReset.compareTo(retryAfter) > 0 ? untilReset : retryAfter;
    }

    private static @Nullable Duration positive(final @Nullable Duration delay) {
        return delay == null || isNonPositive(delay) ? null : delay;
    }

    private RateLimitVerdict.Outcome outcome(
            final boolean isRequested, final Duration required, final int rateLimitWaits) {
        if (!retryable.retryRateLimitedResponses()) {
            return RateLimitVerdict.Outcome.NOT_RETRIED;
        }
        if (isRateLimitBudgetUsed(rateLimitWaits)) {
            return RateLimitVerdict.Outcome.ATTEMPTS_EXHAUSTED;
        }
        if (isRequested && required.compareTo(waits.maxRateLimitWait()) > 0) {
            return RateLimitVerdict.Outcome.WAIT_TOO_LONG;
        }
        return RateLimitVerdict.Outcome.RETRY;
    }

    private static Duration nonNegative(final Duration delay) {
        return isNonPositive(delay) ? Duration.ZERO : delay;
    }

    // Certificate and handshake failures repeat identically; a plain SSLException (a reset mid-session) stays
    // retryable.
    private static boolean isTlsValidationFailure(final IOException failure) {
        return failure instanceof SSLHandshakeException
                || failure instanceof SSLPeerUnverifiedException
                || failure instanceof SSLKeyException;
    }

    // OkHttp reports Call.cancel() as a plain IOException with this message.
    private static boolean isOkHttpCancellation(final IOException failure) {
        return failure.getClass() == IOException.class && OKHTTP_CANCELED.equals(failure.getMessage());
    }

    // The TCP connection was never established, so the request cannot have reached the server. A connect timeout is a
    // SocketTimeoutException like a read timeout and is not told apart, so it stays method-restricted.
    private static boolean isConnectFailure(final IOException failure) {
        return failure instanceof ConnectException || failure instanceof NoRouteToHostException;
    }

    private boolean isNotRetryableMethod(final HttpMethod method) {
        return !retryable.ioRetryableMethods().contains(method);
    }

    private Duration withJitter(final Duration base) {
        final Duration cappedBase = cap(base);
        if (isNonPositive(cappedBase) || !jitter.fullJitter()) {
            return cappedBase;
        }
        // cappedBase is at most maxBackoff, and WaitCaps rejects a maxBackoff that does not fit in nanoseconds.
        return Duration.ofNanos(random.nextLong(cappedBase.toNanos()));
    }

    private Duration cap(final Duration delay) {
        if (isNonPositive(delay)) {
            return Duration.ZERO;
        }
        final Duration maxBackoff = waits.maxBackoff();
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    /**
     * What to do about a rate-limited response.
     *
     * @param bucket the quota the limit applies to, or {@code null} for all
     * @param required the wait the limit asks for, without jitter
     * @param delay {@code required} plus jitter: the pause when the outcome is {@link Outcome#RETRY}
     * @param outcome whether to wait and retry, and if not, why
     */
    record RateLimitVerdict(@Nullable String bucket, Duration required, Duration delay, Outcome outcome) {

        enum Outcome {
            RETRY,
            NOT_RETRIED,
            ATTEMPTS_EXHAUSTED,
            WAIT_TOO_LONG
        }
    }

    /**
     * A pause before a retry and the kind of failure that caused it.
     *
     * @param delay how long to pause
     * @param reason which attempt counter the retry advances
     */
    record RetryWait(Duration delay, Reason reason) {

        enum Reason {
            IO,
            RATE_LIMIT,
            SERVER_ERROR
        }
    }
}
