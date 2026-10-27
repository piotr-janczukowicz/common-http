package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.*;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.NonRetryableIOException;
import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

@UnitTest
class RetryingHttpTransportTest {

    private static final URI API_URI = URI.create("https://api.example.test/user");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private HttpTransport delegate;

    @BeforeEach
    void setUpDelegate() {
        delegate = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldReturnOk_whenRateLimitedThenSucceeds() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        final HttpResponse ok =
                new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        when(delegate.execute(request)).thenReturn(limited, ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldReuseOwnedBodyArray_whenRetryingPost() throws Exception {
        final byte[] payload = {1, 2, 3};
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(payload));
        final HttpResponse retryThenOkLimited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        final HttpResponse retryThenOk =
                new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {9}));
        when(delegate.execute(request)).thenReturn(retryThenOkLimited, retryThenOk);

        try (final RetryingHttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().getFirst().body().mapBytes(bytes -> bytes, () -> new byte[0]))
                .isSameAs(payload);
        assertThat(captor.getAllValues().get(1).body().mapBytes(bytes -> bytes, () -> new byte[0]))
                .isSameAs(payload);
    }

    @Test
    void execute_shouldReturnForbidden_whenResponseIsOrdinaryForbidden() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse forbidden = new HttpResponse(HttpStatus.FORBIDDEN.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(forbidden);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(forbidden);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void stream_shouldRetryUntilStreamingResponse_whenServerErrorThenSuccess() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);
        final HttpStreamResponse ok = streaming();
        when(delegate.stream(request)).thenReturn(HttpStreamResponse.buffered(unavailable), ok);

        final HttpStreamResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.stream(request);
        }

        assertThat(response).isSameAs(ok);
        verify(delegate, times(2)).stream(request);
    }

    @Test
    void stream_shouldNotRetry_whenResponseIsStreaming() throws Exception {
        final HttpRequest request = getRequest();
        final HttpStreamResponse ok = streaming();
        when(delegate.stream(request)).thenReturn(ok);

        final HttpStreamResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.stream(request);
        }

        assertThat(response).isSameAs(ok);
        verify(delegate, times(1)).stream(request);
    }

    @Test
    void stream_shouldReturnBufferedResponse_whenErrorIsNotRetryable() throws Exception {
        final HttpRequest request = getRequest();
        final HttpStreamResponse notFound = HttpStreamResponse.buffered(
                new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {1})));
        when(delegate.stream(request)).thenReturn(notFound);

        final HttpStreamResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.stream(request);
        }

        assertThat(response).isSameAs(notFound);
        verify(delegate, times(1)).stream(request);
    }

    @Test
    void stream_shouldRetryIoFailure_whenMethodIsGet() throws Exception {
        final HttpRequest request = getRequest();
        final HttpStreamResponse ok = streaming();
        when(delegate.stream(request)).thenThrow(new SocketException("reset")).thenReturn(ok);

        final HttpStreamResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.stream(request);
        }

        assertThat(response).isSameAs(ok);
        verify(delegate, times(2)).stream(request);
    }

    @Test
    void stream_shouldRetryRateLimit_whenMethodIsPost() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        final HttpStreamResponse ok = streaming();
        when(delegate.stream(request)).thenReturn(HttpStreamResponse.buffered(limited), ok);

        final HttpStreamResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.stream(request);
        }

        assertThat(response).isSameAs(ok);
        verify(delegate, times(2)).stream(request);
    }

    @Test
    void execute_shouldNotRetryIoFailure_whenMethodIsPutByDefault() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.PUT, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final SocketException reset = new SocketException("reset");
        when(delegate.execute(request)).thenThrow(reset);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(reset);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldRetryRateLimit_whenMethodIsPutByDefault() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.PUT, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(limited, ok);

        try (final RetryingHttpTransport transport = newTransport()) {
            assertThat(transport.execute(request)).isSameAs(ok);
        }

        verify(delegate, times(2)).execute(request);
    }

    @Test
    void stream_shouldCloseResponse_whenExhaustionDetectorThrows() throws Exception {
        final HttpRequest request = getRequest();
        final AtomicBoolean isClosed = new AtomicBoolean();
        final InputStream body = new ByteArrayInputStream(new byte[] {5}) {
            @Override
            public void close() {
                isClosed.set(true);
            }
        };
        when(delegate.stream(request))
                .thenReturn(HttpStreamResponse.streaming(HttpStatus.OK.code(), HttpHeaders.empty(), body));
        final RateLimitDetector detector = new RateLimitDetector() {
            @Override
            public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
                return Optional.empty();
            }

            @Override
            public Optional<RateLimitSignal> detectExhaustion(
                    final HttpResponse response, final HttpRequest sent, final Clock clock) {
                throw new IllegalStateException("detector failed");
            }
        };
        final RetryConfiguration configuration =
                RetryConfiguration.builder().rateLimitDetector(detector).build();

        final Throwable thrown;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            thrown = catchThrowable(() -> transport.stream(request));
        }

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("detector failed");
            softly.assertThat(isClosed).isTrue();
        });
    }

    private static HttpStreamResponse streaming() {
        return HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {5}));
    }

    @Test
    void execute_shouldReturnOk_whenSocketTimeoutThenSucceedsOnGet() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request))
                .thenThrow(new SocketTimeoutException("read"))
                .thenReturn(ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldThrowInterruptedWithLastFailure_whenSleepInterruptedAfterIo() throws Exception {
        final HttpRequest request = getRequest();
        final IOException first = new IOException("first");
        when(delegate.execute(request)).thenThrow(first);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(false)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {
            throw new InterruptedException("stopped");
        })) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown)
                    .isInstanceOf(InterruptedIOException.class)
                    .hasMessage("Retry interrupted")
                    .hasCause(first);
        }
    }

    @Test
    void execute_shouldReturnOk_whenIoExceptionThenSucceedsOnGet() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenThrow(new IOException("reset")).thenReturn(ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldThrow_whenIoExceptionOnPost() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        when(delegate.execute(request)).thenThrow(new IOException("reset"));

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class).hasMessage("reset");
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldReturnRateLimited_whenAttemptsExhausted() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        when(delegate.execute(request)).thenReturn(limited);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.code());
        verify(delegate, times(4)).execute(request);
    }

    @Test
    void execute_shouldSleepForRetryAfter_whenRateLimitedThenSucceeds() throws Exception {
        final HttpRequest request = getRequest();

        final int sleptMillis = executeSleepingForRetryAfter(request, "retry-after", "2");

        assertThat(sleptMillis).isEqualTo(2000);
    }

    @Test
    void execute_shouldThrow_whenMaxAttemptsIsOneAndIoFails() throws Exception {
        final HttpRequest request = getRequest();
        when(delegate.execute(request)).thenThrow(new IOException("reset"));
        final RetryConfiguration configuration =
                RetryConfiguration.builder().maxIoAttempts(1).build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void close_shouldCloseDelegate_whenCalled() {
        final RetryingHttpTransport transport = newTransport();

        transport.close();

        verify(delegate).close();
    }

    @Test
    void execute_shouldReturnResponse_whenDelegateSucceeds() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(ok);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void constructor_shouldReturnResponse_whenOnlyDelegateProvided() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate)) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(ok);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldThrow_whenRequestIsNull() {
        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(null));

            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
        }
    }

    @Test
    void constructor_shouldThrow_whenDelegateIsNull() {
        final Throwable thrown = catchThrowable(() -> new RetryingHttpTransport(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("delegate");
    }

    @Test
    void constructor_shouldThrow_whenConfigurationIsNull() {
        final Throwable thrown = catchThrowable(() -> new RetryingHttpTransport(delegate, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void constructor_shouldThrow_whenSleeperIsNull() {
        final RetryConfiguration config = RetryConfiguration.defaults();

        final Throwable thrown = catchThrowable(() -> new RetryingHttpTransport(delegate, config, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("sleeper");
    }

    @Test
    void execute_shouldRecordGateEntryAndPublishWait_whenCollaboratorsAreInjected() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("30")), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(limited, ok);
        final RetryConfiguration configuration =
                RetryConfiguration.builder().rateLimitJitter(Duration.ZERO).build();
        final RateLimitGate gate =
                new RateLimitGate(FIXED_CLOCK, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMinutes(5));
        final List<RateLimitEvent> published = new ArrayList<>();
        final RetryEvents events =
                new RetryEvents(published::add, configuration.waits().maxRateLimitWait());

        final HttpResponse response;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, new RetryPolicy(configuration, FIXED_CLOCK), gate, events, unused -> {})) {
            response = transport.execute(request);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(gate.size()).isEqualTo(1);
            softly.assertThat(published)
                    .extracting(RateLimitEvent::type, RateLimitEvent::statusCode)
                    .containsExactly(tuple(RateLimitEvent.Type.WAIT, HttpStatus.TOO_MANY_REQUESTS.code()));
        });
    }

    @Test
    void execute_shouldReturnRateLimited_whenRetryRateLimitedDisabled() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("0")), null);
        when(delegate.execute(request)).thenReturn(limited);
        final RetryConfiguration configuration =
                RetryConfiguration.builder().retryRateLimitedResponses(false).build();

        final HttpResponse response;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(limited);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldThrow_whenRetryIoExceptionsDisabled() throws Exception {
        final HttpRequest request = getRequest();
        when(delegate.execute(request)).thenThrow(new IOException("reset"));
        final RetryConfiguration configuration =
                RetryConfiguration.builder().retryIoExceptions(false).build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class).hasMessage("reset");
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldSleepForBackoff_whenIoExceptionThenSucceeds() throws Exception {
        final HttpRequest request = getRequest();
        final AtomicInteger sleptMillis = new AtomicInteger();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenThrow(new IOException("reset")).thenReturn(ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(3))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(false)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(request);
        }

        assertThat(sleptMillis.get()).isEqualTo(3000);
    }

    @Test
    void execute_shouldReturnLastResponse_whenNextWaitWouldPassDeadline() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("30")), null);
        when(delegate.execute(request)).thenReturn(limited);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ZERO)
                .totalDeadline(Duration.ofSeconds(10))
                .build();
        final List<Duration> sleeps = new ArrayList<>();

        final HttpResponse response;
        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, configuration, sleeps::add, FIXED_CLOCK)) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(limited);
        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldRetry_whenWaitFitsDeadline() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(limited, ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ZERO)
                .totalDeadline(Duration.ofSeconds(10))
                .build();
        final List<Duration> sleeps = new ArrayList<>();

        final HttpResponse response;
        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, configuration, sleeps::add, FIXED_CLOCK)) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(ok);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void execute_shouldThrowLastFailure_whenIoBackoffWouldPassDeadline() throws Exception {
        final HttpRequest request = getRequest();
        final IOException failure = new IOException("reset");
        when(delegate.execute(request)).thenThrow(failure);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .defaultBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(false)
                .totalDeadline(Duration.ofSeconds(2))
                .build();
        final List<Duration> sleeps = new ArrayList<>();

        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, configuration, sleeps::add, FIXED_CLOCK)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(failure);
        }

        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldNotRetry_whenResponseTooLarge() throws Exception {
        final HttpRequest request = getRequest();
        when(delegate.execute(request)).thenThrow(new ResponseTooLargeException(10));

        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, RetryConfiguration.defaults(), unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldUseDefaultSleeper_whenTwoArgConstructor() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenThrow(new IOException("reset")).thenReturn(ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .defaultBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofSeconds(60))
                .fullJitter(false)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration)) {
            assertThat(transport.execute(request).statusCode()).isEqualTo(HttpStatus.OK.code());
        }

        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldReturnWithoutSleep_whenRetryAfterExceedsMaxWait() throws Exception {
        final HttpRequest request = getRequest();
        final List<Duration> sleeps = new ArrayList<>();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("3600")), null);
        when(delegate.execute(request)).thenReturn(limited);
        final RetryConfiguration configuration =
                RetryConfiguration.builder().fullJitter(false).build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, sleeps::add)) {
            transport.execute(request);
        }

        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldSleepRetryAfter_whenRetryAfterWithinMaxWait() throws Exception {
        final HttpRequest request = getRequest();

        final int sleptMillis = executeSleepingForRetryAfter(request, RETRY_AFTER, "5");

        assertThat(sleptMillis).isEqualTo(5000);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldSleepAtLeastMinSecondary_whenTooManyRequestsHaveNoHeaders() throws Exception {
        final HttpRequest request = getRequest();
        final AtomicInteger sleptMillis = new AtomicInteger();
        final HttpResponse limited = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(limited, ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .maxRateLimitWait(Duration.ofMinutes(2))
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(request);
        }

        assertThat(sleptMillis.get()).isGreaterThanOrEqualTo(60_000);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldSleepMinRateLimitWait_whenRateLimitHasNoSignalAndCapAllows() throws Exception {
        final HttpResponse limited = HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(any())).thenReturn(limited, ok);
        final AtomicInteger sleptMillis = new AtomicInteger(-1);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .maxRateLimitWait(Duration.ofMinutes(2))
                .minRateLimitWait(Duration.ofSeconds(60))
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(getRequest());
        }

        assertThat(sleptMillis.get()).isGreaterThanOrEqualTo(60_000);
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void execute_shouldRethrowOriginal_whenThreadInterrupted() throws Exception {
        final HttpRequest request = getRequest();
        final IOException failure = new IOException("reset");
        when(delegate.execute(request)).thenThrow(failure);
        Thread.currentThread().interrupt();

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldNotRetry_whenCallTimeoutDeadlineExpires() throws Exception {
        final HttpRequest request = getRequest();
        final InterruptedIOException deadline = new InterruptedIOException("timeout");
        when(delegate.execute(request)).thenThrow(deadline);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(deadline);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldRethrow_whenInterruptedIoException() throws Exception {
        final HttpRequest request = getRequest();
        final InterruptedIOException failure = new InterruptedIOException("cancelled");
        when(delegate.execute(request)).thenThrow(failure);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(failure);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldSuppressEarlierFailure_whenSecondIoAttemptFails() throws Exception {
        final HttpRequest request = getRequest();
        final IOException first = new IOException("first");
        final IOException second = new IOException("second");
        when(delegate.execute(request)).thenThrow(first, second);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .maxIoAttempts(2)
                .defaultBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(second).hasSuppressedException(first);
        }

        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldReturnOk_whenGetIsServiceUnavailableThenSucceeds() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable, unavailable, ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(3)).execute(request);
    }

    @Test
    void execute_shouldReturnLastBadGateway_whenAttemptsExhausted() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse first = new HttpResponse(HttpStatus.BAD_GATEWAY.code(), HttpHeaders.empty(), null);
        final HttpResponse second = new HttpResponse(HttpStatus.BAD_GATEWAY.code(), HttpHeaders.empty(), null);
        final HttpResponse last = new HttpResponse(HttpStatus.BAD_GATEWAY.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(first, second, last);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response).isSameAs(last);
        verify(delegate, times(3)).execute(request);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PATCH"})
    void execute_shouldNotRetry_whenNonIdempotentMethodIsServiceUnavailable(final HttpMethod method) throws Exception {
        final HttpRequest request = new HttpRequest(method, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable);
        final List<Duration> sleeps = new ArrayList<>();

        final HttpResponse response;
        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, RetryConfiguration.defaults(), sleeps::add)) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(unavailable);
        assertThat(sleeps).isEmpty();
        verify(delegate, times(1)).execute(request);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PATCH", "PUT", "DELETE"})
    void execute_shouldRetryWriteMethod_whenConnectionRefused(final HttpMethod method) throws Exception {
        final HttpRequest request = new HttpRequest(method, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request))
                .thenThrow(new ConnectException("refused"))
                .thenReturn(ok);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(ok);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldNotRetryPost_whenConnectionResetAfterSend() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final IOException reset = new SocketException("Connection reset");
        when(delegate.execute(request)).thenThrow(reset);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(reset);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldRetryPost_whenPostIsAllowListedForServerError() throws Exception {
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable, ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .ioRetryableMethods(Set.of(HttpMethod.POST))
                .fullJitter(false)
                .defaultBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .build();

        final HttpResponse response;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(ok);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldRetryServerError_whenWaitExceedsRateLimitCapOnly() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("5")), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable, ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .minRateLimitWait(Duration.ZERO)
                .maxRateLimitWait(Duration.ofSeconds(1))
                .maxServerErrorWait(Duration.ofSeconds(10))
                .fullJitter(false)
                .build();
        final AtomicInteger sleptMillis = new AtomicInteger();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()), FIXED_CLOCK)) {
            transport.execute(request);
        }

        assertThat(sleptMillis.get()).isEqualTo(5000);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldSleepRetryAfter_whenServiceUnavailableHasRetryAfter() throws Exception {
        final HttpRequest request = getRequest();
        final AtomicInteger sleptMillis = new AtomicInteger();
        final HttpResponse unavailable =
                HttpResponses.of(HttpStatus.SERVICE_UNAVAILABLE.code(), Map.of(RETRY_AFTER, List.of("2")), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable, ok);
        final RetryConfiguration configuration =
                RetryConfiguration.builder().fullJitter(false).build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(request);
        }

        assertThat(sleptMillis.get()).isEqualTo(2000);
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldNotRetry_whenInternalServerError() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse error = new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(error);

        final HttpResponse response;
        try (final RetryingHttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(error);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldNotRetry_whenRetryableServerStatusesEmpty() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse unavailable =
                new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(unavailable);
        final RetryConfiguration configuration =
                RetryConfiguration.builder().retryableServerStatuses(Set.of()).build();

        final HttpResponse response;
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(delegate, configuration, unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response).isEqualTo(unavailable);
        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldThrow_whenSleepInterrupted() throws Exception {
        final Throwable thrown = executeWhileSleepInterrupted(getRequest());

        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessage("Retry interrupted")
                .hasCauseInstanceOf(InterruptedException.class);
    }

    @ParameterizedTest
    @MethodSource("unretryableFailures")
    void execute_shouldNotRetry_whenFailureIsDeterministic(final IOException failure) throws Exception {
        final HttpRequest request = getRequest();
        when(delegate.execute(request)).thenThrow(failure);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(failure);
        }

        verify(delegate, times(1)).execute(request);
    }

    static Stream<IOException> unretryableFailures() {
        return Stream.of(
                new NonRetryableIOException("request body source failed"),
                new FileNotFoundException("missing.bin"),
                new UnknownHostException("api.example.test"),
                new SSLHandshakeException("PKIX path building failed"),
                new SSLPeerUnverifiedException("hostname mismatch"),
                new SSLKeyException("bad key"),
                new IOException("Canceled"));
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void execute_shouldRetry_whenFailureMayBeTransient(final IOException failure) throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenThrow(failure).thenReturn(ok);

        try (final RetryingHttpTransport transport = newTransport()) {
            assertThat(transport.execute(request)).isSameAs(ok);
        }

        verify(delegate, times(2)).execute(request);
    }

    static Stream<IOException> retryableFailures() {
        return Stream.of(
                new SSLException("Connection reset"),
                new SocketException("Connection reset"),
                new IOException("unexpected end of stream"),
                new IOException("Canceled by someone else", new IOException("x")));
    }

    @Test
    void execute_shouldThrowWithoutSuppressed_whenTlsFailureFollowsRetriedFailure() throws Exception {
        final HttpRequest request = getRequest();
        final SSLHandshakeException tls = new SSLHandshakeException("PKIX path building failed");
        when(delegate.execute(request))
                .thenThrow(new SocketException("Connection reset"))
                .thenThrow(tls);

        final Throwable thrown;
        try (final RetryingHttpTransport transport = newTransport()) {
            thrown = catchThrowable(() -> transport.execute(request));
        }

        assertSoftly(softly -> {
            softly.assertThat(thrown).isSameAs(tls);
            softly.assertThat(thrown.getSuppressed()).isEmpty();
        });
        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldStopRetrying_whenDelegateReportsClosed() throws Exception {
        final HttpRequest request = getRequest();
        final NonRetryableIOException closed = new NonRetryableIOException("transport closed");
        when(delegate.execute(request)).thenThrow(new IOException("reset")).thenThrow(closed);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(closed);
        }

        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldNotCallDelegateAgain_whenWaitAfterResponseInterrupted() throws Exception {
        final HttpRequest request = getRequest();
        try {
            final Throwable thrown = executeWhileSleepInterrupted(request);

            assertThat(thrown).isInstanceOf(InterruptedIOException.class).hasMessage("Retry interrupted");
            assertThat(thrown.getSuppressed()).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(delegate, times(1)).execute(request);
    }

    private RetryingHttpTransport newTransport() {
        return new RetryingHttpTransport(
                delegate,
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ZERO)
                        .maxBackoff(Duration.ZERO)
                        .minRateLimitWait(Duration.ZERO)
                        .rateLimitJitter(Duration.ZERO)
                        .clockSkewMargin(Duration.ZERO)
                        .build(),
                unused -> {});
    }

    private int executeSleepingForRetryAfter(final HttpRequest request, final String headerName, final String seconds)
            throws Exception {
        final AtomicInteger sleptMillis = new AtomicInteger();
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(headerName, List.of(seconds)), null);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenReturn(limited, ok);
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .fullJitter(false)
                .rateLimitJitter(Duration.ZERO)
                .build();

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate, configuration, duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(request);
        }
        return sleptMillis.get();
    }

    private Throwable executeWhileSleepInterrupted(final HttpRequest request) throws Exception {
        final HttpResponse limited =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("5")), null);
        when(delegate.execute(request)).thenReturn(limited);

        try (final RetryingHttpTransport transport =
                new RetryingHttpTransport(delegate, RetryConfiguration.builder().build(), unused -> {
                    throw new InterruptedException("stopped");
                })) {
            return catchThrowable(() -> transport.execute(request));
        }
    }

    private static HttpRequest getRequest() {
        return new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);
    }

    @Test
    void execute_shouldRetry_whenProtocolExceptionOnGet() throws Exception {
        final HttpRequest request = getRequest();
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request))
                .thenThrow(new ProtocolException("unexpected end of stream"))
                .thenReturn(ok);

        try (final RetryingHttpTransport transport = newTransport()) {
            assertThat(transport.execute(request)).isSameAs(ok);
        }

        verify(delegate, times(2)).execute(request);
    }

    @Test
    void execute_shouldNotRetry_whenStreamBodyLengthMismatchReportedAsNonRetryable() throws Exception {
        final HttpBody stream = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[2]), 4);
        final HttpRequest request = new HttpRequest(HttpMethod.PUT, API_URI, HttpHeaders.empty(), stream);
        final NonRetryableIOException mismatch = new NonRetryableIOException("request body length does not match");
        when(delegate.execute(request)).thenThrow(mismatch);

        try (final RetryingHttpTransport transport = newTransport()) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isSameAs(mismatch);
        }

        verify(delegate, times(1)).execute(request);
    }

    @Test
    void execute_shouldSendSameStreamBodyEachAttempt_whenIoFailureIsRetried() throws Exception {
        final HttpBody stream = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {1}), 1);
        final HttpRequest request = new HttpRequest(HttpMethod.PUT, API_URI, HttpHeaders.empty(), stream);
        final HttpResponse ok = new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
        when(delegate.execute(request)).thenThrow(new SocketException("reset")).thenReturn(ok);

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate,
                RetryConfiguration.builder()
                        .ioRetryableMethods(Set.of(HttpMethod.PUT))
                        .defaultBackoff(Duration.ZERO)
                        .maxBackoff(Duration.ZERO)
                        .build(),
                unused -> {})) {
            assertThat(transport.execute(request)).isSameAs(ok);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(sent -> assertThat(sent.body()).isSameAs(stream));
    }

    @Test
    void execute_shouldStopRetryLoop_whenTransportClosedWhileWaiting() throws Exception {
        final HttpRequest request = getRequest();
        final NonRetryableIOException closed = new NonRetryableIOException("transport closed");
        when(delegate.execute(request)).thenThrow(new SocketException("reset")).thenThrow(closed);
        final RetryingHttpTransport[] holder = new RetryingHttpTransport[1];
        holder[0] = new RetryingHttpTransport(
                delegate,
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(1))
                        .fullJitter(false)
                        .build(),
                unused -> holder[0].close());

        final Throwable thrown = catchThrowable(() -> holder[0].execute(request));

        assertThat(thrown).isSameAs(closed);
        verify(delegate).close();
        verify(delegate, times(2)).execute(request);
    }
}
