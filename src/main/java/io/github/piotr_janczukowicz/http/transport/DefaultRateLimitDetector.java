package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.util.ValidationUtils.isNonPositive;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import java.time.Clock;
import java.util.Optional;

/**
 * The detector behind {@link RateLimitDetector#defaults()}: HTTP 429 with its {@code Retry-After}, when that is a
 * positive delay. Its limits apply to every request ({@link RateLimitSignal#bucket()} is {@code null}). An enum
 * constant, so there is exactly one instance and {@link RetryConfiguration#hasDefaultRateLimitDetector()} can rely on
 * identity.
 */
enum DefaultRateLimitDetector implements RateLimitDetector {
    INSTANCE;

    @Override
    public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
        requireNonNull(response, "response");
        requireNonNull(clock, "clock");
        if (!response.hasStatus(HttpStatus.TOO_MANY_REQUESTS)) {
            return Optional.empty();
        }
        return Optional.of(new RateLimitSignal(
                response.retryAfter(clock)
                        .filter(delay -> !isNonPositive(delay))
                        .orElse(null),
                null));
    }
}
