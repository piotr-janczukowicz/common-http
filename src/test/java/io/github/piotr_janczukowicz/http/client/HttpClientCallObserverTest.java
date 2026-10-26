package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpClientCallObserverTest {

    private static final URI BASE = URI.create("https://api.example.test/");

    private HttpTransport transport;

    private final List<HttpCallEvent> events = new ArrayList<>();

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldReportResponse_whenCallSucceeds() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of("X-Request-Id", "r-1"), null));

        try (HttpClient client = client(events::add)) {
            client.execute(HttpCall.get("/user").withQuery(Map.of("token", "secret")));
        }

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertSoftly(softly -> {
                    softly.assertThat(event.method()).isEqualTo(HttpMethod.GET);
                    softly.assertThat(event.target()).isEqualTo("https://api.example.test/user");
                    softly.assertThat(event.statusCode()).isEqualTo(200);
                    softly.assertThat(event.responseHeader("x-request-id")).contains("r-1");
                    softly.assertThat(event.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
                    softly.assertThat(event.isFailure()).isFalse();
                    softly.assertThat(event.failure()).isNull();
                }));
    }

    @Test
    void execute_shouldReportStatusAndStillThrow_whenResponseIsAnError() throws IOException {
        when(transport.execute(any()))
                .thenReturn(
                        new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.of(new byte[0])));

        try (HttpClient client = client(events::add)) {
            final var call = HttpCall.get("/missing");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.statusCode()).isEqualTo(404);
            assertThat(event.isFailure()).isFalse();
        });
    }

    @Test
    void execute_shouldReportFailure_whenTransportThrows() throws IOException {
        final IOException refused = new ConnectException("refused");
        when(transport.execute(any())).thenThrow(refused);

        try (HttpClient client = client(events::add)) {
            final var call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isSameAs(refused);
        }

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertSoftly(softly -> {
                    softly.assertThat(event.isFailure()).isTrue();
                    softly.assertThat(event.failure()).isSameAs(refused);
                    softly.assertThat(event.statusCode()).isZero();
                    softly.assertThat(event.responseHeaders()).isEqualTo(HttpHeaders.empty());
                    softly.assertThat(event.target()).isEqualTo("https://api.example.test/user");
                }));
    }

    @Test
    void execute_shouldKeepStatusAndHeaders_whenRedirectIsRefusedByPolicy() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(
                                LOCATION,
                                List.of("https://api.example.test/elsewhere"),
                                "X-Request-Id",
                                List.of("r-3"))),
                        HttpBody.absent()));

        try (HttpClient client = client(events::add)) {
            final var call = HttpCall.of(HttpMethod.POST, "/start").withBody(HttpBody.of(new byte[] {1}));

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertSoftly(softly -> {
                    softly.assertThat(event.statusCode()).isEqualTo(302);
                    softly.assertThat(event.responseHeader("X-Request-Id")).contains("r-3");
                    softly.assertThat(event.isFailure()).isTrue();
                    softly.assertThat(event.failure()).isInstanceOf(HttpException.class);
                }));
    }

    @Test
    void execute_shouldReportEvent_whenResponseHeadersObserverThrows() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (HttpClient client = HttpClient.builder(transport, config())
                .callObserver(events::add)
                .responseHeadersObserver(headers -> {
                    throw new IllegalStateException("headers observer broke");
                })
                .build()) {
            final var call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
        }

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.statusCode()).isEqualTo(200));
    }

    @Test
    void stream_shouldReportEventAndCloseBody_whenResponseHeadersObserverThrows() throws IOException {
        final AtomicBoolean isClosed = new AtomicBoolean();
        final ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                isClosed.set(true);
            }
        };
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(HttpStatus.OK.code(), HttpHeaders.empty(), body));

        try (HttpClient client = HttpClient.builder(transport, config())
                .callObserver(events::add)
                .responseHeadersObserver(headers -> {
                    throw new IllegalStateException("headers observer broke");
                })
                .build()) {
            final var call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, HttpStreamResponse::statusCode));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
        }

        assertThat(isClosed).isTrue();
        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.statusCode()).isEqualTo(200));
    }

    @Test
    void execute_shouldNotChangeOutcome_whenObserverThrows() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (HttpClient client = client(event -> {
            throw new IllegalStateException("observer broke");
        })) {
            final HttpResponse response = client.execute(HttpCall.get("/user"));

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void execute_shouldNotReport_whenRequestIsRejectedBeforeTheTransport() {
        try (HttpClient client = client(events::add)) {
            final var call = HttpCall.get(URI.create("https://other.example.test/x"));

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
        }

        assertThat(events).isEmpty();
    }

    @Test
    void stream_shouldReportResponse_whenCallSucceeds() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(),
                        HttpHeaders.of("X-Request-Id", "r-2"),
                        new ByteArrayInputStream(new byte[] {1})));

        try (HttpClient client = client(events::add)) {
            final int status = client.stream(HttpCall.get("/zip"), HttpStreamResponse::statusCode);

            assertThat(status).isEqualTo(200);
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.statusCode()).isEqualTo(200);
            assertThat(event.responseHeader("X-Request-Id")).contains("r-2");
            assertThat(event.target()).isEqualTo("https://api.example.test/zip");
        });
    }

    @Test
    void stream_shouldReportFailure_whenTransportThrows() throws IOException {
        final IOException refused = new ConnectException("refused");
        when(transport.stream(any())).thenThrow(refused);

        try (HttpClient client = client(events::add)) {
            final var call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, HttpStreamResponse::statusCode));

            assertThat(thrown).isSameAs(refused);
        }

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.failure()).isSameAs(refused));
    }

    @Test
    void execute_shouldWorkWithoutObserver_whenNoneIsSet() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (HttpClient client = HttpClient.builder(transport, config()).build()) {
            assertThat(client.execute(HttpCall.get("/user")).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void callObserver_shouldThrow_whenNull() {
        final HttpClient.Builder builder = HttpClient.builder(transport, config());

        final Throwable thrown = catchThrowable(() -> builder.callObserver(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("callObserver");
    }

    private HttpClient client(final HttpCallObserver observer) {
        return HttpClient.builder(transport, config()).callObserver(observer).build();
    }

    private static HttpClientConfiguration config() {
        return HttpClientConfiguration.builder(BASE).build();
    }
}
