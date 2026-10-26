package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpClientResponseHeadersObserverTest {
    private static final String REMAINING_HEADER = "X-Remaining";

    private static final URI BASE = URI.create("https://api.example.test/");

    private HttpTransport transport;

    private final List<HttpHeaders> observed = new ArrayList<>();

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldObserveHeaders_whenResponseComesFromTheRequestedOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of(REMAINING_HEADER, "7"), null));

        try (HttpClient client = client()) {
            client.execute(HttpCall.get("/user"));
        }

        assertThat(observed)
                .singleElement()
                .satisfies(headers -> assertThat(headers.get(REMAINING_HEADER)).contains("7"));
    }

    @Test
    void execute_shouldNotObserveHeaders_whenCallSetsItsOwnAuthorization() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of(REMAINING_HEADER, "7"), null));

        try (HttpClient client = client()) {
            client.execute(HttpCall.get("/app").withHeaders(HttpHeaders.of(AUTHORIZATION, "Bearer app-jwt")));
        }

        assertThat(observed).isEmpty();
    }

    @Test
    void execute_shouldObserveHeaders_whenRedirectStaysOnTheSameOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirectTo("https://api.example.test/moved"))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of(REMAINING_HEADER, "7"), null));

        try (HttpClient client = client()) {
            client.execute(HttpCall.get("/user"));
        }

        assertThat(observed).hasSize(1);
    }

    @Test
    void execute_shouldNotObserveHeaders_whenFinalResponseComesFromAnotherOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirectTo("https://cdn.example.test/file"))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of(REMAINING_HEADER, "0"), null));

        try (HttpClient client = client()) {
            final HttpResponse response = client.execute(HttpCall.get("/file"));

            assertThat(response.statusCode()).isEqualTo(200);
        }

        assertThat(observed).isEmpty();
    }

    @Test
    void stream_shouldNotObserveHeaders_whenFinalResponseComesFromAnotherOrigin() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.buffered(redirectTo("https://cdn.example.test/file")))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(),
                        HttpHeaders.of(REMAINING_HEADER, "0"),
                        new ByteArrayInputStream(new byte[] {1})));

        try (HttpClient client = client()) {
            final int status = client.stream(HttpCall.get("/file"), HttpStreamResponse::statusCode);

            assertThat(status).isEqualTo(200);
        }

        assertThat(observed).isEmpty();
    }

    @Test
    void stream_shouldObserveHeaders_whenResponseComesFromTheRequestedOrigin() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(),
                        HttpHeaders.of(REMAINING_HEADER, "7"),
                        new ByteArrayInputStream(new byte[] {1})));

        try (HttpClient client = client()) {
            client.stream(HttpCall.get("/zip"), HttpStreamResponse::statusCode);
        }

        assertThat(observed).hasSize(1);
    }

    private static HttpResponse redirectTo(final String location) {
        return new HttpResponse(
                HttpStatus.FOUND.code(), HttpHeaders.fromWire(Map.of(LOCATION, List.of(location))), HttpBody.absent());
    }

    private HttpClient client() {
        return HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .responseHeadersObserver(observed::add)
                .build();
    }

    @Test
    void execute_shouldNotObserveHeaders_whenGateBlockedTheRequest() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.TOO_MANY_REQUESTS.code(),
                        HttpHeaders.of(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE, "blocked"),
                        null));

        try (HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/user");

            catchThrowable(() -> client.execute(call));
        }

        assertThat(observed).isEmpty();
    }
}
