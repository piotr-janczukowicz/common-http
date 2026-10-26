package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpStatus.FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.NonRetryableIOException;
import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@IntegrationTest
class HttpClientTransportIntegrationTest {

    private static final String USER_PATH = "/user";
    private static final long SMALL_LIMIT = 16;

    @RegisterExtension
    static final StubHttpServer api = new StubHttpServer("127.0.0.1");

    @RegisterExtension
    static final StubHttpServer other = new StubHttpServer("localhost");

    @Test
    void execute_shouldApplyTransportConfiguration_whenTransportBuiltFromIt() {
        api.stub(GET, USER_PATH, status(OK).body(new byte[64]));

        final Throwable thrown;
        try (final HttpClient client = client(limitedTransport())) {
            final var call = HttpCall.get(USER_PATH);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void execute_shouldNotRetry_whenParentClosedBeforeCall() {
        api.stub(GET, USER_PATH, status(OK));
        final HttpClient client = client(newTransport());
        client.close();
        client.close();

        final var call = HttpCall.get(USER_PATH);

        final Throwable thrown = catchThrowable(() -> client.execute(call));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class);
        api.verifyRequested(GET, USER_PATH, 0);
    }

    @Test
    void execute_shouldDropQueryFromExceptionUri_whenCrossOriginRedirectEndsInError() {
        other.stub(GET, "/target", status(NOT_FOUND));
        api.stub(GET, "/start", status(FOUND.code()).header(LOCATION, other.uri("/target") + "?sig=secret"));

        final HttpException thrown;
        try (final HttpClient client = client(newTransport())) {
            final HttpCall call = HttpCall.get("/start");
            thrown = (HttpException) catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown.uri()).contains(other.uri("/target"));
        assertThat(thrown.uri().orElseThrow().getRawQuery()).isNull();
    }

    @Test
    void execute_shouldKeepQueryInExceptionUri_whenRedirectStaysOnOrigin() {
        api.stub(GET, "/end", status(NOT_FOUND));
        api.stub(GET, "/begin", status(FOUND.code()).header(LOCATION, api.uri("/end") + "?page=2"));

        final HttpException thrown;
        try (final HttpClient client = client(newTransport())) {
            final HttpCall call = HttpCall.get("/begin");
            thrown = (HttpException) catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown.uri().orElseThrow().getRawQuery()).isEqualTo("page=2");
    }

    @Test
    void execute_shouldNotLeakQuery_whenRequestIsOutsideBaseUri() {
        try (final HttpClient client = client(newTransport())) {
            final var call = HttpCall.get(URI.create("https://user:pw@elsewhere.example.test/x?token=secret#frag"));

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("request URI is not under the API base URI: https://elsewhere.example.test/x");
        }
    }

    private static HttpClientConfiguration config() {
        return HttpClientConfiguration.of(URI.create(api.baseUri() + "/"));
    }

    private static HttpTransport limitedTransport() {
        return new RetryingHttpTransport(
                HttpTransports.okHttp(HttpTransportConfiguration.builder()
                        .maxResponseBytes(SMALL_LIMIT)
                        .build()),
                RetryConfiguration.defaults());
    }

    private static HttpTransport newTransport() {
        return new RetryingHttpTransport(HttpTransports.okHttp(), RetryConfiguration.defaults());
    }

    private static HttpClient client(final HttpTransport transport) {
        return HttpClient.builder(transport, config()).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void execute_shouldReopenStreamBodyForRedirectHop_whenKeepMethodRedirect(final int status) throws Exception {
        final byte[] payload = {1, 2, 3, 4};
        api.stub(
                HttpMethod.POST,
                "/start",
                status(status).header(LOCATION, api.uri("/target").toString()));
        api.stub(HttpMethod.POST, "/target", status(OK));
        final AtomicInteger opened = new AtomicInteger();
        final HttpBody body = HttpBody.ofStream(
                () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(payload);
                },
                payload.length);

        try (final HttpClient client = client(newTransport())) {
            final int code = client.execute(
                            HttpCall.of(HttpMethod.POST, "/start").withBody(body))
                    .statusCode();

            assertThat(code).isEqualTo(OK.code());
        }

        assertThat(opened).hasValue(2);
        api.verifyBody(HttpMethod.POST, "/target", payload);
    }
}
