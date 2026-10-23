package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LOCATION;
import static pl.piotrjanczukowicz.http.HttpMethod.GET;
import static pl.piotrjanczukowicz.http.HttpStatus.FOUND;
import static pl.piotrjanczukowicz.http.HttpStatus.NOT_FOUND;
import static pl.piotrjanczukowicz.http.HttpStatus.OK;
import static pl.piotrjanczukowicz.http.support.StubHttpServer.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.http.support.StubHttpServer;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransportConfiguration;
import pl.piotrjanczukowicz.http.transport.HttpTransports;
import pl.piotrjanczukowicz.http.transport.RetryConfiguration;
import pl.piotrjanczukowicz.http.transport.RetryingHttpTransport;
import pl.piotrjanczukowicz.test.IntegrationTest;

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
            thrown = catchThrowable(() -> client.execute(HttpCall.get(USER_PATH)));
        }

        assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void execute_shouldNotRetry_whenParentClosedBeforeCall() {
        api.stub(GET, USER_PATH, status(OK));
        final HttpClient client = client(newTransport());
        client.close();
        client.close();

        final Throwable thrown = catchThrowable(() -> client.execute(HttpCall.get(USER_PATH)));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class);
        api.verifyRequested(GET, USER_PATH, 0);
    }

    @Test
    void execute_shouldDropQueryFromExceptionUri_whenCrossOriginRedirectEndsInError() {
        other.stub(GET, "/target", status(NOT_FOUND));
        api.stub(GET, "/start", status(FOUND.code()).header(LOCATION, other.uri("/target") + "?sig=secret"));

        final HttpException thrown;
        try (final HttpClient client = client(newTransport())) {
            thrown = (HttpException) catchThrowable(() -> client.execute(HttpCall.get("/start")));
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
            thrown = (HttpException) catchThrowable(() -> client.execute(HttpCall.get("/begin")));
        }

        assertThat(thrown.uri().orElseThrow().getRawQuery()).isEqualTo("page=2");
    }

    @Test
    void execute_shouldNotLeakQuery_whenRequestIsOutsideBaseUri() {
        try (final HttpClient client = client(newTransport())) {
            final Throwable thrown = catchThrowable(() -> client.execute(
                    HttpCall.get(URI.create("https://user:pw@elsewhere.example.test/x?token=secret#frag"))));

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
