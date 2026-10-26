package io.github.piotr_janczukowicz.http.transport;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Provider-neutral outcome of detecting a rate-limited response.
 *
 * <p>A signal that carries both a positive {@code retryAfter} and a {@code resetAt} asks for the <em>later</em> of the
 * two: {@link RetryPolicy} waits until the reset (measured against the server's {@code Date} header, plus
 * {@link RetryConfiguration.Jitter#clockSkewMargin()}) or for {@code retryAfter}, whichever ends last. A signal with
 * neither asks for nothing specific, and the policy waits {@code minRateLimitWait}, doubled on every further rate limit
 * of the same credential and bucket (across calls when rate limits are coordinated, otherwise within one call) and
 * capped at {@code maxRateLimitWait}. A {@code retryAfter} that is zero or negative counts as absent.
 *
 * @param retryAfter server-requested delay, or {@code null}
 * @param resetAt instant at which the quota is restored, or {@code null}
 * @param bucket the quota the limit applies to: requests that
 *     {@link RateLimitDetector#bucketOf(io.github.piotr_janczukowicz.http.HttpRequest)} maps to another bucket are not
 *     held back by it. {@code null} when the limit applies to every request of the credential
 */
public record RateLimitSignal(
        @Nullable Duration retryAfter,
        @Nullable Instant resetAt,
        @Nullable String bucket) {

    /**
     * Signal that applies to every request of the credential.
     *
     * @param retryAfter server-requested delay, or {@code null}
     * @param resetAt instant at which the quota is restored, or {@code null}
     */
    public RateLimitSignal(final @Nullable Duration retryAfter, final @Nullable Instant resetAt) {
        this(retryAfter, resetAt, null);
    }
}
