package pl.piotrjanczukowicz.test.http;

import static pl.piotrjanczukowicz.util.ValidationUtils.isNonPositive;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.transport.RateLimitDetector;
import pl.piotrjanczukowicz.http.transport.RateLimitSignal;

public final class RateLimitDetectors {

    public static final String RESET_HEADER = "X-Test-Reset";
    public static final String BUCKET_HEADER = "X-Test-Bucket";
    public static final String SPENT_HEADER = "X-Test-Spent";

    private RateLimitDetectors() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static RateLimitDetector resetHeader() {
        return (response, clock) -> {
            if (!response.hasStatus(HttpStatus.TOO_MANY_REQUESTS)) {
                return Optional.empty();
            }
            return Optional.of(new RateLimitSignal(
                    response.retryAfter(clock).orElse(null),
                    resetAt(response.header(RESET_HEADER).orElse(null))));
        };
    }

    public static RateLimitDetector bucketed() {
        return new RateLimitDetector() {
            @Override
            public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
                if (!response.hasStatus(HttpStatus.TOO_MANY_REQUESTS)) {
                    return Optional.empty();
                }
                final Duration retryAfter = response.retryAfter(clock)
                        .filter(delay -> !isNonPositive(delay))
                        .orElse(null);
                return Optional.of(new RateLimitSignal(
                        retryAfter,
                        resetAt(response.header(RESET_HEADER).orElse(null)),
                        response.header(BUCKET_HEADER).orElse(null)));
            }

            @Override
            public String bucketOf(final HttpRequest request) {
                final String path = request.url().encodedPath();
                final int end = path.indexOf('/', 1);
                return path.substring(1, end < 0 ? path.length() : end);
            }
        };
    }

    public static RateLimitDetector spentQuota() {
        final RateLimitDetector rejections = bucketed();
        return new RateLimitDetector() {
            @Override
            public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
                return rejections.detect(response, clock);
            }

            @Override
            public Optional<RateLimitSignal> detectExhaustion(
                    final HttpResponse response, final HttpRequest request, final Clock clock) {
                if (response.statusCode() >= 400
                        || response.header(SPENT_HEADER).isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new RateLimitSignal(
                        null,
                        resetAt(response.header(RESET_HEADER).orElse(null)),
                        response.header(BUCKET_HEADER).orElse(null)));
            }

            @Override
            public String bucketOf(final HttpRequest request) {
                return rejections.bucketOf(request);
            }
        };
    }

    private static @Nullable Instant resetAt(final @Nullable String epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(Long.parseLong(epochSeconds));
    }
}
