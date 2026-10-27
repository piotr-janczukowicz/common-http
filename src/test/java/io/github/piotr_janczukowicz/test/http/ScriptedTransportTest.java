package io.github.piotr_janczukowicz.test.http;

import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

@UnitTest
class ScriptedTransportTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void execute_shouldReplyInOrderThenDefault_whenRepliesRunOut() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.replying(response(201, null), response(202, null));

        final int first = scripted.transport().execute(request("/a")).statusCode();
        final int second = scripted.transport().execute(request("/b")).statusCode();
        final int third = scripted.transport().execute(request("/c")).statusCode();

        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(201);
            softly.assertThat(second).isEqualTo(202);
            softly.assertThat(third).isEqualTo(200);
        });
    }

    @Test
    void execute_shouldUseConfiguredDefault_whenRepliesRunOut() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.replying().withDefault(response(418, null));

        assertThat(scripted.transport().execute(request("/a")).statusCode()).isEqualTo(418);
    }

    @Test
    void execute_shouldAnswerFromFunction_whenResponding() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.responding(
                request -> response(request.url().encodedPath().endsWith("/a") ? 204 : 404, null));

        final int forA = call(scripted, "/a");
        final int forB = call(scripted, "/b");

        assertSoftly(softly -> {
            softly.assertThat(forA).isEqualTo(204);
            softly.assertThat(forB).isEqualTo(404);
        });
    }

    @Test
    void stream_shouldRecordRequestAndBufferReply_whenStreaming() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.replying(response(200, "body"));

        try (HttpStreamResponse stream = scripted.transport().stream(request("/s"))) {
            assertThat(stream.statusCode()).isEqualTo(200);
        }

        assertThat(scripted.calls()).isEqualTo(1);
    }

    @Test
    void requests_shouldRecordCallsInOrder_whenCalled() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.replying();
        scripted.transport().execute(request("/a"));
        scripted.transport().execute(request("/b"));

        assertSoftly(softly -> {
            softly.assertThat(scripted.calls()).isEqualTo(2);
            softly.assertThat(scripted.request(1).url().encodedPath()).isEqualTo("/b");
            softly.assertThat(scripted.requests()).hasSize(2);
            softly.assertThat(scripted.startNanos()).hasSize(2);
            softly.assertThat(scripted.startedAt()).hasSize(2);
        });
    }

    @Test
    void request_shouldThrow_whenIndexOutOfRange() {
        final ScriptedTransport scripted = ScriptedTransport.replying();

        final Throwable thrown = catchThrowable(() -> scripted.request(0));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startedAt_shouldReadConfiguredClock_whenClockSet() throws Exception {
        final Instant start = Instant.parse("2026-10-14T10:00:00Z");
        final MutableClock clock = new MutableClock(start);
        final ScriptedTransport scripted = ScriptedTransport.replying().withClock(clock);
        scripted.transport().execute(request("/a"));
        clock.advance(Duration.ofSeconds(3));
        scripted.transport().execute(request("/b"));

        assertThat(scripted.startedAt()).containsExactly(start, start.plusSeconds(3));
    }

    @Test
    void execute_shouldWait_whenDelayConfigured() throws Exception {
        final ScriptedTransport scripted = ScriptedTransport.replying().withDelay(Duration.ofMillis(60));
        final long before = System.nanoTime();

        scripted.transport().execute(request("/a"));

        assertThat(Duration.ofNanos(System.nanoTime() - before)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void execute_shouldBlockUntilGateOpens_whenCallGated() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        final ScriptedTransport scripted = ScriptedTransport.replying().gatedFromCall(2, gate);
        scripted.transport().execute(request("/first"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<HttpResponse> second =
                    executor.submit(() -> scripted.transport().execute(request("/second")));

            final boolean started = scripted.awaitCall(2, WAIT);
            final boolean doneBeforeOpen = second.isDone();
            gate.countDown();
            second.get();

            assertSoftly(softly -> {
                softly.assertThat(started).isTrue();
                softly.assertThat(doneBeforeOpen).isFalse();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awaitCall_shouldReturnFalse_whenCallNeverStarts() throws Exception {
        assertThat(ScriptedTransport.replying().awaitCall(1, Duration.ofMillis(20)))
                .isFalse();
    }

    @Test
    void close_shouldMarkClosed_whenTransportClosed() {
        final ScriptedTransport scripted = ScriptedTransport.replying();

        scripted.transport().close();

        assertThat(scripted.isClosed()).isTrue();
    }

    private static int call(final ScriptedTransport scripted, final String path) throws Exception {
        return scripted.transport().execute(request(path)).statusCode();
    }

    private static HttpRequest request(final String path) {
        return new HttpRequest(
                HttpMethod.GET, URI.create("https://api.example.test" + path), HttpHeaders.empty(), null);
    }
}
