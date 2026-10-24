package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.test.http.HttpResponses;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class RateLimitDetectorTest {
    private static final String LIMIT_HEADER = "X-Limit";
    private static final String REMAINING_HEADER = "X-Remaining";

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void defaults_shouldReturnSameInstance_whenCalledRepeatedly() {
        assertThat(RateLimitDetector.defaults()).isSameAs(RateLimitDetector.defaults());
    }

    @Test
    void defaults_shouldReadRetryAfter_whenTooManyRequests() {
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("7")), null);

        assertThat(RateLimitDetector.defaults().detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(Duration.ofSeconds(7), null));
    }

    @Test
    void defaults_shouldSignalWithoutWait_whenTooManyRequestsHaveNoRetryAfter() {
        final HttpResponse limited = HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(), null);

        assertThat(RateLimitDetector.defaults().detect(limited, FIXED_CLOCK)).contains(new RateLimitSignal(null, null));
    }

    @Test
    void defaults_shouldBeEmpty_whenForbiddenWithRetryAfter() {
        final HttpResponse forbidden = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RETRY_AFTER, List.of("7"), LIMIT_HEADER, List.of("5000"), REMAINING_HEADER, List.of("0")),
                null);

        assertThat(RateLimitDetector.defaults().detect(forbidden, FIXED_CLOCK)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "soon"})
    void defaults_shouldTreatRetryAfterAsAbsent_whenItIsNotPositive(final String retryAfter) {
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        assertThat(RateLimitDetector.defaults().detect(limited, FIXED_CLOCK)).contains(new RateLimitSignal(null, null));
    }

    @Test
    void defaults_shouldMeasureHttpDateAgainstDateHeader_whenLocalClockIsOff() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(),
                Map.of(
                        DATE,
                        List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(date.atOffset(ZoneOffset.UTC))),
                        RETRY_AFTER,
                        List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                date.plusSeconds(30).atOffset(ZoneOffset.UTC)))),
                null);

        assertThat(RateLimitDetector.defaults().detect(limited, Clock.fixed(date.plusSeconds(120), ZoneOffset.UTC)))
                .contains(new RateLimitSignal(Duration.ofSeconds(30), null));
    }

    @Test
    void bucketOf_shouldBeTheDefaultBucket_whenDetectorDoesNotOverrideIt() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.GET, URI.create("https://api.example.test/user"), HttpHeaders.empty(), null);

        assertThat(RateLimitDetector.defaults().bucketOf(request)).isEqualTo(RateLimitDetector.DEFAULT_BUCKET);
    }

    @Test
    void detect_shouldIgnoreTheRequest_whenDetectorOnlyOverridesTheTwoArgumentForm() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.GET, URI.create("https://api.example.test/user"), HttpHeaders.empty(), null);
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("7")), null);

        assertThat(RateLimitDetector.defaults().detect(limited, request, FIXED_CLOCK))
                .contains(new RateLimitSignal(Duration.ofSeconds(7), null));
    }

    @Test
    void detectExhaustion_shouldBeEmpty_whenDetectorDoesNotOverrideIt() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.GET, URI.create("https://api.example.test/user"), HttpHeaders.empty(), null);
        final HttpResponse ok = HttpResponses.of(HttpStatus.OK.code(), Map.of("X-Test-Reset", List.of("120")), null);

        assertThat(RateLimitDetector.defaults().detectExhaustion(ok, request, FIXED_CLOCK))
                .isEmpty();
    }
}
