package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ETAG;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaderNames;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;
import pl.piotrjanczukowicz.test.http.HttpResponses;
import pl.piotrjanczukowicz.test.http.HttpTransportMocks;
import pl.piotrjanczukowicz.test.http.RateLimitDetectors;
import pl.piotrjanczukowicz.test.http.TestRetryingTransports;
import pl.piotrjanczukowicz.testsupport.MutableClock;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class RetryingHttpTransportRateLimitTest {

    private static final Instant START = Instant.parse("2026-10-09T12:00:00Z");
    private static final int TOO_MANY = HttpStatus.TOO_MANY_REQUESTS.code();

    private HttpTransport delegate;

    private final MutableClock clock = new MutableClock(START);
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<RateLimitEvent> events = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final Deque<Object> script = new ArrayDeque<>();

    @BeforeEach
    void setUpDelegate() {
        delegate = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldWaitSixtyOneTwentyTwoForty_whenSecondaryLimitRepeats() throws Exception {
        script.add(limited());
        script.add(limited());
        script.add(limited());
        script.add(ok());

        final HttpResponse response = execute(config(), get("/user"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240));
    }

    @Test
    void execute_shouldReportWaitsThenGiveUp_whenAttemptsAreExhausted() throws Exception {
        script.add(limited());

        final HttpResponse response = execute(config(), get("/user"));

        assertThat(response.statusCode()).isEqualTo(TOO_MANY);
        assertThat(events)
                .extracting(RateLimitEvent::type, RateLimitEvent::attempt)
                .containsExactly(
                        tuple(RateLimitEvent.Type.WAIT, 1),
                        tuple(RateLimitEvent.Type.WAIT, 2),
                        tuple(RateLimitEvent.Type.WAIT, 3),
                        tuple(RateLimitEvent.Type.GIVE_UP_ATTEMPTS_EXHAUSTED, 4));
        verify(delegate, times(4)).execute(any());
    }

    @Test
    void execute_shouldKeepIoAndRateLimitBudgetsApart_whenIoFailsTwiceThenRateLimited() throws Exception {
        script.add(new IOException("reset"));
        script.add(new IOException("reset"));
        script.add(limited());
        script.add(ok());

        final HttpResponse response = execute(config(), get("/user"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sleeps).last().isEqualTo(Duration.ofSeconds(60));
        verify(delegate, times(4)).execute(any());
    }

    @Test
    void execute_shouldReportDescribedEvent_whenRateLimitIsWaitedOut() throws Exception {
        script.add(limited(RETRY_AFTER, "30", RateLimitDetectors.BUCKET_HEADER, "search"));
        script.add(ok());

        execute(config(), get("/search/issues"));

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertSoftly(softly -> {
                    softly.assertThat(event.type()).isEqualTo(RateLimitEvent.Type.WAIT);
                    softly.assertThat(event.method()).isEqualTo(HttpMethod.GET);
                    softly.assertThat(event.target())
                            .contains("api.example.test")
                            .contains("/search/issues");
                    softly.assertThat(event.statusCode()).isEqualTo(TOO_MANY);
                    softly.assertThat(event.delay()).isEqualTo(Duration.ofSeconds(30));
                    softly.assertThat(event.attempt()).isEqualTo(1);
                    softly.assertThat(event.bucket()).isEqualTo("search");
                }));
    }

    @Test
    void execute_shouldReportDeadlineGiveUp_whenWaitWouldPassTotalDeadline() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));

        final HttpResponse response =
                execute(config(builder -> builder.totalDeadline(Duration.ofSeconds(10))), get("/user"));

        assertThat(response.statusCode()).isEqualTo(TOO_MANY);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RateLimitEvent.Type.GIVE_UP_DEADLINE);
            assertThat(event.delay()).isEqualTo(Duration.ofSeconds(30));
        });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void execute_shouldReportTooLong_whenRequestedWaitExceedsCap() throws Exception {
        script.add(limited(RETRY_AFTER, "3600"));

        final HttpResponse response = execute(config(), get("/user"));

        assertThat(response.statusCode()).isEqualTo(TOO_MANY);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG);
            assertThat(event.delay()).isEqualTo(Duration.ofHours(1));
        });
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldNotFail_whenEventListenerThrows() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        script.add(ok());
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitDetector(RateLimitDetectors.bucketed())
                .rateLimitJitter(Duration.ZERO)
                .rateLimitEventListener(event -> {
                    throw new IllegalStateException("listener broke");
                })
                .build();

        final HttpResponse response = execute(configuration, get("/user"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void execute_shouldSleepUntilBlockEnds_whenAnotherCallWasRateLimited() throws Exception {
        script.add(limited(RETRY_AFTER, "60"));
        script.add(limited(RETRY_AFTER, "60"));
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(2));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            final HttpResponse first = transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertThat(first.statusCode()).isEqualTo(TOO_MANY);
            assertThat(second.statusCode()).isEqualTo(200);
        }

        assertThat(log).containsExactly("call", "sleep 60", "call", "sleep 60", "call");
    }

    @Test
    void execute_shouldGiveWaitersDifferentSleeps_whenReleasedFromTheSameBlock() throws Exception {
        script.add(limited(RETRY_AFTER, "60"));
        script.add(limited(RETRY_AFTER, "60"));
        script.add(ok());
        script.add(ok());
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitDetector(RateLimitDetectors.bucketed())
                .maxRateLimitAttempts(2)
                .rateLimitJitter(Duration.ofSeconds(10))
                .build();
        final RandomGenerator random = new FixedRandom(
                0L, Duration.ofSeconds(2).toNanos(), Duration.ofSeconds(7).toNanos());

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate,
                configuration,
                duration -> {
                    log.add("sleep");
                    sleeps.add(duration);
                },
                clock,
                random)) {
            wire();
            transport.execute(get("/user"));
            transport.execute(get("/user"));
            transport.execute(get("/user"));
        }

        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(62), Duration.ofSeconds(67));
    }

    @Test
    void execute_shouldSendImmediately_whenCoordinationIsOff() throws Exception {
        script.add(limited());
        script.add(ok());
        final RetryConfiguration configuration =
                config(builder -> builder.maxRateLimitAttempts(1).coordinateRateLimits(false));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(TestRetryingTransports.gateSize(transport)).isZero();
        }

        assertThat(log).containsExactly("call", "call");
    }

    @Test
    void execute_shouldFailFastWithMarker_whenBlockOutlastsMaxRateLimitWait() throws Exception {
        script.add(limited(RETRY_AFTER, "3600", DATE, "Fri, 09 Oct 2026 12:00:00 GMT"));
        final RetryConfiguration configuration = config();

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            clock.advance(Duration.ofSeconds(100));
            final HttpResponse blocked = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(blocked.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(blocked.header(RETRY_AFTER)).contains("3500");
                softly.assertThat(blocked.header(DATE)).isEmpty();
            });
        }

        verify(delegate, times(1)).execute(any());
        assertThat(events)
                .extracting(RateLimitEvent::type)
                .containsExactly(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG, RateLimitEvent.Type.GATE_FAIL_FAST);
    }

    @Test
    void execute_shouldFailFast_whenBlockOutlastsTotalDeadline() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration =
                config(builder -> builder.totalDeadline(Duration.ofSeconds(10)).maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            final HttpResponse blocked = transport.execute(get("/user"));

            assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)).contains(RateLimitGateHeaders.BLOCKED);
        }

        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldFailFast_whenRateLimitRetriesAreOff() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.retryRateLimitedResponses(false));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            final HttpResponse first = transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertThat(first.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)).isEmpty();
            assertThat(second.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)).contains(RateLimitGateHeaders.BLOCKED);
        }

        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldOnlyBlockItsBucket_whenLimitNamesBucket() throws Exception {
        script.add(limited(RETRY_AFTER, "3600", RateLimitDetectors.BUCKET_HEADER, "search"));
        script.add(ok());

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            transport.execute(get("/search/issues"));
            final HttpResponse other = transport.execute(get("/user"));
            final HttpResponse sameBucket = transport.execute(get("/search/code"));

            assertThat(other.statusCode()).isEqualTo(200);
            assertThat(sameBucket.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                    .contains(RateLimitGateHeaders.BLOCKED);
        }

        verify(delegate, times(2)).execute(any());
        assertThat(events)
                .filteredOn(event -> event.type() == RateLimitEvent.Type.GATE_FAIL_FAST)
                .singleElement()
                .satisfies(event -> assertThat(event.bucket()).isEqualTo("search"));
    }

    @Test
    void execute_shouldNotBlockOtherCredentialOrOrigin_whenSecondaryLimitIsGlobal() throws Exception {
        script.add(limited());
        script.add(ok());
        script.add(ok());
        final HttpRequest tokenA = get("https://api.example.test/user", "Bearer token-a");
        final HttpRequest tokenB = get("https://api.example.test/user", "Bearer token-b");
        final HttpRequest otherOrigin = get("https://other.example.test/svc/v1/user", "Bearer token-a");
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(tokenA);
            final HttpResponse second = transport.execute(tokenB);
            final HttpResponse third = transport.execute(otherOrigin);

            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(third.statusCode()).isEqualTo(200);
        }

        assertThat(sleeps).isEmpty();
        verify(delegate, times(3)).execute(any());
    }

    @Test
    void execute_shouldSendRequest_whenTheLimitHasEnded() throws Exception {
        script.add(limited());
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            final int whileBlocked = TestRetryingTransports.gateSize(transport);
            clock.advance(Duration.ofSeconds(61));
            final HttpResponse response = transport.execute(get("/user"));

            assertThat(whileBlocked).isEqualTo(1);
            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        }
    }

    @Test
    void stream_shouldReturnBufferedRejection_whenLimitIsInForce() throws Exception {
        script.add(limited(RETRY_AFTER, "3600"));

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            transport.execute(get("/user"));
            try (final HttpStreamResponse blocked = transport.stream(get("/user"))) {
                assertSoftly(softly -> {
                    softly.assertThat(blocked.isStreaming()).isFalse();
                    softly.assertThat(blocked.statusCode()).isEqualTo(TOO_MANY);
                    softly.assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                            .contains(RateLimitGateHeaders.BLOCKED);
                });
            }
        }

        verify(delegate, times(1)).execute(any());
    }

    @Test
    void stream_shouldWaitForBlockThenSend_whenLimitEndsSoon() throws Exception {
        script.add(limited(RETRY_AFTER, "60"));
        script.add(limited(RETRY_AFTER, "60"));
        script.add(HttpStreamResponse.streaming(200, HttpHeaders.empty(), InputStream.nullInputStream()));
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(2));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            try (final HttpStreamResponse response = transport.stream(get("/user"))) {
                assertThat(response.isStreaming()).isTrue();
            }
        }

        assertThat(log).containsExactly("call", "sleep 60", "call", "sleep 60", "stream");
    }

    @Test
    void execute_shouldSendOnce_whenThreadsRaceForABlockedCredential() throws Exception {
        script.add(limited(RETRY_AFTER, "60"));
        script.add(limited(RETRY_AFTER, "60"));
        script.add(ok());
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(2));
        final boolean[] racing = {false};
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch sleeping = new CountDownLatch(2);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate,
                configuration,
                duration -> {
                    if (racing[0]) {
                        sleeping.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    }
                    clock.advance(duration);
                },
                clock)) {
            wire();
            transport.execute(get("/user"));
            racing[0] = true;
            final List<Future<HttpResponse>> waiters = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                waiters.add(executor.submit(() -> transport.execute(get("/user"))));
            }
            assertThat(sleeping.await(10, TimeUnit.SECONDS)).isTrue();
            final int callsWhileBlocked;
            synchronized (log) {
                callsWhileBlocked = (int) log.stream().filter("call"::equals).count();
            }
            release.countDown();
            for (final Future<HttpResponse> waiter : waiters) {
                waiter.get(10, TimeUnit.SECONDS);
            }

            assertThat(callsWhileBlocked).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void execute_shouldWaitAgain_whenAnotherCallExtendedTheBlockDuringTheWait() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited(RETRY_AFTER, "100"));
        script.add(limited(RETRY_AFTER, "100"));
        script.add(limited(RETRY_AFTER, "100"));
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(3));
        final RetryingHttpTransport[] holder = new RetryingHttpTransport[1];
        final HttpRequest request = get("/user");
        final boolean[] extending = {false};
        final boolean[] nested = {false};

        wire();
        try (final RetryingHttpTransport transport = TestRetryingTransports.create(
                delegate,
                configuration,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                    if (extending[0] && !nested[0]) {
                        nested[0] = true;
                        try {
                            holder[0].execute(request);
                        } catch (final IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }
                },
                clock,
                new FixedRandom())) {
            holder[0] = transport;
            transport.execute(request);
            extending[0] = true;
            nested[0] = false;
            final int callsBefore = callsOf(delegate);
            final HttpResponse response = transport.execute(request);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(callsOf(delegate) - callsBefore).isEqualTo(4);
        }

        assertThat(events)
                .filteredOn(event -> event.type() == RateLimitEvent.Type.GATE_WAIT)
                .extracting(RateLimitEvent::delay)
                .containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(100));
    }

    @Test
    void execute_shouldFailOnceAndSendAgainAfterAnHour_whenRetryAfterIsAbsurd() throws Exception {
        script.add(limited(RETRY_AFTER, "999999999"));
        script.add(ok());

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            final HttpResponse first = transport.execute(get("/user"));
            final HttpResponse whileBlocked = transport.execute(get("/user"));
            clock.advance(Duration.ofHours(1).plusSeconds(1));
            final HttpResponse afterwards = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(first.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .isEmpty();
                softly.assertThat(whileBlocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(afterwards.statusCode()).isEqualTo(200);
                softly.assertThat(events)
                        .extracting(RateLimitEvent::type)
                        .containsExactly(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG, RateLimitEvent.Type.GATE_FAIL_FAST);
            });
        }

        verify(delegate, times(2)).execute(any());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void execute_shouldFailFast_whenEveryGateWaitEndsWithTheBlockExtended() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(3));
        final RetryingHttpTransport[] holder = new RetryingHttpTransport[1];
        final HttpRequest request = get("/user");
        final List<Duration> gateSleeps = new ArrayList<>();
        final boolean[] extending = {false};
        final int[] depth = {0};

        wire();
        try (final RetryingHttpTransport transport = TestRetryingTransports.create(
                delegate,
                configuration,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                    if (extending[0] && depth[0] == 0) {
                        gateSleeps.add(duration);
                        depth[0]++;
                        try {
                            holder[0].execute(request);
                        } catch (final IOException failure) {
                            throw new UncheckedIOException(failure);
                        } finally {
                            depth[0]--;
                        }
                    }
                },
                clock,
                new FixedRandom())) {
            holder[0] = transport;
            transport.execute(request);
            final int callsBeforeTheWaiter = callsOf(delegate);
            extending[0] = true;

            final HttpResponse response = transport.execute(request);

            assertSoftly(softly -> {
                softly.assertThat(gateSleeps).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(30));
                softly.assertThat(response.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(response.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(events)
                        .extracting(RateLimitEvent::type)
                        .filteredOn(type -> type.name().startsWith("GATE_"))
                        .containsExactly(
                                RateLimitEvent.Type.GATE_WAIT,
                                RateLimitEvent.Type.GATE_WAIT,
                                RateLimitEvent.Type.GATE_FAIL_FAST);
                softly.assertThat(callsOf(delegate) - callsBeforeTheWaiter).isEqualTo(6);
            });
        }
    }

    @Test
    void execute_shouldReturnRateLimitAtOnce_whenItsStatusIsAlsoAServerStatusAndRetriesAreOff() throws Exception {
        script.add(limited());

        final HttpResponse response = execute(
                config(builder ->
                        builder.retryableServerStatuses(Set.of(TOO_MANY)).retryRateLimitedResponses(false)),
                get("/user"));

        assertThat(response.statusCode()).isEqualTo(TOO_MANY);
        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldRetryAsRateLimit_whenItsStatusIsAlsoAServerStatus() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.retryableServerStatuses(Set.of(TOO_MANY)));

        final HttpResponse response = execute(configuration, get("/user"));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(TOO_MANY);
            softly.assertThat(events)
                    .extracting(RateLimitEvent::type)
                    .containsExactly(
                            RateLimitEvent.Type.WAIT,
                            RateLimitEvent.Type.WAIT,
                            RateLimitEvent.Type.WAIT,
                            RateLimitEvent.Type.GIVE_UP_ATTEMPTS_EXHAUSTED);
            softly.assertThat(sleeps)
                    .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240));
        });
        verify(delegate, times(4)).execute(any());
    }

    @Test
    void execute_shouldReturnServerError_whenItsWaitWouldPassTotalDeadline() throws Exception {
        script.add(new HttpResponse(503, HttpHeaders.of(RETRY_AFTER, "30"), HttpBody.absent()));

        final HttpResponse response =
                execute(config(builder -> builder.totalDeadline(Duration.ofSeconds(10))), get("/user"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldWaitOutRateLimit_whenCoordinationIsOff() throws Exception {
        script.add(limited());
        script.add(ok());

        final HttpResponse response = execute(config(builder -> builder.coordinateRateLimits(false)), get("/user"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void execute_shouldSpendOneBudgetOnGatePauseAndOwnWait_whenBothHappenInOneCall() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(3));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            events.clear();
            final int callsBefore = callsOf(delegate);

            final HttpResponse response = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(response.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(events)
                        .extracting(RateLimitEvent::type)
                        .containsExactly(
                                RateLimitEvent.Type.GATE_WAIT,
                                RateLimitEvent.Type.WAIT,
                                RateLimitEvent.Type.GIVE_UP_ATTEMPTS_EXHAUSTED);
                softly.assertThat(callsOf(delegate) - callsBefore).isEqualTo(2);
            });
        }
    }

    @Test
    void execute_shouldFailFastAtGate_whenTwoOwnWaitsUsedTheBudgetAndOneSecondIsLeft() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited(RETRY_AFTER, "1"));
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(3));
        final RetryingHttpTransport[] holder = new RetryingHttpTransport[1];
        final HttpRequest request = get("/user");
        final int[] waitsSeen = {0};
        final boolean[] nested = {false};

        wire();
        try (final RetryingHttpTransport transport = TestRetryingTransports.create(
                delegate,
                configuration,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                    waitsSeen[0]++;
                    if (waitsSeen[0] == 2 && !nested[0]) {
                        nested[0] = true;
                        try {
                            holder[0].execute(request);
                        } catch (final IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }
                },
                clock,
                new FixedRandom())) {
            holder[0] = transport;

            final HttpResponse response = transport.execute(request);

            assertSoftly(softly -> {
                softly.assertThat(response.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(response.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(response.header(RETRY_AFTER)).contains("1");
                softly.assertThat(events)
                        .filteredOn(event -> event.type() == RateLimitEvent.Type.GATE_FAIL_FAST)
                        .singleElement()
                        .satisfies(event -> assertThat(event.delay()).isEqualTo(Duration.ofSeconds(1)));
                softly.assertThat(callsOf(delegate)).isEqualTo(5);
            });
        }
    }

    @Test
    void execute_shouldNeverWaitForRateLimits_whenBudgetIsOne() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            final HttpResponse rejected = transport.execute(get("/user"));
            final HttpResponse blocked = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(rejected.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(sleeps).isEmpty();
                softly.assertThat(events)
                        .extracting(RateLimitEvent::type)
                        .containsExactly(
                                RateLimitEvent.Type.GIVE_UP_ATTEMPTS_EXHAUSTED, RateLimitEvent.Type.GATE_FAIL_FAST);
            });
        }
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldLogWaitAtInfo_whenRateLimitIsWaitedOut() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        script.add(ok());

        try (final LogCaptor logs = LogCaptor.forClass(RetryEvents.class)) {
            execute(config(), get("/user?access=hidden"));

            assertThat(logs.getInfoLogs())
                    .singleElement()
                    .asString()
                    .contains("GET")
                    .contains("api.example.test/user")
                    .contains("HTTP 429")
                    .contains("retrying in 30000 ms after attempt 1")
                    .doesNotContain("hidden");
        }
    }

    @Test
    void execute_shouldLogGiveUpAtInfo_whenWaitIsTooLong() throws Exception {
        script.add(limited(RETRY_AFTER, "3600"));

        try (final LogCaptor logs = LogCaptor.forClass(RetryingHttpTransport.class)) {
            execute(config(), get("/user"));

            assertThat(logs.getInfoLogs())
                    .singleElement()
                    .asString()
                    .contains("gave HTTP 429")
                    .contains("not retrying after rate-limit attempt 1")
                    .contains("asks for a wait of 3600000 ms, more than the 300000 ms allowed");
        }
    }

    @Test
    void execute_shouldReturnResponse_whenRetryAfterOverflowsMilliseconds() throws Exception {
        script.add(limited(RETRY_AFTER, String.valueOf(Long.MAX_VALUE)));

        try (final LogCaptor logs = LogCaptor.forClass(RetryingHttpTransport.class)) {
            final HttpResponse response = execute(config(), get("/user"));

            assertThat(response.statusCode()).isEqualTo(TOO_MANY);
            assertThat(logs.getInfoLogs())
                    .singleElement()
                    .asString()
                    .contains("gave HTTP 429")
                    .contains(String.valueOf(Long.MAX_VALUE));
        }
    }

    @Test
    void execute_shouldLogGiveUpAtInfo_whenAttemptsAreExhausted() throws Exception {
        script.add(limited(RETRY_AFTER, "5"));

        try (final LogCaptor logs = LogCaptor.forClass(RetryingHttpTransport.class)) {
            execute(config(), get("/user"));

            assertThat(logs.getInfoLogs())
                    .last()
                    .asString()
                    .contains("not retrying after rate-limit attempt 4")
                    .contains("the rate-limit attempts are used up (it asks for 5000 ms)");
        }
    }

    @Test
    void execute_shouldLogGateWaitAndFailFastAtDebug_whenLimitIsInForce() throws Exception {
        script.add(limited(RETRY_AFTER, "60"));
        script.add(limited(RETRY_AFTER, "60"));
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(2));

        try (final LogCaptor logs = LogCaptor.forClass(RetryEvents.class);
                final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            logs.setLogLevelToDebug();
            transport.execute(get("/user"));
            transport.execute(get("/user"));

            assertThat(logs.getDebugLogs())
                    .singleElement()
                    .asString()
                    .contains("waits because of a rate limit on all buckets that lasts another 60000 ms");
        }
    }

    @Test
    void execute_shouldLogFailFastAtDebug_whenBlockIsTooLong() throws Exception {
        script.add(limited(RETRY_AFTER, "3600", RateLimitDetectors.BUCKET_HEADER, "search"));

        try (final LogCaptor logs = LogCaptor.forClass(RetryEvents.class);
                final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            logs.setLogLevelToDebug();
            transport.execute(get("/search/issues"));
            transport.execute(get("/search/issues"));

            assertThat(logs.getDebugLogs())
                    .singleElement()
                    .asString()
                    .contains("is not sent because of a rate limit on bucket search that lasts another 3600000 ms");
        }
    }

    @Test
    void execute_shouldNeverLogCredentialsOrQuery_whenRateLimitsAreHandled() throws Exception {
        script.add(limited());
        script.add(limited(RETRY_AFTER, "3600"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET,
                URI.create("https://api.example.test/user?client_secret=s3cr3t"),
                HttpHeaders.of(AUTHORIZATION, "Bearer tok_super_secret_token"),
                null);

        try (final LogCaptor logs = LogCaptor.forClass(RetryingHttpTransport.class);
                final RetryingHttpTransport transport =
                        transport(config(builder -> builder.maxRateLimitAttempts(2)), new FixedRandom())) {
            logs.setLogLevelToDebug();
            transport.execute(request);
            transport.execute(request);

            assertThat(logs.getLogs())
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line)
                            .doesNotContain("tok_super_secret_token")
                            .doesNotContain("Bearer")
                            .doesNotContain("s3cr3t")
                            .doesNotContain(AUTHORIZATION));
        }
    }

    @Test
    void execute_shouldLogListenerFailureAtWarn_whenListenerThrows() throws Exception {
        script.add(limited(RETRY_AFTER, "30"));
        script.add(ok());
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitDetector(RateLimitDetectors.bucketed())
                .rateLimitJitter(Duration.ZERO)
                .rateLimitEventListener(event -> {
                    throw new IllegalStateException("listener broke");
                })
                .build();

        try (final LogCaptor logs = LogCaptor.forClass(RetryEvents.class)) {
            execute(configuration, get("/user"));

            assertThat(logs.getWarnLogs())
                    .singleElement()
                    .asString()
                    .contains("Rate-limit event listener failed for WAIT")
                    .contains("listener broke");
        }
    }

    @Test
    void execute_shouldHoldBucketUntilReset_whenSuccessReportsSpentQuota() throws Exception {
        script.add(spent(RateLimitDetectors.SPENT_HEADER, "1", RateLimitDetectors.RESET_HEADER, resetIn(120)));
        script.add(ok());

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            final HttpResponse first = transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(200);
                softly.assertThat(first.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .isEmpty();
                softly.assertThat(second.statusCode()).isEqualTo(200);
                softly.assertThat(sleeps).containsExactly(Duration.ofSeconds(121));
            });
        }

        verify(delegate, times(2)).execute(any());
    }

    @Test
    void execute_shouldReplayRejectionWithoutBodyHeaders_whenSpentQuotaOutlastsMaxRateLimitWait() throws Exception {
        script.add(spent(
                RateLimitDetectors.SPENT_HEADER,
                "1",
                RateLimitDetectors.RESET_HEADER,
                resetIn(3600),
                ETAG,
                "\"abc\"",
                CONTENT_LENGTH,
                "10"));

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            final HttpResponse first = transport.execute(get("/user"));
            final HttpResponse blocked = transport.execute(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(200);
                softly.assertThat(blocked.statusCode()).isEqualTo(TOO_MANY);
                softly.assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(blocked.header(RateLimitDetectors.RESET_HEADER))
                        .contains(resetIn(3600));
                softly.assertThat(blocked.header(RETRY_AFTER)).isPresent();
                softly.assertThat(blocked.header(ETAG)).isEmpty();
                softly.assertThat(blocked.header(CONTENT_LENGTH)).isEmpty();
            });
        }

        verify(delegate, times(1)).execute(any());
    }

    @Test
    void stream_shouldHoldBucketUntilReset_whenStreamedSuccessReportsSpentQuota() throws Exception {
        script.add(HttpStreamResponse.streaming(
                HttpStatus.OK.code(),
                HttpHeaders.of(RateLimitDetectors.SPENT_HEADER, "1", RateLimitDetectors.RESET_HEADER, resetIn(120)),
                InputStream.nullInputStream()));
        script.add(ok());

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            final HttpStreamResponse first = transport.stream(get("/user"));
            final HttpStreamResponse second = transport.stream(get("/user"));

            assertSoftly(softly -> {
                softly.assertThat(first.isStreaming()).isTrue();
                softly.assertThat(second.statusCode()).isEqualTo(200);
                softly.assertThat(sleeps).containsExactly(Duration.ofSeconds(121));
            });
            first.close();
            second.close();
        }
    }

    @Test
    void execute_shouldNotHoldBucket_whenCoordinationIsOff() throws Exception {
        script.add(spent(RateLimitDetectors.SPENT_HEADER, "1", RateLimitDetectors.RESET_HEADER, resetIn(120)));
        script.add(ok());
        final RetryConfiguration configuration = config(builder -> builder.coordinateRateLimits(false));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(sleeps).isEmpty();
            assertThat(TestRetryingTransports.gateSize(transport)).isZero();
        }
    }

    @Test
    void execute_shouldNotHoldBucket_whenSpentQuotaNamesNoReset() throws Exception {
        script.add(spent(RateLimitDetectors.SPENT_HEADER, "1"));
        script.add(ok());

        try (final RetryingHttpTransport transport = transport(config(), new FixedRandom())) {
            transport.execute(get("/user"));
            final HttpResponse second = transport.execute(get("/user"));

            assertThat(second.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)).isEmpty();
            assertThat(sleeps).isEmpty();
            assertThat(TestRetryingTransports.gateSize(transport)).isZero();
        }
    }

    @Test
    void execute_shouldGrowUnnamedWaitsUpToCap_whenSeparateCallsAreLimited() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration =
                config(builder -> builder.maxRateLimitAttempts(1).maxRateLimitWait(Duration.ofMinutes(3)));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            for (int call = 0; call < 4; call++) {
                transport.execute(get("/user"));
                clock.advance(events.getLast().delay());
            }
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(
                        Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofMinutes(3), Duration.ofMinutes(3));
    }

    @Test
    void execute_shouldStartAtBaseWait_whenQuietPeriodHasPassed() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            for (int call = 0; call < 3; call++) {
                transport.execute(get("/user"));
                clock.advance(events.getLast().delay());
            }
            clock.advance(Duration.ofMinutes(5));
            transport.execute(get("/user"));
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120),
                        Duration.ofSeconds(240),
                        Duration.ofSeconds(60));
    }

    @Test
    void execute_shouldKeepEscalating_whenQuietPeriodHasNotPassed() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            clock.advance(events.getLast().delay());
            transport.execute(get("/user"));
            clock.advance(events.getLast().delay().plusSeconds(299));
            transport.execute(get("/user"));
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240));
    }

    @Test
    void execute_shouldKeepUnnamedLevel_whenAnotherLimitNamesItsWait() throws Exception {
        script.add(limited());
        script.add(limited(RETRY_AFTER, "30"));
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            clock.advance(Duration.ofSeconds(60));
            transport.execute(get("/user"));
            clock.advance(Duration.ofSeconds(30));
            transport.execute(get("/user"));
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }

    @Test
    void execute_shouldStartAtBaseWaitForAnotherCredential_whenOneCredentialEscalated() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("https://api.example.test/user", "Bearer token-a"));
            clock.advance(Duration.ofSeconds(60));
            transport.execute(get("https://api.example.test/user", "Bearer token-a"));
            clock.advance(Duration.ofSeconds(120));
            transport.execute(get("https://api.example.test/user", "Bearer token-b"));
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(60));
    }

    @Test
    void execute_shouldKeepOneUnnamedWaitPerCredentialIdentity_whenTokenRotates() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.maxRateLimitAttempts(1));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(identified("token-a", "app:1/installation:42"));
            clock.advance(Duration.ofSeconds(60));
            transport.execute(identified("token-b", "app:1/installation:42"));
            clock.advance(Duration.ofSeconds(120));
            transport.execute(identified("token-c", "app:1/installation:43"));
        }

        assertThat(events)
                .extracting(RateLimitEvent::delay)
                .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(60));
    }

    private static HttpRequest identified(final String token, final String credentialId) {
        return new HttpRequest(
                HttpMethod.GET,
                ParsedUrl.of(URI.create("https://api.example.test/user")),
                HttpHeaders.of(AUTHORIZATION, "Bearer " + token),
                null,
                credentialId);
    }

    private static int callsOf(final HttpTransport transport) {
        return Mockito.mockingDetails(transport).getInvocations().stream()
                .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
                .toList()
                .size();
    }

    private HttpResponse execute(final RetryConfiguration configuration, final HttpRequest request) throws IOException {
        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            return transport.execute(request);
        }
    }

    private RetryingHttpTransport transport(final RetryConfiguration configuration, final RandomGenerator random)
            throws IOException {
        wire();
        return TestRetryingTransports.create(
                delegate,
                configuration,
                duration -> {
                    log.add("sleep " + duration.toSeconds());
                    sleeps.add(duration);
                    clock.advance(duration);
                },
                clock,
                random);
    }

    private void wire() throws IOException {
        lenient().when(delegate.execute(any())).thenAnswer(invocation -> next("call"));
        lenient().when(delegate.stream(any())).thenAnswer(invocation -> {
            final Object next = next("stream");
            return next instanceof final HttpResponse response ? HttpStreamResponse.buffered(response) : next;
        });
    }

    private Object next(final String kind) throws IOException {
        synchronized (log) {
            log.add(kind);
            final Object entry = script.size() > 1 ? script.poll() : script.peek();
            if (entry instanceof final IOException failure) {
                throw failure;
            }
            return entry;
        }
    }

    private RetryConfiguration config() {
        return config(builder -> builder);
    }

    private RetryConfiguration config(final UnaryOperator<RetryConfiguration.Builder> customize) {
        return customize
                .apply(RetryConfiguration.builder()
                        .rateLimitDetector(RateLimitDetectors.spentQuota())
                        .rateLimitJitter(Duration.ZERO)
                        .rateLimitEventListener(events::add))
                .build();
    }

    private static HttpResponse limited(final String... headers) {
        return responseWith(TOO_MANY, headers);
    }

    private static HttpResponse spent(final String... headers) {
        return responseWith(HttpStatus.OK.code(), headers);
    }

    private static HttpResponse responseWith(final int status, final String... headers) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            map.put(headers[i], List.of(headers[i + 1]));
        }
        return HttpResponses.of(status, map, HttpBody.absent());
    }

    private static String resetIn(final long seconds) {
        return Long.toString(START.plusSeconds(seconds).getEpochSecond());
    }

    private static HttpResponse ok() {
        return new HttpResponse(200, HttpHeaders.empty(), HttpBody.absent());
    }

    private static HttpRequest get(final String path) {
        return new HttpRequest(
                HttpMethod.GET,
                URI.create(path.startsWith("http") ? path : "https://api.example.test" + path),
                HttpHeaders.empty(),
                null);
    }

    private static HttpRequest get(final String uri, final String authorization) {
        return new HttpRequest(HttpMethod.GET, URI.create(uri), HttpHeaders.of(AUTHORIZATION, authorization), null);
    }

    private static final class FixedRandom implements RandomGenerator {
        private final Deque<Long> draws = new ArrayDeque<>();

        FixedRandom(final long... values) {
            for (final long value : values) {
                draws.add(value);
            }
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public long nextLong(final long bound) {
            final Long draw = draws.poll();
            return draw == null ? 0L : draw;
        }
    }

    @Test
    void execute_shouldFailFast_whenGateWaitWouldPassTotalDeadlineWithBudgetLeft() throws Exception {
        script.add(limited());
        final RetryConfiguration configuration = config(builder -> builder.totalDeadline(Duration.ofSeconds(10)));

        try (final RetryingHttpTransport transport = transport(configuration, new FixedRandom())) {
            transport.execute(get("/user"));
            final HttpResponse blocked = transport.execute(get("/user"));

            assertThat(blocked.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)).contains(RateLimitGateHeaders.BLOCKED);
        }
    }
}
