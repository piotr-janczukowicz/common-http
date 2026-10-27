package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNegative;

import java.time.Duration;

/**
 * How long to wait for a result that the server computes in the background. Such an endpoint, for example a report,
 * answers HTTP 202 Accepted without data until the result is ready, and asks the caller to try again.
 *
 * <p>The polling is a fixed number of tries with a fixed pause between them; it has no backoff and does not read
 * {@code Retry-After}, because such responses carry none. Each try is a full request, so it counts against the rate
 * limit. The wait blocks the calling thread.
 *
 * @param maxAttempts how many requests to send at most, including the first; at least 1, and 1 means no waiting
 * @param delay pause between two requests; not negative, and zero retries at once
 */
public record HttpAcceptedPolling(int maxAttempts, Duration delay) {

    private static final int DEFAULT_ATTEMPTS = 5;
    private static final Duration DEFAULT_DELAY = Duration.ofSeconds(2);

    public HttpAcceptedPolling {
        requireAtLeast(maxAttempts, 1, "maxAttempts");
        requireNonNegative(delay, "delay");
    }

    /**
     * Five tries, two seconds apart: up to about eight seconds of waiting, which is enough for most results.
     *
     * @return the default polling
     */
    public static HttpAcceptedPolling defaults() {
        return new HttpAcceptedPolling(DEFAULT_ATTEMPTS, DEFAULT_DELAY);
    }
}
