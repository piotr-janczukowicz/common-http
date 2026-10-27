package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.NonRetryableIOException;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.http.ScriptedHttpServer;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@UnitTest
class ConcurrencyLimitingHttpTransportTest {

    private static final HttpRequest REQUEST =
            new HttpRequest(HttpMethod.GET, URI.create("https://api.example.test/user"), HttpHeaders.empty(), null);
    private static final int WAIT_SECONDS = 10;

    @Test
    void execute_shouldRunAtMostLimitRequestsAtOnce_whenManyThreadsCall() throws Exception {
        final AtomicInteger maxConcurrent = new AtomicInteger();
        try (ScriptedHttpServer server = recordingServer(4, maxConcurrent)) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 4);
            final HttpRequest request = server.request();

            runConcurrently(32, () -> transport.execute(request));

            assertThat(maxConcurrent.get()).isEqualTo(4);
        }
    }

    @Test
    void execute_shouldReachFullConcurrency_whenDelegateIsNotLimited() throws Exception {
        final AtomicInteger maxConcurrent = new AtomicInteger();
        try (ScriptedHttpServer server = recordingServer(32, maxConcurrent)) {
            final HttpTransport delegate = HttpTransports.okHttp();
            final HttpRequest request = server.request();

            runConcurrently(32, () -> delegate.execute(request));

            assertThat(maxConcurrent.get()).isEqualTo(32);
        }
    }

    @Test
    void execute_shouldReleaseSlot_whenDelegateThrows() throws Exception {
        final HttpTransport delegate = failingDelegate();
        final ConcurrencyLimitingHttpTransport transport = new ConcurrencyLimitingHttpTransport(delegate, 1);

        final Throwable first = catchThrowable(() -> transport.execute(REQUEST));
        final Throwable second = catchThrowable(() -> transport.execute(REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(first).isInstanceOf(IOException.class).hasMessage("boom");
            softly.assertThat(second).isInstanceOf(IOException.class).hasMessage("boom");
        });
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void stream_shouldReleaseSlot_whenDelegateThrows() throws Exception {
        final HttpTransport delegate = failingDelegate();
        final ConcurrencyLimitingHttpTransport transport = new ConcurrencyLimitingHttpTransport(delegate, 1);

        final Throwable first = catchThrowable(() -> transport.stream(REQUEST));
        final Throwable second = catchThrowable(() -> transport.stream(REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(first).isInstanceOf(IOException.class).hasMessage("boom");
            softly.assertThat(second).isInstanceOf(IOException.class).hasMessage("boom");
        });
        verify(delegate, times(2)).stream(any());
    }

    @Test
    void stream_shouldBlockSecondCall_whenFirstResponseIsNotClosed() throws Exception {
        try (ScriptedHttpServer server = streamingServer()) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 1);
            final HttpRequest request = server.request();
            final ExecutorService executor = Executors.newSingleThreadExecutor();
            try (HttpStreamResponse first = transport.stream(request)) {
                final Future<HttpStreamResponse> second = executor.submit(() -> transport.stream(request));

                final Throwable blocked = catchThrowable(() -> second.get(300, TimeUnit.MILLISECONDS));
                first.close();

                try (HttpStreamResponse opened = second.get(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    assertSoftly(softly -> {
                        softly.assertThat(blocked).isInstanceOf(TimeoutException.class);
                        softly.assertThat(opened.isStreaming()).isTrue();
                    });
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void stream_shouldReleaseSlotOnce_whenResponseClosedTwice() throws Exception {
        try (ScriptedHttpServer server = streamingServer()) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 2);
            final HttpRequest request = server.request();
            final HttpStreamResponse first = transport.stream(request);
            first.close();
            first.close();

            final HttpStreamResponse second = transport.stream(request);
            final HttpStreamResponse third = transport.stream(request);
            final ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                final Future<HttpStreamResponse> fourth = executor.submit(() -> transport.stream(request));

                final Throwable blocked = catchThrowable(() -> fourth.get(300, TimeUnit.MILLISECONDS));

                assertThat(blocked).isInstanceOf(TimeoutException.class);
                second.close();
                fourth.get(WAIT_SECONDS, TimeUnit.SECONDS).close();
                third.close();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void stream_shouldReleaseSlotImmediately_whenResponseIsBuffered() throws Exception {
        try (ScriptedHttpServer server =
                new ScriptedHttpServer((call, exchange) -> exchange.sendResponseHeaders(500, -1))) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 1);
            final HttpRequest request = server.request();

            final HttpStreamResponse first = transport.stream(request);
            final HttpStreamResponse second = transport.stream(request);

            assertSoftly(softly -> {
                softly.assertThat(first.isStreaming()).isFalse();
                softly.assertThat(second.isStreaming()).isFalse();
            });
        }
    }

    @Test
    void execute_shouldThrowInterruptedFailureAndKeepSlot_whenInterruptedWhileWaiting() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            if (call == 1) {
                entered.countDown();
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            exchange.sendResponseHeaders(200, -1);
        })) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 1);
            final HttpRequest request = server.request();
            final ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                final Future<HttpResponse> holder = executor.submit(() -> transport.execute(request));
                entered.await(WAIT_SECONDS, TimeUnit.SECONDS);
                final Future<List<Object>> waiter = executor.submit(() -> {
                    Thread.currentThread().interrupt();
                    final Throwable thrown = catchThrowable(() -> transport.execute(request));
                    return List.of(thrown, Thread.currentThread().isInterrupted());
                });

                final List<Object> outcome = waiter.get(WAIT_SECONDS, TimeUnit.SECONDS);
                release.countDown();
                holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
                final HttpResponse after = transport.execute(request);

                assertSoftly(softly -> {
                    softly.assertThat((Throwable) outcome.getFirst())
                            .isInstanceOf(NonRetryableIOException.class)
                            .hasCauseInstanceOf(InterruptedIOException.class);
                    softly.assertThat(outcome.get(1)).isEqualTo(true);
                    softly.assertThat(server.calls()).isEqualTo(2);
                    softly.assertThat(after.statusCode()).isEqualTo(200);
                });
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void execute_shouldFailWithNonRetryableTimeout_whenNoSlotFreesWithinMaxSlotWait() throws Exception {
        try (ScriptedHttpServer server = streamingServer()) {
            final ConcurrencyLimitingHttpTransport transport =
                    new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 1, Duration.ofMillis(100));
            final HttpRequest request = server.request();
            final HttpStreamResponse held = transport.stream(request);

            final long started = System.nanoTime();
            final Throwable thrown = catchThrowable(() -> transport.execute(request));
            final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            held.close();
            final HttpResponse afterClose = transport.execute(request);

            assertSoftly(softly -> {
                softly.assertThat(thrown)
                        .isInstanceOf(NonRetryableIOException.class)
                        .hasMessageContaining("no free request slot")
                        .hasCauseInstanceOf(InterruptedIOException.class);
                softly.assertThat(waitedMillis).isGreaterThanOrEqualTo(90L);
                softly.assertThat(afterClose.statusCode()).isEqualTo(200);
            });
        }
    }

    @Test
    void stream_shouldReleaseSlotAndCloseResponse_whenWrappingFails() throws Exception {
        final HttpStreamResponse opened = mock(HttpStreamResponse.class);
        when(opened.isStreaming()).thenReturn(true);
        when(opened.statusCode()).thenReturn(200);
        when(opened.headers()).thenThrow(new IllegalStateException("wrap failed"));
        doThrow(new IOException("close failed")).when(opened).close();
        final HttpTransport delegate = HttpTransportMocks.mock();
        when(delegate.execute(any())).thenReturn(ok());
        when(delegate.stream(any())).thenReturn(opened);
        final ConcurrencyLimitingHttpTransport transport =
                new ConcurrencyLimitingHttpTransport(delegate, 1, Duration.ofMillis(200));

        final Throwable thrown = catchThrowable(() -> transport.stream(REQUEST));
        final Throwable next = catchThrowable(() -> transport.execute(REQUEST));

        assertSoftly(softly -> {
            softly.assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("wrap failed")
                    .hasSuppressedException(new IOException("close failed"));
            softly.assertThat(next).isNull();
        });
        verify(opened).close();
    }

    @Test
    void stream_shouldReleaseSlotAndCloseResponse_whenWrappingFailsAndCloseSucceeds() throws Exception {
        final HttpStreamResponse opened = mock(HttpStreamResponse.class);
        when(opened.isStreaming()).thenReturn(true);
        when(opened.statusCode()).thenReturn(200);
        when(opened.headers()).thenThrow(new IllegalStateException("wrap failed"));
        final HttpTransport delegate = HttpTransportMocks.mock();
        when(delegate.execute(any())).thenReturn(ok());
        when(delegate.stream(any())).thenReturn(opened);
        final ConcurrencyLimitingHttpTransport transport =
                new ConcurrencyLimitingHttpTransport(delegate, 1, Duration.ofMillis(200));

        final Throwable thrown = catchThrowable(() -> transport.stream(REQUEST));
        final Throwable next = catchThrowable(() -> transport.execute(REQUEST));

        verify(opened).close();
        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("wrap failed");
            softly.assertThat(thrown.getSuppressed()).isEmpty();
            softly.assertThat(next).isNull();
        });
        verify(opened).close();
    }

    @Test
    void constructor_shouldThrow_whenMaxSlotWaitNotPositive() {
        final var mock = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() -> new ConcurrencyLimitingHttpTransport(mock, 1, Duration.ZERO));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSlotWait");
    }

    @Test
    void constructor_shouldThrow_whenLimitNotPositive() {
        final var mock = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() -> new ConcurrencyLimitingHttpTransport(mock, 0));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrent");
    }

    @Test
    void constructor_shouldThrow_whenDelegateNull() {
        final Throwable thrown = catchThrowable(() -> new ConcurrencyLimitingHttpTransport(null, 1));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("delegate");
    }

    @Test
    void close_shouldCloseDelegate_whenCalled() {
        final HttpTransport delegate = HttpTransportMocks.mock();
        final ConcurrencyLimitingHttpTransport transport = new ConcurrencyLimitingHttpTransport(delegate, 1);

        transport.close();

        verify(delegate).close();
    }

    @Test
    void constructor_shouldThrow_whenMaxSlotWaitAboveLimit() {
        final var mock = HttpTransportMocks.mock();
        final var duration = Duration.ofDays(365 * 400);

        final Throwable thrown = catchThrowable(() -> new ConcurrencyLimitingHttpTransport(mock, 1, duration));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSlotWait");
    }

    private static void runConcurrently(final int threads, final IoCall call) throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(executor.submit(() -> {
                    call.run();
                    return null;
                }));
            }
            for (final Future<?> task : tasks) {
                task.get(WAIT_SECONDS * 3L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IoCall {
        void run() throws IOException;
    }

    private static ScriptedHttpServer recordingServer(final int expectedPeak, final AtomicInteger maxConcurrent)
            throws IOException {
        final AtomicInteger inFlight = new AtomicInteger();
        final CountDownLatch peak = new CountDownLatch(expectedPeak);
        return new ScriptedHttpServer((call, exchange) -> {
            final int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            peak.countDown();
            try {
                if (!peak.await(300, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("the expected number of requests did not arrive together");
                }
            } finally {
                inFlight.decrementAndGet();
            }
            exchange.sendResponseHeaders(200, -1);
        });
    }

    private static ScriptedHttpServer streamingServer() throws IOException {
        return new ScriptedHttpServer((call, exchange) -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
    }

    private static HttpTransport failingDelegate() {
        return HttpTransportMocks.mock(invocation -> {
            if ("close".equals(invocation.getMethod().getName())) {
                return null;
            }
            throw new IOException("boom");
        });
    }

    private static HttpResponse ok() {
        return new HttpResponse(200, HttpHeaders.empty(), HttpBody.zeroLength());
    }

    @Test
    void stream_shouldPassBulkReadsThrough_whenResponseIsStreaming() throws IOException {
        final HttpTransport delegate = HttpTransportMocks.mock();
        when(delegate.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        200, HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
        final ConcurrencyLimitingHttpTransport transport = new ConcurrencyLimitingHttpTransport(delegate, 1);
        final byte[] buffer = new byte[3];

        final int read;
        try (HttpStreamResponse response = transport.stream(REQUEST)) {
            read = response.body().read(buffer, 0, buffer.length);
        }

        assertSoftly(softly -> {
            softly.assertThat(read).isEqualTo(3);
            softly.assertThat(buffer).containsExactly(1, 2, 3);
        });
    }
}
