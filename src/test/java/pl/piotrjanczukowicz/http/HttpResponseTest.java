package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.SET_COOKIE;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;
import pl.piotrjanczukowicz.util.DateTimeUtils;

@UnitTest
class HttpResponseTest {
    private static final String REMAINING_HEADER = "X-Remaining";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void constructor_shouldCopyHeaders_whenHeadersMutated() {
        final Map<String, List<String>> headers = new HashMap<>(Map.of("X-Test", new ArrayList<>(List.of("first"))));

        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), headers, null);
        headers.put("Other", List.of("added"));

        assertThat(response.headers().asMap().keySet()).containsExactly("X-Test");
    }

    @Test
    void constructor_shouldUseEmptyHeaders_whenHeadersNull() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), null, null);

        assertThat(response.headers().isEmpty()).isTrue();
    }

    @Test
    void constructor_shouldNormalizeNullBody_whenBodyNull() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(response.body()).isEqualTo(HttpBody.absent());
            softly.assertThat(response.body().isAbsent()).isTrue();
        });
    }

    @Test
    void constructor_shouldThrow_whenStatusCodeOutOfRange() {
        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> new HttpResponse(99, headers, null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("99");
    }

    @Test
    void fromWire_shouldMapHeaders_whenProvided() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.CREATED.code(), Map.of("X-Test", List.of("value")), HttpBody.of(new byte[] {1, 2}));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.code());
            softly.assertThat(response.header("x-test")).contains("value");
            softly.assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(1, 2);
        });
    }

    @Test
    void header_shouldThrow_whenNameNull() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        final Throwable thrown = catchThrowable(() -> response.header(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void header_shouldReturnTrimmedJoinedValue_whenNameMatchesIgnoreCase() {
        final HttpResponse response =
                HttpResponses.of(HttpStatus.OK.code(), Map.of(REMAINING_HEADER, List.of(" 42 ", "ignored")), null);

        assertThat(response.header("x-remaining")).contains("42 , ignored");
    }

    @ParameterizedTest
    @MethodSource("emptyHeaderCases")
    void header_shouldBeEmpty_whenValueMissingOrBlank(final Map<String, List<String>> headers, final String name) {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), headers, null);

        assertThat(response.header(name)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("hasStatusCases")
    void hasStatus_shouldMatchExpected_whenCompared(
            final HttpStatus responseStatus, final HttpStatus expected, final boolean match) {
        final HttpResponse response = new HttpResponse(responseStatus.code(), HttpHeaders.empty(), null);

        assertThat(response.hasStatus(expected)).isEqualTo(match);
    }

    @Test
    void serverDate_shouldReadDateHeader_whenValidRfc1123() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse response =
                HttpResponses.of(HttpStatus.OK.code(), Map.of(DATE, List.of(DateTimeUtils.toRfc1123(date))), null);

        final Optional<Instant> serverDate = response.serverDate();

        assertThat(serverDate).contains(date);
    }

    @Test
    void serverDate_shouldBeEmpty_whenDateHeaderUnparseable() {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), Map.of(DATE, List.of("not a date")), null);

        final Optional<Instant> serverDate = response.serverDate();

        assertThat(serverDate).isEmpty();
    }

    @Test
    void serverDate_shouldBeEmpty_whenDateHeaderMissing() {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), Map.of("X-Other", List.of("1")), null);

        final Optional<Instant> serverDate = response.serverDate();

        assertThat(serverDate).isEmpty();
    }

    @Test
    void retryAfter_shouldReturnSeconds_whenHeaderIsInteger() {
        final HttpResponse positive = response(RETRY_AFTER, "45");
        final HttpResponse negative = response(RETRY_AFTER, "-5");

        final Optional<Duration> delay = positive.retryAfter(CLOCK);
        final Optional<Duration> negativeDelay = negative.retryAfter(CLOCK);

        assertSoftly(softly -> {
            softly.assertThat(delay).contains(Duration.ofSeconds(45));
            softly.assertThat(negativeDelay).contains(Duration.ofSeconds(-5));
        });
    }

    @Test
    void retryAfter_shouldReturnUntilDate_whenHeaderIsRfc1123() {
        final String value = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                CLOCK.instant().plusSeconds(5).atOffset(ZoneOffset.UTC));
        final HttpResponse response = response(RETRY_AFTER, value);

        final Optional<Duration> delay = response.retryAfter(CLOCK);

        assertThat(delay).contains(Duration.ofSeconds(5));
    }

    @Test
    void retryAfter_shouldMeasureAgainstDateHeader_whenResponseHasDate() {
        final Instant serverNow = Instant.parse("2026-01-01T00:00:00Z");
        final Clock behind = Clock.fixed(serverNow.plusSeconds(120), ZoneOffset.UTC);
        final HttpResponse response = response(
                DATE,
                DateTimeUtils.toRfc1123(serverNow),
                RETRY_AFTER,
                DateTimeUtils.toRfc1123(serverNow.plusSeconds(30)));

        final Optional<Duration> delay = response.retryAfter(behind);

        assertThat(delay).contains(Duration.ofSeconds(30));
    }

    @Test
    void retryAfter_shouldMeasureAgainstClock_whenResponseHasNoDate() {
        final HttpResponse response =
                response(RETRY_AFTER, DateTimeUtils.toRfc1123(CLOCK.instant().plusSeconds(30)));

        final Optional<Duration> delay = response.retryAfter(CLOCK);

        assertThat(delay).contains(Duration.ofSeconds(30));
    }

    @Test
    void retryAfter_shouldMeasureAgainstClock_whenDateHeaderIsGarbage() {
        final HttpResponse response = response(
                DATE,
                "yesterday",
                RETRY_AFTER,
                DateTimeUtils.toRfc1123(CLOCK.instant().plusSeconds(30)));

        final Optional<Duration> delay = response.retryAfter(CLOCK);

        assertThat(delay).contains(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "1.5"})
    void retryAfter_shouldBeEmpty_whenHeaderIsNeitherSecondsNorDate(final String value) {
        final HttpResponse response = response(RETRY_AFTER, value);

        final Optional<Duration> delay = response.retryAfter(CLOCK);

        assertThat(delay).isEmpty();
    }

    @Test
    void retryAfter_shouldBeEmpty_whenHeaderAbsent() {
        final HttpResponse response = response(DATE, DateTimeUtils.toRfc1123(CLOCK.instant()));

        final Optional<Duration> delay = response.retryAfter(CLOCK);

        assertThat(delay).isEmpty();
    }

    @Test
    void retryAfter_shouldThrow_whenClockNull() {
        final HttpResponse response = response(RETRY_AFTER, "1");

        final Throwable thrown = catchThrowable(() -> response.retryAfter(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    private static HttpResponse response(final String... namesAndValues) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
        }
        return HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), headers, null);
    }

    @Test
    void mapBody_shouldMapBytes_whenBodyPresent() {
        final HttpResponse response =
                new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {9}));

        final int length = response.mapBody(bytes -> bytes.length, () -> -1);

        assertThat(length).isEqualTo(1);
    }

    @Test
    void mapBody_shouldUseAbsent_whenBodyMissing() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());

        final int value = response.mapBody(bytes -> bytes.length, () -> 0);

        assertThat(value).isZero();
    }

    @Test
    void hasStatus_shouldReturnTrue_whenStatusIsNotFound() {
        final HttpResponse response =
                new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent());

        assertThat(response.hasStatus(HttpStatus.NOT_FOUND)).isTrue();
    }

    @Test
    void hasStatus_shouldReturnFalse_whenStatusIsNotNotFound() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());

        assertThat(response.hasStatus(HttpStatus.NOT_FOUND)).isFalse();
    }

    @Test
    void isSuccessful_shouldReturnTrue_whenStatusIs2xx() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());

        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    void isSuccessful_shouldReturnTrue_whenStatusIsMultiStatus() {
        final HttpResponse response =
                new HttpResponse(HttpStatus.MULTI_STATUS.code(), HttpHeaders.empty(), HttpBody.absent());

        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    void isSuccessful_shouldReturnFalse_whenStatusIsNot2xx() {
        final HttpResponse response =
                new HttpResponse(HttpStatus.BAD_REQUEST.code(), HttpHeaders.empty(), HttpBody.absent());

        assertThat(response.isSuccessful()).isFalse();
    }

    @Test
    void requireBodyBytes_shouldReturnBytes_whenBodyPresent() {
        final HttpResponse response =
                new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {4, 5}));

        assertThat(response.requireBodyBytes()).containsExactly(4, 5);
    }

    @Test
    void requireBodyBytes_shouldThrow_whenBodyAbsent() {
        final HttpResponse response = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());

        final Throwable thrown = catchThrowable(response::requireBodyBytes);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("HTTP 200 response has no body");
    }

    @Test
    void charset_shouldUseUtf8_whenContentTypeMissing() {
        final HttpResponse response = new HttpResponse(HttpStatus.BAD_REQUEST.code(), HttpHeaders.empty(), null);

        final Charset charset = response.charset();

        assertThat(charset).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void charset_shouldReturnNamedCharset_whenCharsetParameterPresent() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.BAD_REQUEST.code(), Map.of(CONTENT_TYPE, List.of("text/plain; charset=ISO-8859-1")), null);

        final Charset charset = response.charset();

        assertThat(charset).isEqualTo(StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/plain; charset=nonsense", "text/plain; charset=@@"})
    void charset_shouldFallBackToUtf8_whenCharsetAbsentOrInvalid(final String contentType) {
        final HttpResponse response =
                HttpResponses.of(HttpStatus.BAD_REQUEST.code(), Map.of(CONTENT_TYPE, List.of(contentType)), null);

        final Charset charset = response.charset();

        assertThat(charset).isEqualTo(StandardCharsets.UTF_8);
    }

    private static Stream<Arguments> emptyHeaderCases() {
        return Stream.of(
                Arguments.of(Map.of(), "Missing"),
                Arguments.of(Map.of("X-Test", List.of("   ")), "X-Test"),
                Arguments.of(Map.of("X-Test", List.of()), "X-Test"));
    }

    private static Stream<Arguments> hasStatusCases() {
        return Stream.of(
                Arguments.of(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND, true),
                Arguments.of(HttpStatus.OK, HttpStatus.NOT_FOUND, false));
    }

    @Test
    void toString_shouldNotLeakBodyOrSensitiveHeaders_whenPrinted() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.OK.code(),
                Map.of(
                        SET_COOKIE,
                        List.of("session=top-secret-cookie"),
                        "X-Api-Key",
                        List.of("top-secret-key"),
                        "X-Test",
                        List.of("visible")),
                HttpBody.of("top-secret-body".getBytes(StandardCharsets.UTF_8)));

        assertThat(response.toString())
                .doesNotContain("top-secret")
                .contains("X-Test=visible")
                .contains("Set-Cookie=***")
                .contains("length=15");
    }
}
