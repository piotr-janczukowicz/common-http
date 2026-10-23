package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtLeast;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtMost;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonEmpty;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNegative;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requirePositive;

import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpMethod;

/**
 * Immutable retry settings for {@link RetryingHttpTransport}.
 *
 * <p>Three independent budgets count tries including the first: {@code maxIoAttempts} for I/O failures,
 * {@code maxRateLimitAttempts} for rate limits and {@code maxServerErrorAttempts} for retryable 5xx. I/O and 5xx share
 * {@code ioRetryableMethods} (default GET, HEAD, OPTIONS); rate-limited responses are retried for every method. The
 * wait caps for I/O and 5xx default to {@code maxBackoff}.
 *
 * <p>A rate limit that names its wait, as {@code Retry-After} or a reset time (or both, when the later one counts), is
 * waited out if that is at most {@code maxRateLimitWait} (5 minutes by default) and otherwise not retried. A rate limit
 * that names none waits {@code minRateLimitWait} (60 seconds), then twice that on the next one, and so on, never more
 * than {@code maxRateLimitWait}; with the defaults that is 60, 120 and 240 seconds, after which the fourth rejection is
 * returned. {@code rateLimitJitter} (10 seconds by default) is a random extra wait on top, so that threads released by
 * the same limit do not resume together. So {@code minRateLimitWait} must not exceed {@code maxRateLimitWait},
 * otherwise such a response could never be retried; that is rejected here. With the defaults, repeated limits of a
 * call's own that name no wait are retried after 60, 120 and 240 seconds (about 7 minutes plus jitter), and a primary
 * limit that resets within 5 minutes is waited out. The waits of one call for rate limits share one budget: it waits at
 * most {@code maxRateLimitAttempts - 1} times in total, whether it waits out its own rate-limited response or pauses
 * for a limit that another call reported (see below), each wait at most {@code maxRateLimitWait} plus jitter. The worst
 * case per call, own waits and gate pauses together, is therefore 3 waits of at most 5 minutes plus 10 seconds of
 * jitter with the defaults: about 15.5 minutes. The request, including its {@code Authorization} header, is built once
 * and resent on every retry. A credential that can expire during the waits, such as a short-lived access token, must
 * outlive the longest possible total wait, so keep {@code maxRateLimitWait} (times the attempts) well below its
 * lifetime.
 *
 * <p>With {@code coordinateRateLimits} (the default) the transport also remembers a rate limit across calls: while one
 * is in force, a request that it applies to waits for it, or fails fast with the stored rejection when the wait does
 * not fit, instead of being sent and rejected again. {@code rateLimitEventListener} is told about each wait and each
 * decision not to wait.
 *
 * <p>{@code totalDeadline}, when set, is the time budget from the first attempt of <em>one</em>
 * {@link RetryingHttpTransport#execute} call: no retry (or the wait before it) starts once it would begin after the
 * deadline, and the last response or failure is surfaced instead; an attempt already running is bounded by the
 * transport timeouts. The budget is not shared across calls, so every redirect hop and every pagination page is a
 * separate call with a fresh budget, and a walk of many pages can take many times {@code totalDeadline}.
 * {@link RetryPolicy} applies these values.
 *
 * @param attempts attempt caps
 * @param waits backoff and wait-to-retry caps
 * @param jitter jitter and clock-skew settings
 * @param retryable which failures are retried
 * @param rateLimitDetector recognizes rate-limited responses
 * @param coordinateRateLimits whether a rate limit also holds back later calls of the same credential
 * @param rateLimitEventListener receives rate-limit waits and give-ups
 * @param totalDeadline time budget for all attempts and waits of one transport call, or {@code null} for none
 */
