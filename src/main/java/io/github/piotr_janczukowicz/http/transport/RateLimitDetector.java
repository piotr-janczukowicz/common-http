package io.github.piotr_janczukowicz.http.transport;

import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import java.time.Clock;
import java.util.Optional;

/**
 * Decides whether a response is a rate-limit rejection and what wait it asks for, and whether a response that was not
 * rejected reports that its quota is used up.
 *
 * <p>The retry layer calls the overloads that take the request, because a provider may only name a bucket for a request
 * it knows the path of. The overloads without a request are the ones a detector implements when it does not tell
 * requests apart.
 */
@FunctionalInterface
public interface RateLimitDetector {

    /** Bucket of a request when the detector does not tell requests apart. */
    String DEFAULT_BUCKET = "default";

    /**
     * Detects a rate limit in {@code response} without knowing the request it answers.
     *
     * @param response HTTP response
     * @param clock clock for HTTP-date {@code Retry-After} values
     * @return present when the response signaled a rate limit
     */
    Optional<RateLimitSignal> detect(HttpResponse response, Clock clock);

    /**
     * Detects a rate limit in {@code response} to {@code request}. Defaults to {@link #detect(HttpResponse, Clock)}; a
     * detector whose limits are per resource family overrides it to name the bucket of the request when the response
     * does not name one.
     *
     * @param response HTTP response
     * @param request request that {@code response} answers
     * @param clock clock for HTTP-date {@code Retry-After} values
     * @return present when the response signaled a rate limit
     */
    default Optional<RateLimitSignal> detect(
            final HttpResponse response, final HttpRequest request, final Clock clock) {
        return detect(response, clock);
    }

    /**
     * Detects a quota that {@code response} reports as used up although it was not rejected: a successful or redirect
     * response whose quota is spent until a reset instant. The signal carries that instant and no {@code Retry-After};
     * {@link RetryingHttpTransport} then holds back the bucket of {@code request} until the reset, as it does for a
     * rejected response, and returns {@code response} unchanged. Empty by default, so only a detector that knows such
     * responses reports them.
     *
     * @param response response that was not rejected
     * @param request request that {@code response} answers
     * @param clock clock that the reset instant is compared with, when {@code response} has no {@code Date}
     * @return present when the quota is spent until a reset instant in the future
     */
    default Optional<RateLimitSignal> detectExhaustion(
            final HttpResponse response, final HttpRequest request, final Clock clock) {
        return Optional.empty();
    }

    /**
     * The quota that {@code request} draws on. {@link RetryingHttpTransport} holds a request back while a limit on the
     * same bucket, or one that applies to every bucket, is in force, so a detector whose limits are per resource family
     * returns the names it also puts in {@link RateLimitSignal#bucket()}. A request whose bucket no signal ever names
     * is only held back by limits that apply to every bucket.
     *
     * @param request request about to be sent
     * @return bucket name; {@value #DEFAULT_BUCKET} unless overridden
     */
    default String bucketOf(final HttpRequest request) {
        return DEFAULT_BUCKET;
    }

    /**
     * Detector that treats HTTP 429 as a rate limit and reads {@code Retry-After}. Always the same instance, which
     * {@link RetryConfiguration#hasDefaultRateLimitDetector()} relies on.
     *
     * @return default detector
     */
    static RateLimitDetector defaults() {
        return DefaultRateLimitDetector.INSTANCE;
    }
}
