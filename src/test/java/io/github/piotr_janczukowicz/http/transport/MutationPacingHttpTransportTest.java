package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.util.ConcurrencyUtils.sleepInterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.NonRetryableIOException;
import io.github.piotr_janczukowicz.test.http.ScriptedTransport;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class MutationPacingHttpTransportTest {

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Instant START = Instant.parse("2026-10-14T10:00:00Z");
    private static final HttpRequest POST_REQUEST = request(HttpMethod.POST);
    private static final HttpRequest GET_REQUEST = request(HttpMethod.GET);
    private static final int CONCURRENT_CALLERS = 8;
    private static final int WAIT_SECONDS = 10;

    @Test
    void execute_shouldNotDelayGet_whenGetsFollowEachOther() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, sleeper);

        transport.execute(GET_REQUEST);
        transport.execute(GET_REQUEST);
        transport.execute(GET_REQUEST);

        assertSoftly(softly -> {
            softly.assertThat(sleeper.sleeps).isEmpty();
            softly.assertThat(delegate.calls()).isEqualTo(3);
        });
    }

    @Test
    void execute_shouldSpaceStartsByInterval_whenPostsFollowEachOther() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, sleeper);

        transport.execute(POST_REQUEST);
        transport.execute(POST_REQUEST);

        assertSoftly(softly -> {
            softly.assertThat(delegate.startedAt()).hasSize(2);
            softly.assertThat(Duration.between(
                            delegate.startedAt().getFirst(),
                            delegate.startedAt().get(1)))
                    .isGreaterThanOrEqualTo(INTERVAL);
            softly.assertThat(sleeper.sleeps).containsExactly(INTERVAL);
        });
    }

    @Test
    void execute_shouldSerialiseStartsByInterval_whenMutationsRunConcurrently() throws Exception {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, sleeper);

        runConcurrently(CONCURRENT_CALLERS, () -> transport.execute(POST_REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(delegate.calls()).isEqualTo(CONCURRENT_CALLERS);
            softly.assertThat(sleeper.sleeps).hasSize(CONCURRENT_CALLERS - 1).containsOnly(INTERVAL);
            softly.assertThat(clock.instant()).isEqualTo(START.plus(INTERVAL.multipliedBy(CONCURRENT_CALLERS - 1L)));
        });
    }

    @Test
    void stream_shouldSpaceStartsByInterval_whenPostsStream() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, sleeper);

        transport.stream(POST_REQUEST);
        transport.stream(POST_REQUEST);

        assertThat(sleeper.sleeps).containsExactly(INTERVAL);
    }

    @Test
    void execute_shouldThrowInterruptedIoAndRestoreFlag_whenInterruptedWhileWaiting() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, interruptingSleeper());
        transport.execute(POST_REQUEST);

        final Throwable thrown = catchThrowable(() -> transport.execute(POST_REQUEST));
        final boolean isInterrupted = Thread.interrupted();

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(InterruptedIOException.class);
            softly.assertThat(isInterrupted).isTrue();
            softly.assertThat(delegate.calls()).isEqualTo(1);
        });
    }

    @Test
    void execute_shouldThrowInterruptedIoAndRestoreFlag_whenInterruptedBeforeStarting() throws IOException {
        final ScriptedTransport delegate = ScriptedTransport.replying();
        final MutationPacingHttpTransport transport = new MutationPacingHttpTransport(
                delegate.transport(), INTERVAL, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS);
        transport.execute(POST_REQUEST);
        Thread.currentThread().interrupt();

        final Throwable thrown = catchThrowable(() -> transport.execute(POST_REQUEST));
        final boolean isInterrupted = Thread.interrupted();

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(InterruptedIOException.class);
            softly.assertThat(isInterrupted).isTrue();
            softly.assertThat(delegate.calls()).isEqualTo(1);
        });
    }

    @Test
    void execute_shouldWaitAtMostOneInterval_whenClockMovedBack() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final MutationPacingHttpTransport transport =
                paced(ScriptedTransport.replying().withClock(clock).transport(), clock, sleeper);
        transport.execute(POST_REQUEST);
        clock.advance(Duration.ofHours(-1));

        transport.execute(POST_REQUEST);

        assertThat(sleeper.sleeps).containsExactly(INTERVAL);
    }

    @Test
    void execute_shouldNotWait_whenIntervalHasAlreadyElapsed() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final RecordingSleeper sleeper = new RecordingSleeper(clock);
        final MutationPacingHttpTransport transport =
                paced(ScriptedTransport.replying().withClock(clock).transport(), clock, sleeper);
        transport.execute(POST_REQUEST);
        clock.advance(INTERVAL.plusSeconds(1));

        transport.execute(POST_REQUEST);

        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void execute_shouldWaitInRealTime_whenPublicConstructorUsed() throws IOException {
        final ScriptedTransport delegate = ScriptedTransport.replying();
        final MutationPacingHttpTransport transport = new MutationPacingHttpTransport(
                delegate.transport(), Duration.ofMillis(100), MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS);
        transport.execute(POST_REQUEST);

        transport.execute(POST_REQUEST);

        assertThat(Duration.between(
                        delegate.startedAt().getFirst(), delegate.startedAt().get(1)))
                .isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void close_shouldCloseDelegate_whenCalled() {
        final ScriptedTransport delegate = ScriptedTransport.replying();
        final MutationPacingHttpTransport transport = new MutationPacingHttpTransport(
                delegate.transport(), INTERVAL, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS);

        transport.close();

        assertThat(delegate.isClosed()).isTrue();
    }

    @Test
    void execute_shouldFailWithoutSending_whenTransportClosed() {
        final MutableClock clock = new MutableClock(START);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, new RecordingSleeper(clock));
        transport.close();

        final Throwable thrown = catchThrowable(() -> transport.execute(POST_REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(NonRetryableIOException.class);
            softly.assertThat(delegate.calls()).isZero();
        });
    }

    @Test
    void execute_shouldFailWithoutSending_whenTransportClosedWhileWaiting() throws IOException {
        final MutableClock clock = new MutableClock(START);
        final ScriptedTransport delegate = ScriptedTransport.replying().withClock(clock);
        final AtomicReference<MutationPacingHttpTransport> holder = new AtomicReference<>();
        final MutationPacingHttpTransport transport = paced(delegate.transport(), clock, duration -> {
            clock.advance(duration);
            holder.get().close();
        });
        holder.set(transport);
        transport.execute(POST_REQUEST);

        final Throwable thrown = catchThrowable(() -> transport.execute(POST_REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(NonRetryableIOException.class);
            softly.assertThat(delegate.calls()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void constructor_shouldRejectInterval_whenNotPositive(final long millis) {
        final ScriptedTransport delegate = ScriptedTransport.replying();

        final var transport = delegate.transport();
        final var duration = Duration.ofMillis(millis);

        final Throwable thrown = catchThrowable(() -> new MutationPacingHttpTransport(
                transport, duration, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldRejectMethods_whenEmpty() {
        final ScriptedTransport delegate = ScriptedTransport.replying();

        final var transport = delegate.transport();

        final Set<HttpMethod> methods = Set.of();

        final Throwable thrown = catchThrowable(() -> new MutationPacingHttpTransport(transport, INTERVAL, methods));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldRejectDelegate_whenDelegateIsNull() {
        final Throwable thrown = catchThrowable(() ->
                new MutationPacingHttpTransport(null, INTERVAL, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    private static MutationPacingHttpTransport paced(
            final HttpTransport delegate, final Clock clock, final MutationPacingHttpTransport.Sleeper sleeper) {
        return new MutationPacingHttpTransport(
                delegate, INTERVAL, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS, clock, sleeper);
    }

    private static MutationPacingHttpTransport.Sleeper interruptingSleeper() {
        return duration -> {
            Thread.currentThread().interrupt();
            sleepInterruptibly(duration, "test");
        };
    }

    private static HttpRequest request(final HttpMethod method) {
        return new HttpRequest(
                method, URI.create("https://api.example.test/repos/owner/repo/issues"), HttpHeaders.empty(), null);
    }

    private static void runConcurrently(final int callers, final IoCall call) throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            final List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                tasks.add(executor.submit(() -> {
                    call.run();
                    return null;
                }));
            }
            for (final Future<?> task : tasks) {
                task.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IoCall {
        void run() throws IOException;
    }

    private static final class RecordingSleeper implements MutationPacingHttpTransport.Sleeper {

        private final MutableClock clock;
        private final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

        private RecordingSleeper(final MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(final Duration duration) {
            sleeps.add(duration);
            clock.advance(duration);
        }
    }
}
