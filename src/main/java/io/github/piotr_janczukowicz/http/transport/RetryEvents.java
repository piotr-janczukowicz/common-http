package io.github.piotr_janczukowicz.http.transport;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Reports the retry and rate-limit steps of {@link RetryingHttpTransport} to its listener and its log. The log keeps
 * the name of {@link RetryingHttpTransport}, so existing log configuration still applies.
 */
final class RetryEvents {

    private static final Logger LOG = LoggerFactory.getLogger(RetryEvents.class);

    private final RateLimitEventListener listener;
    private final Duration maxRateLimitWait;

    RetryEvents(final RateLimitEventListener listener, final Duration maxRateLimitWait) {
        this.listener = listener;
        this.maxRateLimitWait = maxRateLimitWait;
    }

    String describeGiveUp(final RateLimitEvent.Type type, final Duration wait) {
        return switch (type) {
            case GIVE_UP_WAIT_TOO_LONG ->
                "it asks for a wait of " + millis(wait) + " ms, more than the " + millis(maxRateLimitWait)
                        + " ms allowed";
            case GIVE_UP_ATTEMPTS_EXHAUSTED ->
                "the rate-limit attempts are used up (it asks for " + millis(wait) + " ms)";
            default -> "waiting " + millis(wait) + " ms would end after the total deadline";
        };
    }

    void publish(
            final RateLimitEvent.Type type,
            final HttpRequest request,
            final int statusCode,
            final Duration wait,
            final RetryAttempts attempts,
            final @Nullable String bucket) {
        final RateLimitEvent event = new RateLimitEvent(
                type,
                request.method(),
                request.url().describeSafe(),
                statusCode,
                wait,
                attempts.rateLimitAttempt(),
                bucket);
        try {
            listener.onRateLimitEvent(event);
        } catch (final RuntimeException failure) {
            LOG.atWarn()
                    .setMessage("Rate-limit event listener failed for {}: {}")
                    .addArgument(type)
                    .addArgument(failure.toString())
                    .log();
        }
    }

    void logGate(
            final HttpRequest request, final String outcome, final RateLimitGate.Block block, final Duration wait) {
        final HttpMethod method = request.method();
        LOG.atDebug()
                .setMessage("{} {} {} because of a rate limit on {} that lasts another {} ms")
                .addArgument(method)
                .addArgument(request.url().describeSafe())
                .addArgument(outcome)
                .addArgument(() -> block.bucket() == null ? "all buckets" : "bucket " + block.bucket())
                .addArgument(() -> millis(wait))
                .log();
    }

    void logRetry(
            final HttpRequest request,
            final boolean isRateLimited,
            final String outcome,
            final int finishedAttempts,
            final Duration delay) {
        final LoggingEventBuilder event = isRateLimited ? LOG.atInfo() : LOG.atDebug();
        event.setMessage("{} {} gave {}; retrying in {} ms after attempt {}")
                .addArgument(request.method())
                .addArgument(request.url().describeSafe())
                .addArgument(outcome)
                .addArgument(millis(delay))
                .addArgument(finishedAttempts)
                .log();
    }

    /** Milliseconds of {@code duration}, capped at {@link Long#MAX_VALUE} for a server-supplied wait that overflows. */
    private static long millis(final Duration duration) {
        try {
            return duration.toMillis();
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
