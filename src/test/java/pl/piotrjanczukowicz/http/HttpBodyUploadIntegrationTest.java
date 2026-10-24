package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static pl.piotrjanczukowicz.http.HttpMethod.POST;
import static pl.piotrjanczukowicz.http.HttpStatus.OK;
import static pl.piotrjanczukowicz.http.HttpStatus.TOO_MANY_REQUESTS;
import static pl.piotrjanczukowicz.test.http.StubHttpServer.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransportConfiguration;
import pl.piotrjanczukowicz.http.transport.HttpTransports;
import pl.piotrjanczukowicz.http.transport.RetryConfiguration;
import pl.piotrjanczukowicz.http.transport.RetryingHttpTransport;
import pl.piotrjanczukowicz.test.http.StubHttpServer;
import pl.piotrjanczukowicz.testsupport.IntegrationTest;

@IntegrationTest
class HttpBodyUploadIntegrationTest {

    private static final String UPLOAD_PATH = "/upload";
    private static final int FIVE_MIB = 5 * 1024 * 1024;

    @RegisterExtension
    static final StubHttpServer http = new StubHttpServer();

    @TempDir
    Path tempDir;

    @Test
    void execute_shouldSendFileBytes_whenOkHttpTransportUploadsFiveMiB() throws Exception {
        assertFileUpload(okHttp());
    }

    @Test
    void execute_shouldSendStreamBytes_whenOkHttpTransportUploadsFiveMiB() throws Exception {
        assertStreamUpload(okHttp());
    }

    @Test
    void execute_shouldResendStream_whenOkHttpTransportRetriesAfter429() throws Exception {
        assertStreamRetry(okHttp());
    }

    private void assertFileUpload(final HttpTransport inner) throws Exception {
        final byte[] payload = payload();
        final Path file = tempDir.resolve("asset.bin");
        Files.write(file, payload);
        http.stub(POST, UPLOAD_PATH, OK.code());

        try (inner) {
            inner.execute(post(HttpBody.ofFile(file)));
        }

        http.verifyRequested(POST, UPLOAD_PATH);
        http.verifyBody(POST, UPLOAD_PATH, payload);
        http.verifyHeader(POST, UPLOAD_PATH, CONTENT_LENGTH, Integer.toString(FIVE_MIB));
    }

    private void assertStreamUpload(final HttpTransport inner) throws Exception {
        final byte[] payload = payload();
        http.stub(POST, UPLOAD_PATH, OK.code());

        try (inner) {
            inner.execute(post(HttpBody.ofStream(() -> new ByteArrayInputStream(payload), payload.length)));
        }

        http.verifyRequested(POST, UPLOAD_PATH);
        http.verifyBody(POST, UPLOAD_PATH, payload);
        http.verifyHeader(POST, UPLOAD_PATH, CONTENT_LENGTH, Integer.toString(FIVE_MIB));
    }

    private void assertStreamRetry(final HttpTransport inner) throws Exception {
        final byte[] payload = payload();
        http.stub(POST, UPLOAD_PATH, status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"), status(OK));

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                inner,
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ZERO)
                        .maxBackoff(Duration.ZERO)
                        .minRateLimitWait(Duration.ZERO)
                        .rateLimitJitter(Duration.ZERO)
                        .build())) {
            final HttpResponse response =
                    transport.execute(post(HttpBody.ofStream(() -> new ByteArrayInputStream(payload), payload.length)));
            assertThat(response.statusCode()).isEqualTo(OK.code());
        }

        http.verifyRequested(POST, UPLOAD_PATH, 2);
        http.verifyBody(POST, UPLOAD_PATH, payload);
    }

    private HttpRequest post(final HttpBody body) {
        return new HttpRequest(POST, http.uri(UPLOAD_PATH), HttpHeaders.empty(), body);
    }

    private static byte[] payload() {
        final byte[] payload = new byte[FIVE_MIB];
        Arrays.fill(payload, (byte) 7);
        return payload;
    }

    private static HttpTransport okHttp() {
        return HttpTransports.okHttp(HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(30))
                .build());
    }

    @Test
    void execute_shouldReopenStreamForEachAttempt_whenOkHttpTransportRetriesAfter429() throws Exception {
        final byte[] payload = {1, 2, 3};
        final AtomicInteger opened = new AtomicInteger();
        http.stub(POST, UPLOAD_PATH, status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"), status(OK));

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                okHttp(),
                RetryConfiguration.builder()
                        .defaultBackoff(Duration.ZERO)
                        .maxBackoff(Duration.ZERO)
                        .minRateLimitWait(Duration.ZERO)
                        .rateLimitJitter(Duration.ZERO)
                        .build())) {
            transport.execute(post(HttpBody.ofStream(
                    () -> {
                        opened.incrementAndGet();
                        return new ByteArrayInputStream(payload);
                    },
                    payload.length)));
        }

        assertThat(opened).hasValue(2);
    }
}
