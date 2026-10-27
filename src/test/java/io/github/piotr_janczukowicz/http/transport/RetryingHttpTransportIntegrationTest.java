package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpMethod.POST;
import static io.github.piotr_janczukowicz.http.HttpStatus.*;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.connectionReset;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.test.http.RateLimitDetectors;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@IntegrationTest
class RetryingHttpTransportIntegrationTest {

    private static final String USER_PATH = "/user";

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    @Test
    void execute_shouldReturnOk_whenRateLimitedThenSucceeds() throws Exception {
        HTTP.stub(
                GET,
                USER_PATH,
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(OK).body(new byte[] {1}));

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        HTTP.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldReturnOk_whenCustomDetectorSignalsResetThenSucceeds() throws Exception {
        HTTP.stub(GET, USER_PATH, status(TOO_MANY_REQUESTS).header(RateLimitDetectors.RESET_HEADER, "0"), status(OK));

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        HTTP.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldReturnForbidden_whenResponseIsOrdinaryForbidden() throws Exception {
        HTTP.stub(GET, USER_PATH, FORBIDDEN.code());

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(FORBIDDEN.code());
        HTTP.verifyRequested(GET, USER_PATH);
    }

    @Test
    void execute_shouldReturnOk_whenConnectionResetThenSucceeds() throws Exception {
        HTTP.stub(GET, USER_PATH, connectionReset(), status(OK));

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        HTTP.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void execute_shouldThrow_whenIoExceptionOnPost() {
        HTTP.stub(POST, "/repos", connectionReset());

        final HttpRequest request =
                new HttpRequest(POST, HTTP.uri("/repos"), HttpHeaders.empty(), HttpBody.of(new byte[] {1}));

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
        }

        HTTP.verifyRequested(POST, "/repos");
    }

    @Test
    void execute_shouldReturnRateLimited_whenAttemptsExhausted() throws Exception {
        HTTP.stub(
                GET,
                USER_PATH,
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"));

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final RetryingHttpTransport transport = newTransport(unused -> {})) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(TOO_MANY_REQUESTS.code());
        HTTP.verifyRequested(GET, USER_PATH, 4);
    }

    @Test
    void execute_shouldSleepForRetryAfter_whenRateLimitedThenSucceeds() throws Exception {
        final AtomicInteger sleptMillis = new AtomicInteger();
        HTTP.stub(GET, USER_PATH, status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "2"), status(OK));

        final HttpRequest request = new HttpRequest(GET, HTTP.uri(USER_PATH), HttpHeaders.empty(), null);

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
