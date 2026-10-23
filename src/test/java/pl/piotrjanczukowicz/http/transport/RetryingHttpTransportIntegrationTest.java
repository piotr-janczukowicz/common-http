package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static pl.piotrjanczukowicz.http.HttpMethod.GET;
import static pl.piotrjanczukowicz.http.HttpMethod.POST;
import static pl.piotrjanczukowicz.http.HttpStatus.*;
import static pl.piotrjanczukowicz.http.support.StubHttpServer.connectionReset;
import static pl.piotrjanczukowicz.http.support.StubHttpServer.status;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.support.StubHttpServer;
import pl.piotrjanczukowicz.test.IntegrationTest;

@IntegrationTest
class RetryingHttpTransportIntegrationTest {

    private static final String USER_PATH = "/user";

    @RegisterExtension
    static final StubHttpServer http = new StubHttpServer();

    @Test
    void execute_shouldReturnOk_whenRateLimitedThenSucceeds() throws Exception {
        http.stub(
                GET,
                USER_PATH,
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(OK).body(new byte[] {1}));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        http.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldReturnOk_whenCustomDetectorSignalsResetThenSucceeds() throws Exception {
        http.stub(GET, USER_PATH, status(TOO_MANY_REQUESTS).header(RateLimitDetectors.RESET_HEADER, "0"), status(OK));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        http.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldReturnForbidden_whenResponseIsOrdinaryForbidden() throws Exception {
        http.stub(GET, USER_PATH, FORBIDDEN.code());

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(FORBIDDEN.code());
        http.verifyRequested(GET, USER_PATH);
    }

    @Test
    void execute_shouldReturnOk_whenConnectionResetThenSucceeds() throws Exception {
        http.stub(GET, USER_PATH, connectionReset(), status(OK));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        http.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldThrow_whenIoExceptionOnPost() {
        http.stub(POST, "/repos", connectionReset());

        final HttpRequest request =
                new HttpRequest(POST, http.uri("/repos"), HttpHeaders.empty(), HttpBody.of(new byte[] {1}));

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
        }

        http.verifyRequested(POST, "/repos");
    }

    @Test
    void execute_shouldReturnRateLimited_whenAttemptsExhausted() throws Exception {
        http.stub(
                GET,
                USER_PATH,
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(TOO_MANY_REQUESTS.code());
        http.verifyRequested(GET, USER_PATH, 4);
    }

    @Test
    void execute_shouldSleepForRetryAfter_whenRateLimitedThenSucceeds() throws Exception {
        final AtomicInteger sleptMillis = new AtomicInteger();
        http.stub(GET, USER_PATH, status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "2"), status(OK));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);

        final HttpTransport delegate = HttpTransports.okHttp(HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .build());
        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                delegate,
                RetryConfiguration.builder()
                        .fullJitter(false)
                        .rateLimitJitter(Duration.ZERO)
                        .build(),
                duration -> sleptMillis.set((int) duration.toMillis()))) {
            transport.execute(request);
        }

        assertThat(sleptMillis.get()).isEqualTo(2000);
    }

    private static RetryingHttpTransport newTransport(final RetryingHttpTransport.Sleeper sleeper) {
        final HttpTransport delegate = HttpTransports.okHttp(HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .build());
        final RetryConfiguration configuration = RetryConfiguration.builder()
                .rateLimitDetector(RateLimitDetectors.resetHeader())
                .defaultBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .rateLimitJitter(Duration.ZERO)
                .clockSkewMargin(Duration.ZERO)
                .build();
        return new RetryingHttpTransport(delegate, configuration, sleeper);
    }
}
