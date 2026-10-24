package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.test.http.HttpResponses;
import pl.piotrjanczukowicz.test.http.RateLimitDetectors;
import pl.piotrjanczukowicz.testsupport.UnitTest;
import pl.piotrjanczukowicz.util.DateTimeUtils;

@UnitTest
class RetryPolicyTest {

    private static final URI API_URI = URI.create("https://api.example.test/user");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final HttpRequest GET = new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);
    private static final HttpRequest POST = new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), null);

    private static final IOException IO_FAILURE = new IOException("connection lost");

    private static HttpRequest request(final HttpMethod method) {
        return new HttpRequest(method, API_URI, HttpHeaders.empty(), null);
    }

    @Test
    void rateLimitDelay_shouldBePresent_whenAttemptsRemain() {
        final OptionalDelay delay =
                delay(new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null), 1);

        assertThat(delay.present()).isTrue();
    }

    @Test
    void rateLimitDelay_shouldBeEmpty_whenAttemptsExhausted() {
        final OptionalDelay delay =
                delay(new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null), 4);

        assertThat(delay.present()).isFalse();
    }

    @Test
    void rateLimitDelay_shouldBeEmpty_whenRetryDisabled() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder().retryRateLimitedResponses(false).build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 1)).isEmpty();
    }

    @Test
    void rateLimitDelay_shouldBeEmpty_whenResponseNotRateLimited() {
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy(RetryConfiguration.defaults()), ok, 1)).isEmpty();
    }

    @Test
    void afterIoFailure_shouldBePresent_whenGetRequest() {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(GET, IO_FAILURE, 1))
                .isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"PUT", "DELETE", "PATCH"})
    void afterIoFailure_shouldBeEmpty_whenNonIdempotentByDefault(final HttpMethod method) {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(request(method), IO_FAILURE, 1))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"PUT", "DELETE"})
    void afterIoFailure_shouldBePresent_whenMethodOptedIn(final HttpMethod method) {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .ioRetryableMethods(Set.of(HttpMethod.GET, method))
                .build());

        assertThat(policy.afterIoFailure(request(method), IO_FAILURE, 1)).isPresent();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenPostRequest() {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(POST, IO_FAILURE, 1))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void afterIoFailure_shouldBePresent_whenConnectionRefusedForAnyMethod(final HttpMethod method) {
        assertThat(policy(RetryConfiguration.defaults())
                        .afterIoFailure(request(method), new ConnectException("refused"), 1))
                .isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PATCH", "PUT", "DELETE"})
    void afterIoFailure_shouldBePresent_whenNoRouteToHostForWriteMethod(final HttpMethod method) {
        assertThat(policy(RetryConfiguration.defaults())
                        .afterIoFailure(request(method), new NoRouteToHostException("no route"), 1))
                .isPresent();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenWriteMethodTimesOut() {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(POST, new SocketTimeoutException("timeout"), 1))
                .isEmpty();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenConnectFailureAndAttemptsExhausted() {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(POST, new ConnectException("refused"), 3))
                .isEmpty();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenConnectFailureAndRetryDisabled() {
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().retryIoExceptions(false).build());

        assertThat(policy.afterIoFailure(POST, new ConnectException("refused"), 1))
                .isEmpty();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenAttemptsExhausted() {
        assertThat(policy(RetryConfiguration.defaults()).afterIoFailure(GET, IO_FAILURE, 3))
                .isEmpty();
    }

    @Test
    void afterIoFailure_shouldBeEmpty_whenRetryDisabled() {
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().retryIoExceptions(false).build());

        assertThat(policy.afterIoFailure(GET, IO_FAILURE, 1)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("neverRetriedFailures")
    void afterIoFailure_shouldBeEmpty_whenFailureIsNeverRetried(final IOException failure) {
        final RetryPolicy policy = policy(RetryConfiguration.defaults());

        final Optional<Duration> wait = policy.afterIoFailure(GET, failure, 1);

        assertThat(wait).isEmpty();
    }

    static Stream<IOException> neverRetriedFailures() {
        return Stream.of(
                new SSLHandshakeException("PKIX path building failed"),
                new SSLPeerUnverifiedException("hostname mismatch"),
                new SSLKeyException("bad key"),
                new IOException("Canceled"),
                new ResponseTooLargeException(1024),
                new NonRetryableIOException("request body source failed"),
                new FileNotFoundException("missing.bin"),
                new UnknownHostException("api.example.test"),
                new InterruptedIOException("timeout"));
    }

    @ParameterizedTest
    @MethodSource("transientIoFailures")
    void afterIoFailure_shouldBePresent_whenFailureMayBeTransient(final IOException failure) {
        final RetryPolicy policy = policy(RetryConfiguration.defaults());

        final Optional<Duration> wait = policy.afterIoFailure(GET, failure, 1);

        assertThat(wait).isPresent();
    }

    static Stream<IOException> transientIoFailures() {
        return Stream.of(
                new SSLException("Connection reset"),
                new IOException("Canceled by someone else"),
                new SocketTimeoutException("read timed out"));
    }

    @Test
    void rateLimitDelay_shouldUseRetryAfter_whenHeaderPresent() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .fullJitter(false)
                        .rateLimitJitter(Duration.ZERO)
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(5));
    }

    @Test
    void rateLimitDelay_shouldDoubleMinWait_whenSecondaryLimitRepeats() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(60));
            softly.assertThat(rateLimitDelay(policy, limited, 2)).contains(Duration.ofSeconds(120));
            softly.assertThat(rateLimitDelay(policy, limited, 3)).contains(Duration.ofSeconds(240));
            softly.assertThat(rateLimitDelay(policy, limited, 4)).isEmpty();
        });
    }

    @Test
    void rateLimitDelay_shouldClampDoublingToMaxRateLimitWait_whenItWouldExceedIt() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .minRateLimitWait(Duration.ofSeconds(120))
                .maxRateLimitWait(Duration.ofMinutes(5))
                .maxRateLimitAttempts(5)
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(120));
            softly.assertThat(rateLimitDelay(policy, limited, 2)).contains(Duration.ofSeconds(240));
            softly.assertThat(rateLimitDelay(policy, limited, 3)).contains(Duration.ofSeconds(300));
            softly.assertThat(rateLimitDelay(policy, limited, 4)).contains(Duration.ofSeconds(300));
        });
    }

    @Test
    void rateLimitDelay_shouldClampToMaxRateLimitWait_whenBackoffDoublingExceedsIt() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .minRateLimitWait(Duration.ofSeconds(4_000_000_000L))
                .maxRateLimitWait(Duration.ofSeconds(4_000_000_000L))
                .maxRateLimitAttempts(20)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 18)).contains(Duration.ofSeconds(4_000_000_000L));
    }

    @Test
    void rateLimitDelay_shouldAddJitterOnTopOfMaxRateLimitWait_whenWaitEqualsCap() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .minRateLimitWait(Duration.ofSeconds(60))
                        .maxRateLimitWait(Duration.ofSeconds(60))
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .build(),
                returning(500_000_000L));
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofMillis(60_500));
    }

    @Test
    void rateLimitDelay_shouldIgnoreIoBackoff_whenSecondaryLimitHasNoHeaders() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(60));
    }

    @Test
    void rateLimitDelay_shouldNotGiveUp_whenSecondaryBackoffExceedsMaxRateLimitWait() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .minRateLimitWait(Duration.ofSeconds(5))
                .maxRateLimitWait(Duration.ofSeconds(8))
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(5));
            softly.assertThat(rateLimitDelay(policy, limited, 2)).contains(Duration.ofSeconds(8));
        });
    }

    @Test
    void rateLimitDelay_shouldWaitMinRateLimitWait_whenSecondaryLimitAndMaxBackoffIsBelowIt() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(15))
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void rateLimitDelay_shouldUseMinRateLimitWait_whenRetryAfterNonPositive(final String retryAfter) {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .fullJitter(false)
                        .rateLimitJitter(Duration.ZERO)
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(60));
    }

    @Test
    void rateLimitDelay_shouldIgnoreFullJitter_whenRetryAfterPresent() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .fullJitter(true)
                        .rateLimitJitter(Duration.ZERO)
                        .build(),
                FIXED_CLOCK,
                returning(Duration.ofMillis(40).toNanos()));
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(5));
    }

    @Test
    void rateLimitDelay_shouldUseResetAt_whenResetSignalled() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .fullJitter(false)
                        .rateLimitJitter(Duration.ZERO)
                        .clockSkewMargin(Duration.ZERO)
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RateLimitDetectors.RESET_HEADER, List.of("120")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(120));
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, 30})
    void rateLimitDelay_shouldUseDateHeader_whenResetPresent(final int localClockSkewSeconds) {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final Instant resetAt = date.plusSeconds(10);
        final Clock localClock = Clock.fixed(date.plusSeconds(localClockSkewSeconds), ZoneOffset.UTC);
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .clockSkewMargin(Duration.ofSeconds(1))
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                localClock,
                returning(0L));

        assertThat(rateLimitDelay(policy, resetSignal(date, resetAt), 1)).contains(Duration.ofSeconds(11));
    }

    @Test
    void rateLimitDelay_shouldStayBelowMarginPlusJitter_whenResetPresent() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .clockSkewMargin(Duration.ofSeconds(1))
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                Clock.fixed(date.plusSeconds(30), ZoneOffset.UTC),
                returningMax());

        final Duration delay = rateLimitDelay(policy, resetSignal(date, date.plusSeconds(10)), 1)
                .orElseThrow();

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ofSeconds(11)).isLessThan(Duration.ofSeconds(12));
    }

    @Test
    void rateLimitDelay_shouldUseMarginOnly_whenResetIsInThePast() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .clockSkewMargin(Duration.ofSeconds(1))
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                Clock.fixed(date, ZoneOffset.UTC),
                returning(0L));

        assertThat(rateLimitDelay(policy, resetSignal(date, date.minusSeconds(5)), 1))
                .contains(Duration.ofSeconds(1));
    }

    @Test
    void rateLimitDelay_shouldUseClock_whenDateHeaderUnparseable() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .clockSkewMargin(Duration.ofSeconds(1))
                        .rateLimitJitter(Duration.ZERO)
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(),
                Map.of(DATE, List.of("not-a-date"), RateLimitDetectors.RESET_HEADER, List.of("10")),
                null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(11));
    }

    @Test
    void rateLimitDelay_shouldUseClock_whenDateHeaderAbsent() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .clockSkewMargin(Duration.ofSeconds(1))
                        .rateLimitJitter(Duration.ZERO)
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RateLimitDetectors.RESET_HEADER, List.of("10")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(11));
    }

    @Test
    void rateLimitDelay_shouldAddRateLimitJitter_whenRetryAfterPresent() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .build(),
                FIXED_CLOCK,
                returning(0L));
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(5));
    }

    @Test
    void rateLimitDelay_shouldStayBelowRetryAfterPlusJitter_whenRandomIsMax() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .build(),
                FIXED_CLOCK,
                returningMax());
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);

        final Duration delay = rateLimitDelay(policy, limited, 1).orElseThrow();

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ofSeconds(5)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void rateLimitDelay_shouldUseMinRateLimitWait_whenTooManyRequestsHaveNoHeadersAndJitterZeroDraw() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitJitter(Duration.ofSeconds(1))
                        .maxRateLimitWait(Duration.ofMinutes(2))
                        .build(),
                returning(0L));
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(60));
    }

    @Test
    void rateLimitDelay_shouldMatchLegacyWait_whenRateLimitJitterAndMarginZero() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.resetHeader())
                        .rateLimitJitter(Duration.ZERO)
                        .clockSkewMargin(Duration.ZERO)
                        .maxRateLimitWait(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK);
        final HttpResponse retryAfter =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);
        final HttpResponse reset = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RateLimitDetectors.RESET_HEADER, List.of("120")), null);

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, retryAfter, 1)).contains(Duration.ofSeconds(5));
            softly.assertThat(rateLimitDelay(policy, reset, 1)).contains(Duration.ofSeconds(120));
        });
    }

    @Test
    void rateLimitDelay_shouldRetry_whenJitterWouldExceedMaxWait() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .maxRateLimitWait(Duration.ofSeconds(60))
                        .rateLimitJitter(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK,
                returningMax());
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("60")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).isPresent();
    }

    @Test
    void rateLimitDelay_shouldBeEmpty_whenBaseWaitExceedsMaxWait() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .maxRateLimitWait(Duration.ofSeconds(60))
                        .rateLimitJitter(Duration.ofHours(1))
                        .build(),
                FIXED_CLOCK,
                returning(0L));
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("61")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).isEmpty();
    }

    @Test
    void serverErrorDelay_shouldBePresent_whenGetServiceUnavailable() {
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);

        assertThat(policy(RetryConfiguration.defaults()).serverErrorDelay(GET, unavailable, 1))
                .isPresent();
    }

    @Test
    void serverErrorDelay_shouldBeEmpty_whenPost() {
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);

        assertThat(policy(RetryConfiguration.defaults()).serverErrorDelay(POST, unavailable, 1))
                .isEmpty();
    }

    @Test
    void serverErrorDelay_shouldBePresent_whenPostIsAllowListed() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .ioRetryableMethods(Set.of(HttpMethod.POST))
                .fullJitter(false)
                .build());
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);

        assertThat(policy.serverErrorDelay(POST, unavailable, 1)).isPresent();
        assertThat(policy.afterIoFailure(POST, IO_FAILURE, 1)).isPresent();
    }

    @Test
    void serverErrorDelay_shouldBeEmpty_whenRetryAfterExceedsMaxServerErrorWait() {
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("3600")), null);

        assertThat(policy(RetryConfiguration.defaults()).serverErrorDelay(GET, unavailable, 1))
                .isEmpty();
    }

    @Test
    void serverErrorDelay_shouldIgnoreMaxRateLimitWait_whenServerWaitCapAllows() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .minRateLimitWait(Duration.ZERO)
                        .maxRateLimitWait(Duration.ofSeconds(1))
                        .maxServerErrorWait(Duration.ofSeconds(10))
                        .fullJitter(false)
                        .build(),
                FIXED_CLOCK);
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("5")), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(5));
    }

    @Test
    void serverErrorDelay_shouldUseRetryAfter_whenHeaderPresent() {
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().fullJitter(false).build());
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("2")), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(2));
    }

    @Test
    void serverErrorDelay_shouldUseBackoff_whenRetryAfterMalformed() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .fullJitter(false)
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(60))
                .build());
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("soon")), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-9223372036854775808", "-1", "9223372036854775807"})
    void serverErrorDelay_shouldNotThrow_whenRetryAfterIsExtreme(final String retryAfter) {
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        final var policy = policy(RetryConfiguration.defaults());

        final Throwable thrown = catchThrowable(() -> policy.serverErrorDelay(GET, unavailable, 1));

        assertThat(thrown).isNull();
    }

    @Test
    void serverErrorDelay_shouldWaitZero_whenRetryAfterIsNegative() {
        final HttpResponse unavailable = HttpResponses.of(
                HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("-9223372036854775808")), null);

        assertThat(policy(RetryConfiguration.defaults()).serverErrorDelay(GET, unavailable, 1))
                .contains(Duration.ZERO);
    }

    @Test
    void serverErrorDelay_shouldBeEmpty_whenRetryAfterIsLongMax() {
        final HttpResponse unavailable = HttpResponses.of(
                HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("9223372036854775807")), null);

        assertThat(policy(RetryConfiguration.defaults()).serverErrorDelay(GET, unavailable, 1))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-9223372036854775808", "9223372036854775807"})
    void rateLimitDelay_shouldNotThrow_whenRetryAfterIsExtreme(final String retryAfter) {
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        final var policy = policy(RetryConfiguration.defaults());

        final Throwable thrown = catchThrowable(() -> rateLimitDelay(policy, limited, 1));

        assertThat(thrown).isNull();
    }

    @Test
    void backoffForAttempt_shouldCapAtMaxBackoff_whenDoublingExceedsIt() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(4_000_000_000L))
                .maxBackoff(Duration.ofNanos(Long.MAX_VALUE))
                .build());

        assertThat(policy.backoffForAttempt(17)).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    void beyondDeadline_shouldBeFalse_whenNoDeadline() {
        final RetryPolicy policy = policy(RetryConfiguration.defaults(), FIXED_CLOCK);

        assertThat(policy.deadline()).isNull();
        assertThat(policy.isBeyondDeadline(null, Duration.ofDays(365))).isFalse();
    }

    @Test
    void beyondDeadline_shouldCompareDelayWithRemainingTime_whenDeadlineSet() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .totalDeadline(Duration.ofSeconds(10))
                        .build(),
                FIXED_CLOCK);
        final Instant deadline = policy.deadline();

        assertSoftly(softly -> {
            softly.assertThat(deadline).isEqualTo(Instant.EPOCH.plusSeconds(10));
            softly.assertThat(policy.isBeyondDeadline(deadline, Duration.ofSeconds(10)))
                    .isFalse();
            softly.assertThat(policy.isBeyondDeadline(deadline, Duration.ofSeconds(11)))
                    .isTrue();
        });
    }

    @Test
    void deadline_shouldBeNull_whenDeadlineOverflowsInstant() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .totalDeadline(Duration.ofSeconds(Long.MAX_VALUE))
                        .build(),
                FIXED_CLOCK);

        assertThat(policy.deadline()).isNull();
    }

    @Test
    void serverErrorDelay_shouldUseRetryAfter_whenHeaderIsRfc1123Date() {
        final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        final String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                clock.instant().plusSeconds(5).atOffset(ZoneOffset.UTC));
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().fullJitter(false).build(), clock);
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(5));
    }

    @Test
    void afterIoFailure_shouldDrawInsideBackoff_whenFullJitter() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .fullJitter(true)
                        .build(),
                returning(Duration.ofMillis(25).toNanos()));

        assertThat(policy.afterIoFailure(GET, IO_FAILURE, 1)).contains(Duration.ofMillis(25));
    }

    @Test
    void afterIoFailure_shouldReturnZero_whenRandomDrawsZero() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .fullJitter(true)
                        .build(),
                returning(0L));

        assertThat(policy.afterIoFailure(GET, IO_FAILURE, 1)).contains(Duration.ZERO);
    }

    @Test
    void afterIoFailure_shouldStayUnderCappedBackoff_whenRandomReturnsUpperBound() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(1))
                        .maxIoAttempts(4)
                        .fullJitter(true)
                        .build(),
                returningMax());

        assertThat(policy.afterIoFailure(GET, IO_FAILURE, 3))
                .contains(Duration.ofSeconds(1).minusNanos(1));
    }

    @Test
    void afterIoFailure_shouldStayUnderBackoff_whenUsingDefaultRandom() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(true)
                .build());

        final Duration delay = policy.afterIoFailure(GET, IO_FAILURE, 1).orElseThrow();

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void serverErrorDelay_shouldDrawFullJitter_whenRetryAfterAbsent() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(60))
                        .fullJitter(true)
                        .build(),
                returning(Duration.ofMillis(25).toNanos()));
        final HttpResponse unavailable = HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofMillis(25));
    }

    @Test
    void afterIoFailure_shouldMatchBackoff_whenFullJitterDisabled() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .fullJitter(false)
                        .build(),
                returning(999L));

        assertThat(policy.afterIoFailure(GET, IO_FAILURE, 2)).contains(Duration.ofSeconds(2));
    }

    @Test
    void backoffForAttempt_shouldGrowExponentially_whenAttemptIncreases() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(60))
                .build());

        assertSoftly(softly -> {
            softly.assertThat(policy.backoffForAttempt(1)).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(policy.backoffForAttempt(2)).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(policy.backoffForAttempt(3)).isEqualTo(Duration.ofSeconds(4));
        });
    }

    @Test
    void backoffForAttempt_shouldCapShift_whenAttemptExceedsSixteen() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofDays(1))
                .build());

        assertThat(policy.backoffForAttempt(20)).isEqualTo(Duration.ofSeconds(65536));
    }

    @Test
    void rateLimit_shouldBeEmpty_whenResponseIsOk() {
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        assertThat(policy(RetryConfiguration.defaults()).rateLimit(request(HttpMethod.GET), ok, 1, 0))
                .isEmpty();
    }

    @Test
    void constructor_shouldThrow_whenClockIsNull() {
        final var config = RetryConfiguration.defaults();

        final Throwable thrown = catchThrowable(() -> new RetryPolicy(config, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    @Test
    void constructor_shouldThrow_whenRandomIsNull() {
        final var config = RetryConfiguration.defaults();
        final var clock = Clock.systemUTC();

        final Throwable thrown = catchThrowable(() -> new RetryPolicy(config, clock, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("random");
    }

    @Test
    void exhaustion_shouldReturnDetectorSignal_whenDetectorReportsSpentQuota() {
        final Instant reset = Instant.parse("2026-01-01T00:02:00Z");
        final RateLimitSignal signal = new RateLimitSignal(null, reset, "bucket-a");
        final RetryPolicy policy = policy(exhaustionConfiguration(signal));
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        final Optional<RateLimitSignal> found = policy.exhaustion(request(HttpMethod.GET), ok);

        assertThat(found).contains(signal);
    }

    @Test
    void exhaustion_shouldBeEmpty_whenDetectorReportsNoSpentQuota() {
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);

        final Optional<RateLimitSignal> found =
                policy(RetryConfiguration.defaults()).exhaustion(request(HttpMethod.GET), ok);

        assertThat(found).isEmpty();
    }

    @Test
    void untilReset_shouldAddClockSkewMargin_whenTimeComesFromDateHeader() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(RetryConfiguration.defaults(), Clock.fixed(date, ZoneOffset.UTC));

        final Duration wait = policy.untilReset(date.plusSeconds(120), dated(date));

        assertThat(wait).isEqualTo(Duration.ofSeconds(121));
    }

    private static RetryConfiguration exhaustionConfiguration(final RateLimitSignal signal) {
        return RetryConfiguration.builder()
                .rateLimitDetector(new RateLimitDetector() {
                    @Override
                    public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<RateLimitSignal> detectExhaustion(
                            final HttpResponse response, final HttpRequest request, final Clock clock) {
                        return Optional.of(signal);
                    }
                })
                .build();
    }

    private static Optional<Duration> rateLimitDelay(
            final RetryPolicy policy, final HttpResponse response, final int attempt) {
        return policy.rateLimit(request(HttpMethod.GET), response, attempt, attempt - 1)
                .filter(verdict -> verdict.outcome() == RetryPolicy.RateLimitVerdict.Outcome.RETRY)
                .map(RetryPolicy.RateLimitVerdict::delay);
    }

    private static OptionalDelay delay(final HttpResponse response, final int attempt) {
        return new OptionalDelay(rateLimitDelay(policy(RetryConfiguration.defaults()), response, attempt)
                .isPresent());
    }

    private static RetryPolicy policy(final RetryConfiguration configuration) {
        return new RetryPolicy(configuration);
    }

    @Test
    void rateLimitDelay_shouldBeEmpty_whenRetryAfterIsHuge() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("9223372036854775807")), null);

        assertThat(rateLimitDelay(policy(RetryConfiguration.defaults()), limited, 1))
                .isEmpty();
    }

    @Test
    void rateLimitDelay_shouldUseMinRateLimitWait_whenRetryAfterIsFractional() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofSeconds(1))
                        .minRateLimitWait(Duration.ofSeconds(7))
                        .fullJitter(false)
                        .rateLimitJitter(Duration.ZERO)
                        .build(),
                FIXED_CLOCK);
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("1.5")), null);

        assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(7));
    }

    @Test
    void serverErrorDelay_shouldUseBackoff_whenRetryAfterIsFractional() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .fullJitter(false)
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(60))
                .build());
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("1.5")), null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(1));
    }

    @Test
    void serverErrorDelay_shouldBeEmpty_whenRetryAfterJustExceedsMaxServerErrorWait() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder()
                        .maxServerErrorWait(Duration.ofSeconds(10))
                        .build(),
                FIXED_CLOCK);
        final HttpResponse atCap =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("10")), null);
        final HttpResponse overCap =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("11")), null);

        assertSoftly(softly -> {
            softly.assertThat(policy.serverErrorDelay(GET, atCap, 1)).contains(Duration.ofSeconds(10));
            softly.assertThat(policy.serverErrorDelay(GET, overCap, 1)).isEmpty();
        });
    }

    @Test
    void rateLimit_shouldWaitForLaterOfRetryAfterAndReset_whenBothPresent() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(
                signalConfiguration(new RateLimitSignal(Duration.ofSeconds(10), date.plusSeconds(100), "bucket-a")),
                Clock.fixed(date.plusSeconds(30), ZoneOffset.UTC));
        final RetryPolicy longerRetryAfter = policy(
                signalConfiguration(new RateLimitSignal(Duration.ofSeconds(200), date.plusSeconds(100), "bucket-a")),
                Clock.fixed(date.plusSeconds(30), ZoneOffset.UTC));

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, dated(date), 1)).contains(Duration.ofSeconds(101));
            softly.assertThat(rateLimitDelay(longerRetryAfter, dated(date), 1)).contains(Duration.ofSeconds(200));
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -5})
    void rateLimit_shouldWaitForReset_whenRetryAfterNonPositiveAndResetPresent(final long retryAfterSeconds) {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(
                signalConfiguration(
                        new RateLimitSignal(Duration.ofSeconds(retryAfterSeconds), date.plusSeconds(100), null)),
                Clock.fixed(date, ZoneOffset.UTC));

        assertThat(rateLimitDelay(policy, dated(date), 1)).contains(Duration.ofSeconds(101));
    }

    @Test
    void rateLimit_shouldGiveUpAsTooLong_whenRequestedWaitExceedsMaxRateLimitWait() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy = policy(
                signalConfiguration(new RateLimitSignal(null, date.plus(Duration.ofMinutes(40)), "search")),
                Clock.fixed(date, ZoneOffset.UTC));

        final RetryPolicy.RateLimitVerdict verdict =
                policy.rateLimit(request(HttpMethod.GET), dated(date), 1, 0).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(verdict.outcome()).isEqualTo(RetryPolicy.RateLimitVerdict.Outcome.WAIT_TOO_LONG);
            softly.assertThat(verdict.required())
                    .isEqualTo(Duration.ofMinutes(40).plusSeconds(1));
            softly.assertThat(verdict.bucket()).isEqualTo("search");
        });
    }

    @Test
    void rateLimit_shouldReportAttemptsExhausted_whenBudgetIsUsedUp() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .maxRateLimitAttempts(2)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        final RetryPolicy.RateLimitVerdict verdict =
                policy.rateLimit(request(HttpMethod.GET), limited, 2, 1).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(verdict.outcome()).isEqualTo(RetryPolicy.RateLimitVerdict.Outcome.ATTEMPTS_EXHAUSTED);
            softly.assertThat(verdict.required()).isEqualTo(Duration.ofSeconds(120));
            softly.assertThat(verdict.bucket()).isNull();
        });
    }

    @Test
    void rateLimit_shouldReportNotRetried_whenRetryDisabled() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder().retryRateLimitedResponses(false).build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertThat(policy.rateLimit(request(HttpMethod.GET), limited, 1, 0))
                .hasValueSatisfying(verdict ->
                        assertThat(verdict.outcome()).isEqualTo(RetryPolicy.RateLimitVerdict.Outcome.NOT_RETRIED));
    }

    @Test
    void rateLimit_shouldWaitTheSameRetryAfterEachTime_whenTheSameLimitRepeats() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder().rateLimitJitter(Duration.ZERO).build());
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("30")), null);

        assertSoftly(softly -> {
            softly.assertThat(rateLimitDelay(policy, limited, 1)).contains(Duration.ofSeconds(30));
            softly.assertThat(rateLimitDelay(policy, limited, 2)).contains(Duration.ofSeconds(30));
            softly.assertThat(rateLimitDelay(policy, limited, 3)).contains(Duration.ofSeconds(30));
        });
    }

    @Test
    void rateLimit_shouldGrowBackoffByResponseAndSpendBudgetByWait_whenGatePausesCameBetween() {
        final RetryPolicy policy = policy(
                RetryConfiguration.builder().rateLimitJitter(Duration.ZERO).build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(policy.rateLimit(request(HttpMethod.GET), limited, 2, 2)
                            .orElseThrow()
                            .required())
                    .isEqualTo(Duration.ofSeconds(120));
            softly.assertThat(policy.rateLimit(request(HttpMethod.GET), limited, 2, 2)
                            .orElseThrow()
                            .outcome())
                    .isEqualTo(RetryPolicy.RateLimitVerdict.Outcome.RETRY);
            softly.assertThat(policy.rateLimit(request(HttpMethod.GET), limited, 2, 3)
                            .orElseThrow()
                            .outcome())
                    .isEqualTo(RetryPolicy.RateLimitVerdict.Outcome.ATTEMPTS_EXHAUSTED);
        });
    }

    @Test
    void afterIoFailure_shouldNotShareBudgetWithRateLimits_whenRateLimitBudgetIsOne() {
        final RetryPolicy ioOnly = policy(RetryConfiguration.builder()
                .maxRateLimitAttempts(1)
                .maxIoAttempts(3)
                .build());
        final RetryPolicy rateLimitOnly = policy(RetryConfiguration.builder()
                .maxIoAttempts(1)
                .maxRateLimitAttempts(4)
                .rateLimitJitter(Duration.ZERO)
                .build());
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(ioOnly.afterIoFailure(GET, IO_FAILURE, 2)).isPresent();
            softly.assertThat(rateLimitDelay(rateLimitOnly, limited, 1)).isPresent();
            softly.assertThat(rateLimitOnly.afterIoFailure(GET, IO_FAILURE, 1)).isEmpty();
        });
    }

    @Test
    void serverErrorDelay_shouldMeasureHttpDateAgainstDateHeader_whenLocalClockIsOff() {
        final Instant serverNow = Instant.parse("2026-01-01T00:00:00Z");
        final Clock localClock = Clock.fixed(serverNow.plusSeconds(120), ZoneOffset.UTC);
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().fullJitter(false).build(), localClock);
        final HttpResponse unavailable = HttpResponses.of(
                HttpStatus.SERVICE_UNAVAILABLE.code(),
                Map.of(
                        DATE,
                        List.of(DateTimeUtils.toRfc1123(serverNow)),
                        RETRY_AFTER,
                        List.of(DateTimeUtils.toRfc1123(serverNow.plusSeconds(30)))),
                null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(30));
    }

    @Test
    void serverErrorDelay_shouldMeasureHttpDateAgainstLocalClock_whenDateHeaderAbsent() {
        final Instant localNow = Instant.parse("2026-01-01T00:00:00Z");
        final RetryPolicy policy =
                policy(RetryConfiguration.builder().fullJitter(false).build(), Clock.fixed(localNow, ZoneOffset.UTC));
        final HttpResponse unavailable = HttpResponses.of(
                HttpStatus.SERVICE_UNAVAILABLE.code(),
                Map.of(RETRY_AFTER, List.of(DateTimeUtils.toRfc1123(localNow.plusSeconds(30)))),
                null);

        assertThat(policy.serverErrorDelay(GET, unavailable, 1)).contains(Duration.ofSeconds(30));
    }

    @Test
    void bucketOf_shouldReturnDetectorBucket_whenDetectorOverridesIt() {
        final RetryPolicy custom = policy(RetryConfiguration.builder()
                .rateLimitDetector(new RateLimitDetector() {
                    @Override
                    public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
                        return Optional.empty();
                    }

                    @Override
                    public String bucketOf(final HttpRequest request) {
                        return "mine";
                    }
                })
                .build());

        assertSoftly(softly -> {
            softly.assertThat(custom.bucketOf(GET)).isEqualTo("mine");
            softly.assertThat(policy(RetryConfiguration.defaults()).bucketOf(GET))
                    .isEqualTo(RateLimitDetector.DEFAULT_BUCKET);
        });
    }

    @Test
    void accessors_shouldExposeConfiguration_whenPolicyIsBuilt() {
        final RetryPolicy policy = policy(RetryConfiguration.builder()
                .maxRateLimitWait(Duration.ofMinutes(2))
                .retryRateLimitedResponses(false)
                .build());

        assertSoftly(softly -> {
            softly.assertThat(policy.maxRateLimitWait()).isEqualTo(Duration.ofMinutes(2));
            softly.assertThat(policy.isRetryingRateLimits()).isFalse();
            softly.assertThat(RetryPolicy.defaultRandom()).isNotNull();
        });
    }

    private static RetryConfiguration signalConfiguration(final RateLimitSignal signal) {
        return RetryConfiguration.builder()
                .rateLimitDetector((response, clock) -> Optional.of(signal))
                .rateLimitJitter(Duration.ZERO)
                .clockSkewMargin(Duration.ofSeconds(1))
                .maxRateLimitWait(Duration.ofMinutes(5))
                .build();
    }

    private static HttpResponse dated(final Instant date) {
        return HttpResponses.of(
                HttpStatus.FORBIDDEN.code(), Map.of(DATE, List.of(DateTimeUtils.toRfc1123(date))), null);
    }

    private static RetryPolicy policy(final RetryConfiguration configuration, final Clock clock) {
        return new RetryPolicy(configuration, clock);
    }

    private static RetryPolicy policy(
            final RetryConfiguration configuration, final Clock clock, final RandomGenerator random) {
        return new RetryPolicy(configuration, clock, random);
    }

    private static RetryPolicy policy(final RetryConfiguration configuration, final RandomGenerator random) {
        return new RetryPolicy(configuration, Clock.systemUTC(), random);
    }

    private static HttpResponse resetSignal(final Instant date, final Instant resetAt) {
        return HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(),
                Map.of(
                        DATE,
                        List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(date.atOffset(ZoneOffset.UTC))),
                        RateLimitDetectors.RESET_HEADER,
                        List.of(Long.toString(resetAt.getEpochSecond()))),
                null);
    }

    private static RandomGenerator returning(final long nanos) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return nanos;
            }

            @Override
            public long nextLong(final long bound) {
                return nanos;
            }
        };
    }

    private static RandomGenerator returningMax() {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return Long.MAX_VALUE;
            }

            @Override
            public long nextLong(final long bound) {
                return bound - 1;
            }
        };
    }

    private record OptionalDelay(boolean present) {}
}