public record RetryConfiguration(
        AttemptBudgets attempts,
        WaitCaps waits,
        Jitter jitter,
        Retryable retryable,
        RateLimitDetector rateLimitDetector,
        boolean coordinateRateLimits,
        RateLimitEventListener rateLimitEventListener,
        @Nullable Duration totalDeadline) {

    private static final Set<HttpMethod> DEFAULT_IO_RETRYABLE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final RateLimitDetector DEFAULT_RATE_LIMIT_DETECTOR = RateLimitDetector.defaults();

    private static final RateLimitEventListener NO_EVENT_LISTENER = event -> {};

    private static final Set<Integer> DEFAULT_RETRYABLE_SERVER_STATUSES = Set.of(502, 503, 504);

    // Retry delays are computed in nanoseconds, the range Duration.toNanos() accepts.
    private static final Duration MAX_NANOS = Duration.ofNanos(Long.MAX_VALUE);

    public RetryConfiguration {
        requireNonNull(attempts, "attempts");
        requireNonNull(waits, "waits");
        requireNonNull(jitter, "jitter");
        requireNonNull(retryable, "retryable");
        requireNonNull(rateLimitDetector, "rateLimitDetector");
        requireNonNull(rateLimitEventListener, "rateLimitEventListener");
        requireAtLeast(waits.maxBackoff(), waits.defaultBackoff(), "maxBackoff");
        requireAtLeast(waits.maxRateLimitWait(), waits.minRateLimitWait(), "maxRateLimitWait");
        if (totalDeadline != null) {
            requirePositive(totalDeadline, "totalDeadline");
        }
    }

    /**
     * Three tries for I/O failures and for 5xx, four for rate limits, 1s/60s backoff, full jitter. Rate-limit retries
     * apply to every method (the request was rejected, not applied); I/O-failure and 5xx retries apply to GET, HEAD and
     * OPTIONS only, because a replayed PUT or DELETE may repeat an effect the server already applied; the exception is
     * a failure to open the connection ({@link java.net.ConnectException}, {@link java.net.NoRouteToHostException}),
     * which is retried for every method since the request was never sent. Opt the other methods in with
     * {@link Builder#ioRetryableMethods(Set)}.
     *
     * <p>A rate limit waits at least 60 seconds and at most 5 minutes, plus up to 10 seconds of jitter: a primary limit
     * that resets within 5 minutes is waited out, a longer one is returned at once, and repeated limits that name no
     * wait are retried after 60, 120 and 240 seconds, which is about 7 minutes of waiting plus jitter. A call waits at
     * most three times in total for rate limits, its own and those that other calls reported, so the worst case is
     * about 15.5 minutes (3 times 5 minutes 10 seconds). Limits are remembered across calls
     * ({@code coordinateRateLimits}), and no event listener is set.
     */
    public static RetryConfiguration defaults() {
        return builder().build();
    }

    /**
     * Copy using {@code detector}.
     *
     * @param detector detector that recognizes rate-limited responses
     * @return configuration with {@code detector}
     * @throws NullPointerException if {@code detector} is {@code null}
     */
    public RetryConfiguration withRateLimitDetector(final RateLimitDetector detector) {
        return new RetryConfiguration(
                attempts,
                waits,
                jitter,
                retryable,
                detector,
                coordinateRateLimits,
                rateLimitEventListener,
                totalDeadline);
    }

    /**
     * Whether the generic default detector is in place, which is also what {@link RateLimitDetector#defaults()}
     * returns. Callers that supply a provider-specific detector use this to tell whether one was already chosen.
     *
     * @return {@code true} when the detector is {@link RateLimitDetector#defaults()}
     */
    public boolean hasDefaultRateLimitDetector() {
        return rateLimitDetector == DEFAULT_RATE_LIMIT_DETECTOR;
    }

    /** Builder initialized to {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireNonNegativeNanos(final Duration value, final String name) {
        requireAtMost(requireNonNegative(value, name), MAX_NANOS, name);
    }

    private static void requirePositiveNanos(final Duration value, final String name) {
        requireAtMost(requirePositive(value, name), MAX_NANOS, name);
    }

    /** I/O, rate-limit and 5xx tries, each budget independent and including the first try. */
    public record AttemptBudgets(int maxIoAttempts, int maxRateLimitAttempts, int maxServerErrorAttempts) {
        public AttemptBudgets {
            requireAtLeast(maxIoAttempts, 1, "maxIoAttempts");
            requireAtLeast(maxRateLimitAttempts, 1, "maxRateLimitAttempts");
            requireAtLeast(maxServerErrorAttempts, 1, "maxServerErrorAttempts");
        }
    }

    /**
     * Exponential backoff bounds for I/O and 5xx, and wait-to-retry caps. {@code maxBackoff} is not a wait-to-retry
     * cap. {@code minRateLimitWait} is the shortest rate-limit wait and the start of the rate-limit backoff, which does
     * not use {@code defaultBackoff} or {@code maxBackoff}. {@code maxRateLimitBlock} is the longest that one reported
     * rate limit holds back later calls, however long it asked for. Each duration must fit in nanoseconds (at most
     * about 292 years), otherwise it is rejected with {@link IllegalArgumentException}.
     */
    public record WaitCaps(
            Duration defaultBackoff,
            Duration maxBackoff,
            Duration maxRateLimitWait,
            Duration maxServerErrorWait,
            Duration minRateLimitWait,
            Duration maxRateLimitBlock) {
        public WaitCaps {
            requireNonNegativeNanos(defaultBackoff, "defaultBackoff");
            requireNonNegativeNanos(maxBackoff, "maxBackoff");
            requireNonNegativeNanos(maxRateLimitWait, "maxRateLimitWait");
            requireNonNegativeNanos(maxServerErrorWait, "maxServerErrorWait");
            requireNonNegativeNanos(minRateLimitWait, "minRateLimitWait");
            requirePositiveNanos(maxRateLimitBlock, "maxRateLimitBlock");
        }
    }

    /**
     * Full jitter for I/O/5xx, extra rate-limit delay bound, and rate-limit reset clock-skew margin. Both durations
     * must fit in nanoseconds (at most about 292 years), otherwise they are rejected with
     * {@link IllegalArgumentException}.
     */
    public record Jitter(boolean fullJitter, Duration rateLimitJitter, Duration clockSkewMargin) {
        public Jitter {
            requireNonNegativeNanos(rateLimitJitter, "rateLimitJitter");
            requireNonNegativeNanos(clockSkewMargin, "clockSkewMargin");
        }
    }

    /** Which rate-limit, I/O, method, and 5xx failures are retried. */
    public record Retryable(
            boolean retryRateLimitedResponses,
            boolean retryIoExceptions,
            Set<HttpMethod> ioRetryableMethods,
            Set<Integer> retryableServerStatuses) {
        public Retryable {
            ioRetryableMethods = Set.copyOf(requireNonEmpty(ioRetryableMethods, "ioRetryableMethods"));
            retryableServerStatuses = Set.copyOf(requireNonNull(retryableServerStatuses, "retryableServerStatuses"));
            for (final int status : retryableServerStatuses) {
                if (status < 400 || status > 599) {
                    throw new IllegalArgumentException(
                            "retryableServerStatuses must hold only 4xx and 5xx statuses, got " + status);
                }
            }
        }
    }

    /** Builds an {@link RetryConfiguration}. Unset fields keep the {@link #defaults()} values. */
    @SuppressWarnings("PMD.TooManyFields")
    public static final class Builder {
        private int maxIoAttempts = 3;
        private int maxRateLimitAttempts = 4;
        private Duration defaultBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(60);
        private boolean isFullJitter = true;
        private boolean isRetryingRateLimitedResponses = true;
        private boolean isRetryingIoExceptions = true;
        private Set<HttpMethod> ioRetryableMethods = DEFAULT_IO_RETRYABLE_METHODS;
        private Duration maxRateLimitWait = Duration.ofMinutes(5);
        private @Nullable Duration maxServerErrorWait;
        private Duration minRateLimitWait = Duration.ofSeconds(60);
        private Duration maxRateLimitBlock = Duration.ofHours(1);
        private Duration rateLimitJitter = Duration.ofSeconds(10);
        private Duration clockSkewMargin = Duration.ofSeconds(1);
        private Set<Integer> retryableServerStatuses = DEFAULT_RETRYABLE_SERVER_STATUSES;
        private int maxServerErrorAttempts = 3;
        private RateLimitDetector rateLimitDetector = DEFAULT_RATE_LIMIT_DETECTOR;
        private boolean isCoordinatingRateLimits = true;
        private RateLimitEventListener rateLimitEventListener = NO_EVENT_LISTENER;
        private @Nullable Duration totalDeadline;

        private Builder() {}

        /**
         * Attempts allowed for I/O failures, counting the first. Rate-limited responses and 5xx have their own budgets.
         *
         * @param maxIoAttempts total attempts, at least 1; default 3
         * @return this builder
         */
        public Builder maxIoAttempts(final int maxIoAttempts) {
            this.maxIoAttempts = maxIoAttempts;
            return this;
        }

        /**
         * Attempts allowed for rate limits, counting the first. A call waits at most {@code maxRateLimitAttempts - 1}
         * times in total for rate limits, whether it waits out its own rate-limited response or pauses for a limit that
         * another call reported ({@link #coordinateRateLimits(boolean)}). The default 4 is the first try plus three
         * waits, which are 60, 120 and 240 seconds when the limit names no wait. With 1 the call never waits for a rate
         * limit: a rate-limited response is returned at once and a limit in force is answered by the stored rejection.
         *
         * @param maxRateLimitAttempts total attempts, at least 1; default 4
         * @return this builder
         */
        public Builder maxRateLimitAttempts(final int maxRateLimitAttempts) {
            this.maxRateLimitAttempts = maxRateLimitAttempts;
            return this;
        }

        /**
         * Base of the exponential wait between I/O and 5xx attempts when the server gives no {@code Retry-After}. Rate
         * limits do not use it: see {@link #minRateLimitWait(Duration)}.
         *
         * @param defaultBackoff wait before the second attempt, not negative; default 1 second
         * @return this builder
         */
        public Builder defaultBackoff(final Duration defaultBackoff) {
            this.defaultBackoff = defaultBackoff;
            return this;
        }

        /**
         * Upper bound of the exponential wait between I/O and 5xx attempts.
         *
         * @param maxBackoff largest wait, not below {@code defaultBackoff}; default 60 seconds
         * @return this builder
         */
        public Builder maxBackoff(final Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Whether each backoff wait is drawn at random between zero and its computed value, which spreads out clients
         * that failed together.
         *
         * @param fullJitter {@code true} to randomise the wait; default {@code true}
         * @return this builder
         */
        public Builder fullJitter(final boolean fullJitter) {
            this.isFullJitter = fullJitter;
            return this;
        }

        /**
         * Whether rate-limited responses are retried after the wait the limit asks for.
         *
         * @param retryRateLimitedResponses {@code false} to return such a response to the caller; default {@code true}
         * @return this builder
         */
        public Builder retryRateLimitedResponses(final boolean retryRateLimitedResponses) {
            this.isRetryingRateLimitedResponses = retryRateLimitedResponses;
            return this;
        }

        /**
         * Whether I/O failures are retried for the methods in {@link #ioRetryableMethods(Set)}. A
         * {@link java.net.ConnectException} or {@link java.net.NoRouteToHostException} is retried for every method,
         * because the transport may throw them only when no byte of the request was sent; see
         * {@link pl.piotrjanczukowicz.http.transport.HttpTransport#execute}.
         *
         * @param retryIoExceptions {@code false} to throw on the first failure; default {@code true}
         * @return this builder
         */
        public Builder retryIoExceptions(final boolean retryIoExceptions) {
            this.isRetryingIoExceptions = retryIoExceptions;
            return this;
        }

        /**
         * Methods retried after an I/O failure or a retryable 5xx. Default: GET, HEAD and OPTIONS. A failure to open
         * the connection ({@link java.net.ConnectException}, {@link java.net.NoRouteToHostException}) is retried for
         * every method regardless; this relies on a transport throwing them only when no byte of the request was sent
         * (see {@link pl.piotrjanczukowicz.http.transport.HttpTransport#execute}). PUT and DELETE are idempotent by
         * specification but a lost response may hide an applied change, so add them only when that is acceptable.
         * Rate-limited responses (429, and what the detector recognizes) are retried for every method regardless of
         * this set.
         *
         * @param ioRetryableMethods methods to retry, not empty when the configuration is built
         * @return this builder
         * @throws NullPointerException if {@code ioRetryableMethods} is {@code null}
         */
        public Builder ioRetryableMethods(final Set<HttpMethod> ioRetryableMethods) {
            this.ioRetryableMethods = Set.copyOf(requireNonNull(ioRetryableMethods, "ioRetryableMethods"));
            return this;
        }

        /**
         * Longest single wait for a rate limit, before {@link #rateLimitJitter(Duration)} is added. A limit that names
         * its wait ({@code Retry-After} or a reset time) and asks for more is not waited out: the response is returned.
         * The wait of a limit that names none, which doubles from {@link #minRateLimitWait(Duration)}, is cut to this
         * value instead. The jitter comes on top, so the actual pause can exceed this value by up to
         * {@code rateLimitJitter}. Unlike {@link #maxServerErrorWait(Duration)}, it has no derived default and does not
         * accept {@code null}.
         *
         * @param maxRateLimitWait cap, not below {@code minRateLimitWait}; default 5 minutes
         * @return this builder
         * @throws NullPointerException if {@code maxRateLimitWait} is {@code null}
         */
        public Builder maxRateLimitWait(final Duration maxRateLimitWait) {
            this.maxRateLimitWait = requireNonNull(maxRateLimitWait, "maxRateLimitWait");
            return this;
        }

        /**
         * Longest that one reported rate limit holds back later calls ({@link #coordinateRateLimits(boolean)}), however
         * long the response that reported it asked for, so that one absurd {@code Retry-After} cannot disable a
         * credential. Set it to the longest limit window of the API.
         *
         * @param maxRateLimitBlock cap, positive; default 1 hour
         * @return this builder
         * @throws NullPointerException if {@code maxRateLimitBlock} is {@code null}
         */
        public Builder maxRateLimitBlock(final Duration maxRateLimitBlock) {
            this.maxRateLimitBlock = requireNonNull(maxRateLimitBlock, "maxRateLimitBlock");
            return this;
        }

        /**
         * Longest single wait before retrying a retryable 5xx response.
         *
         * @param maxServerErrorWait cap, or {@code null} to restore the default ({@code maxBackoff})
         * @return this builder
         */
        public Builder maxServerErrorWait(final @Nullable Duration maxServerErrorWait) {
            this.maxServerErrorWait = maxServerErrorWait;
            return this;
        }

        /**
         * Wait for a rate limit that names no wait, such as a limit without {@code Retry-After}, and the start of the
         * backoff for repeated ones: the wait doubles with each further rate limit of the same credential and bucket
         * (across calls when rate limits are coordinated, otherwise within one call), up to
         * {@link #maxRateLimitWait(Duration)}. Many APIs ask for at least a minute.
         *
         * @param minRateLimitWait wait, not negative; default 60 seconds
         * @return this builder
         */
        public Builder minRateLimitWait(final Duration minRateLimitWait) {
            this.minRateLimitWait = minRateLimitWait;
            return this;
        }

        /**
         * Random extra wait added to a rate-limit wait so that clients, and the threads of one client that waited for
         * the same limit, do not resume together. It is added after the {@link #maxRateLimitWait(Duration)} check, so
         * it is not part of that cap.
         *
         * @param rateLimitJitter upper bound of the extra wait, not negative; default 10 seconds
         * @return this builder
         */
        public Builder rateLimitJitter(final Duration rateLimitJitter) {
            this.rateLimitJitter = rateLimitJitter;
            return this;
        }

        /**
         * Margin added to a wait computed from a server timestamp, to absorb clock differences.
         *
         * @param clockSkewMargin margin, not negative; default 1 second
         * @return this builder
         */
        public Builder clockSkewMargin(final Duration clockSkewMargin) {
            this.clockSkewMargin = clockSkewMargin;
            return this;
        }

        /**
         * Server error statuses that are retried for the methods in {@link #ioRetryableMethods(Set)}. A response that
         * the {@link #rateLimitDetector(RateLimitDetector) rate-limit detector} flags is handled only by the rate-limit
         * settings, even when its status is listed here: it is never retried as a server error.
         *
         * @param retryableServerStatuses status codes; copied
         * @return this builder
         * @throws NullPointerException if {@code retryableServerStatuses} is {@code null}
         */
        public Builder retryableServerStatuses(final Set<Integer> retryableServerStatuses) {
            this.retryableServerStatuses =
                    Set.copyOf(requireNonNull(retryableServerStatuses, "retryableServerStatuses"));
            return this;
        }

        /**
         * Attempts allowed for retryable 5xx responses, counted separately from the I/O and rate-limit attempts.
         *
         * @param maxServerErrorAttempts total attempts, at least 1; default 3
         * @return this builder
         */
        public Builder maxServerErrorAttempts(final int maxServerErrorAttempts) {
            this.maxServerErrorAttempts = maxServerErrorAttempts;
            return this;
        }

        /**
         * Detector that recognizes rate-limited responses.
         *
         * @param rateLimitDetector detector; default {@link RateLimitDetector#defaults()}
         * @return this builder
         * @throws NullPointerException if {@code rateLimitDetector} is {@code null}
         */
        public Builder rateLimitDetector(final RateLimitDetector rateLimitDetector) {
            this.rateLimitDetector = requireNonNull(rateLimitDetector, "rateLimitDetector");
            return this;
        }

        /**
         * Whether a rate limit also holds back later calls. When {@code true}, the transport remembers a limit for the
         * credential and origin it was seen on, with the bucket the detector names ({@code null} meaning all of them):
         * a request it applies to waits for it, or, if the wait would exceed {@link #maxRateLimitWait(Duration)} or the
         * total deadline, or rate-limit retries are off, is answered without being sent by the stored rejection, with
         * {@code Retry-After} set to the time left and the header
         * {@value pl.piotrjanczukowicz.http.HttpHeaderNames#X_CLIENT_RATE_LIMIT_GATE} added. A pause for the gate uses
         * the same budget as waiting out a rate limit of the call's own ({@link #maxRateLimitAttempts(int)} minus one
         * waits in total); once it is used up the call gets the stored rejection, even when little of the limit is
         * left, because other calls can extend a limit while it waits. A limit is remembered for
         * {@link #maxRateLimitBlock(Duration)} at most. That protects the account from the ban that some providers
         * threaten for requests sent while limited. When {@code false}, only the call that met the limit reacts to it.
         *
         * @param coordinateRateLimits {@code false} to leave every call alone; default {@code true}
         * @return this builder
         */
        public Builder coordinateRateLimits(final boolean coordinateRateLimits) {
            this.isCoordinatingRateLimits = coordinateRateLimits;
            return this;
        }

        /**
         * Listener for rate-limit waits and for the decisions not to wait; see {@link RateLimitEvent}.
         *
         * @param rateLimitEventListener listener, called on the calling thread; default none
         * @return this builder
         * @throws NullPointerException if {@code rateLimitEventListener} is {@code null}
         */
        public Builder rateLimitEventListener(final RateLimitEventListener rateLimitEventListener) {
            this.rateLimitEventListener = requireNonNull(rateLimitEventListener, "rateLimitEventListener");
            return this;
        }

        /**
         * Time budget for all attempts and waits of one {@code execute} call, from its first attempt; each redirect hop
         * and pagination page gets a fresh budget.
         *
         * @param totalDeadline budget, positive, or {@code null} (the default) for none
         * @return this builder
         */
        public Builder totalDeadline(final @Nullable Duration totalDeadline) {
            this.totalDeadline = totalDeadline;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return immutable configuration
         * @throws NullPointerException if a backoff, jitter, margin, or method set is {@code null}
         * @throws IllegalArgumentException if an attempt cap is below 1, a duration is negative, {@code maxBackoff} is
         *     below {@code defaultBackoff}, {@code maxRateLimitWait} is below {@code minRateLimitWait},
         *     {@code ioRetryableMethods} is empty, or {@code totalDeadline} or {@code maxRateLimitBlock} is not
         *     positive
         */
        public RetryConfiguration build() {
            final Duration serverErrorWaitCap = maxServerErrorWait == null ? maxBackoff : maxServerErrorWait;
            return new RetryConfiguration(
                    new AttemptBudgets(maxIoAttempts, maxRateLimitAttempts, maxServerErrorAttempts),
                    new WaitCaps(
                            defaultBackoff,
                            maxBackoff,
                            maxRateLimitWait,
                            serverErrorWaitCap,
                            minRateLimitWait,
                            maxRateLimitBlock),
                    new Jitter(isFullJitter, rateLimitJitter, clockSkewMargin),
                    new Retryable(
                            isRetryingRateLimitedResponses,
                            isRetryingIoExceptions,
                            ioRetryableMethods,
                            retryableServerStatuses),
                    rateLimitDetector,
                    isCoordinatingRateLimits,
                    rateLimitEventListener,
                    totalDeadline);
        }
    }
}
