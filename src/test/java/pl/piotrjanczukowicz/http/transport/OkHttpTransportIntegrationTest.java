package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpMethod.DELETE;
import static pl.piotrjanczukowicz.http.HttpMethod.GET;
import static pl.piotrjanczukowicz.http.HttpMethod.PATCH;
import static pl.piotrjanczukowicz.http.HttpMethod.POST;
import static pl.piotrjanczukowicz.http.HttpStatus.CREATED;
import static pl.piotrjanczukowicz.http.HttpStatus.FOUND;
import static pl.piotrjanczukowicz.http.HttpStatus.NO_CONTENT;
import static pl.piotrjanczukowicz.http.HttpStatus.OK;
import static pl.piotrjanczukowicz.http.support.StubHttpServer.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.http.support.DropMidRequestServer;
import pl.piotrjanczukowicz.http.support.LargeContentLengthServer;
import pl.piotrjanczukowicz.http.support.ScriptedHttpServer;
import pl.piotrjanczukowicz.http.support.StallAfterHeadersServer;
import pl.piotrjanczukowicz.http.support.StubHttpServer;
import pl.piotrjanczukowicz.test.IntegrationTest;

@IntegrationTest
class OkHttpTransportIntegrationTest {

    private static final String USER_PATH = "/user";

    private static final int BODY_SIZE = 256 * 1024;

    @RegisterExtension
    static final StubHttpServer http = new StubHttpServer();

    @TempDir
    Path tempDir;

    @Test
    void execute_shouldReturnResponse_whenGetSucceeds() throws Exception {
        http.stub(GET, USER_PATH, status(OK).header("X-Test", "value").body(new byte[] {42}));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(response.header("X-Test")).contains("value");
            softly.assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(42);
        });
        http.verifyRequested(GET, USER_PATH);
    }

    @Test
    void execute_shouldReturnAbsentBody_whenResponseHasNoBody() throws Exception {
        http.stub(GET, USER_PATH, NO_CONTENT.code());

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.code());
            softly.assertThat(response.body().isAbsent()).isTrue();
        });
    }

    @Test
    void execute_shouldSendWriteOnce_whenServerAnswers503WithRetryAfterZero() throws Exception {
        http.stub(
                POST,
                USER_PATH,
                status(503).header("Retry-After", "0"),
                status(CREATED).body(new byte[] {1}));

        final HttpRequest request =
                new HttpRequest(POST, http.uri(USER_PATH), HttpHeaders.empty(), HttpBody.of(new byte[] {7}));
        final HttpResponse response;

        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(503);
        http.verifyRequested(POST, USER_PATH, 1);
    }

    @Test
    void execute_shouldSendAcceptEncodingGzip_whenCallerOmitsAcceptEncoding() throws Exception {
        http.stub(GET, USER_PATH, OK.code());

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyHeader(GET, USER_PATH, "Accept-Encoding", "gzip");
    }

    @Test
    void execute_shouldDecompressGzipBody_whenContentEncodingIsGzip() throws Exception {
        final byte[] plain = "hello-example".getBytes(StandardCharsets.UTF_8);
        http.stub(
                GET,
                USER_PATH,
                status(OK)
                        .header("Content-Encoding", "gzip")
                        .header("X-Test", "value")
                        .body(gzip(plain)));

        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(plain);
            softly.assertThat(response.header("Content-Encoding")).isEmpty();
            softly.assertThat(response.header("Content-Length")).isEmpty();
            softly.assertThat(response.header("X-Test")).contains("value");
        });
    }

    @Test
    void execute_shouldThrowResponseTooLarge_whenBodyExceedsLimit() {
        http.stub(GET, "/big", status(OK).body(new byte[64]));
        final HttpRequest request = new HttpRequest(GET, http.uri("/big"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = limitedTransport(63)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class).hasMessageContaining("63 bytes");
        }
    }

    @Test
    void execute_shouldReturnBody_whenBodyEqualsLimit() throws Exception {
        http.stub(GET, "/exact", status(OK).body(new byte[64]));
        final HttpRequest request = new HttpRequest(GET, http.uri("/exact"), HttpHeaders.empty(), null);

        final HttpResponse response;
        try (final HttpTransport transport = limitedTransport(64)) {
            response = transport.execute(request);
        }

        assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0])).hasSize(64);
    }

    @Test
    void execute_shouldLimitInflatedSize_whenGzipBodyExpandsPastLimit() throws Exception {
        final byte[] compressed = gzip(new byte[100_000]);
        http.stub(GET, "/bomb", status(OK).header("Content-Encoding", "gzip").body(compressed));
        final HttpRequest request = new HttpRequest(GET, http.uri("/bomb"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = limitedTransport(10_000)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(compressed).hasSizeLessThan(10_000);
            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
        }
    }

    @Test
    void execute_shouldSendRequestHeaders_whenHeadersPresent() throws Exception {
        http.stub(POST, "/repos", CREATED.code());

        final HttpRequest request = new HttpRequest(
                POST,
                http.uri("/repos"),
                Map.of("Content-Type", "application/json"),
                HttpBody.of("{}".getBytes(StandardCharsets.UTF_8)));

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyHeader(POST, "/repos", "Content-Type", "application/json");
    }

    @Test
    void execute_shouldSendEmptyBody_whenPostHasNoPayload() throws Exception {
        http.stub(POST, "/repos", CREATED.code());

        final HttpRequest request = new HttpRequest(POST, http.uri("/repos"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyRequested(POST, "/repos");
    }

    @Test
    void execute_shouldSendEmptyBody_whenPutOrPatchHasNoPayload() throws Exception {
        http.stub(HttpMethod.PUT, "/repos", CREATED.code());
        http.stub(HttpMethod.PATCH, "/repos", OK.code());

        try (final HttpTransport transport = newTransport()) {
            transport.execute(new HttpRequest(HttpMethod.PUT, http.uri("/repos"), HttpHeaders.empty(), null));
            transport.execute(new HttpRequest(HttpMethod.PATCH, http.uri("/repos"), HttpHeaders.empty(), null));
        }

        http.verifyRequested(HttpMethod.PUT, "/repos");
        http.verifyRequested(HttpMethod.PATCH, "/repos");
    }

    @Test
    void execute_shouldSendTrimmedHeaderValue_whenValueHasSurroundingWhitespace() throws Exception {
        http.stub(GET, USER_PATH, status(OK));
        final HttpRequest request =
                new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.of("X-Test", " 	 padded value 	 "), null);

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyHeader(GET, USER_PATH, "X-Test", "padded value");
    }

    @Test
    void execute_shouldSendCallerHostHeader_whenHostSet() throws Exception {
        http.stub(GET, USER_PATH, status(OK));
        final HttpRequest request = new HttpRequest(
                GET, http.uri(USER_PATH), HttpHeaders.of("Host", "virtual.example.test", "Connection", "close"), null);

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyHeader(GET, USER_PATH, "Host", "virtual.example.test");
    }

    @Test
    void execute_shouldSendBody_whenExpectContinueSet() throws Exception {
        http.stub(POST, "/repos", CREATED.code());
        final HttpRequest request = new HttpRequest(
                POST,
                http.uri("/repos"),
                HttpHeaders.of("Expect", "100-continue"),
                HttpBody.of("payload".getBytes(StandardCharsets.UTF_8)));

        final HttpResponse response;
        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(CREATED.code());
        http.verifyBody(POST, "/repos", "payload");
    }

    @Test
    void execute_shouldSendBody_whenDeleteHasBody() throws Exception {
        http.stub(DELETE, USER_PATH, status(OK));
        final HttpRequest request = new HttpRequest(
                DELETE,
                http.uri(USER_PATH),
                HttpHeaders.empty(),
                HttpBody.of("payload".getBytes(StandardCharsets.UTF_8)));

        try (final HttpTransport transport = newTransport()) {
            transport.execute(request);
        }

        http.verifyBody(DELETE, USER_PATH, "payload");
    }

    @Test
    void execute_shouldReturnRedirect_whenTransportDoesNotFollow() throws Exception {
        http.stubRedirect("/start", http.uri("/target").toString(), FOUND.code());

        final HttpRequest request = new HttpRequest(GET, http.uri("/start"), HttpHeaders.empty(), null);
        final HttpResponse response;

        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FOUND.code());
        http.verifyRequested(GET, "/start");
    }

    @Test
    void execute_shouldThrow_whenResponseHeadersAreSlow() {
        http.stub(GET, "/slow", status(OK).delay(Duration.ofSeconds(5)));

        final HttpTransportConfiguration configuration = HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofMillis(300))
                .build();
        final HttpRequest request = new HttpRequest(GET, http.uri("/slow"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(InterruptedIOException.class);
        }
    }

    @Test
    void execute_shouldSucceed_whenChunkedBodyIsSlowUnderTimeout() throws Exception {
        http.stub(
                GET,
                "/chunked",
                status(OK).body(new byte[] {1, 2, 3, 4}).chunkedDribbleDelay(4, Duration.ofSeconds(1)));

        final HttpTransportConfiguration configuration = HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .build();
        final HttpRequest request = new HttpRequest(GET, http.uri("/chunked"), HttpHeaders.empty(), null);

        final HttpResponse response;
        try (final HttpTransport transport = HttpTransports.okHttp(configuration)) {
            response = transport.execute(request);
        }

        assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0])).containsExactly(1, 2, 3, 4);
    }

    @Test
    void execute_shouldThrow_whenBodyStallsAfterHeaders() throws Exception {
        final HttpTransportConfiguration configuration = HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(1))
                .build();

        try (final StallAfterHeadersServer stall = new StallAfterHeadersServer(Duration.ofSeconds(60));
                final HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final var request = new HttpRequest(GET, stall.uri(), HttpHeaders.empty(), null);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(InterruptedIOException.class);
        }
    }

    @Test
    void execute_shouldNotResendStreamBody_whenPooledConnectionDropsMidRequest() throws Exception {
        final AtomicInteger opened = new AtomicInteger();
        final HttpBody body = HttpBody.ofStream(
                () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(new byte[BODY_SIZE]);
                },
                BODY_SIZE);

        try (final DropMidRequestServer server = new DropMidRequestServer();
                final HttpTransport transport = newTransport()) {
            transport.execute(new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null));

            final var request = new HttpRequest(POST, server.uri(), HttpHeaders.empty(), body);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
            assertThat(opened).hasValue(1);
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @Test
    void execute_shouldFailWithIoException_whenServerAnswersWithStatusAbove599() throws Exception {
        try (final ScriptedHttpServer server =
                        new ScriptedHttpServer((call, exchange) -> exchange.sendResponseHeaders(999, -1));
                final HttpTransport transport = newTransport()) {
            final HttpRequest request = server.request();

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessageContaining("999");
        }
    }

    @Test
    void stream_shouldFailWithNonRetryableIoException_whenServerAnswersWithStatusAbove599() throws Exception {
        try (final ScriptedHttpServer server =
                        new ScriptedHttpServer((call, exchange) -> exchange.sendResponseHeaders(999, -1));
                final HttpTransport transport = newTransport()) {
            final HttpRequest request = server.request();

            final Throwable thrown = catchThrowable(() -> transport.stream(request));

            assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessageContaining("999");
        }
    }

    @Test
    void execute_shouldNotResendBytesBody_whenPooledConnectionDropsMidRequest() throws Exception {
        final HttpBody body = HttpBody.of(new byte[BODY_SIZE]);

        try (final DropMidRequestServer server = new DropMidRequestServer();
                final HttpTransport transport = newTransport()) {
            transport.execute(new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null));

            final var request = new HttpRequest(POST, server.uri(), HttpHeaders.empty(), body);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @Test
    void execute_shouldNotResendFileBody_whenPooledConnectionDropsMidRequest() throws Exception {
        final Path file = tempDir.resolve("upload.bin");
        Files.write(file, new byte[BODY_SIZE]);

        try (final DropMidRequestServer server = new DropMidRequestServer();
                final HttpTransport transport = newTransport()) {
            transport.execute(new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null));

            final var request = new HttpRequest(PATCH, server.uri(), HttpHeaders.empty(), HttpBody.ofFile(file));

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @Test
    void execute_shouldRetryOnFreshConnection_whenGetOnPooledConnectionDropsMidRequest() throws Exception {
        try (final DropMidRequestServer server = new DropMidRequestServer();
                final HttpTransport transport = newTransport()) {
            transport.execute(new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null));

            final HttpResponse response =
                    transport.execute(new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null));

            assertThat(response.statusCode()).isEqualTo(OK.code());
            assertThat(server.connections()).isEqualTo(2);
        }
    }

    @Test
    void execute_shouldThrowTimeout_whenSlowDrippedBodyExceedsCallTimeout() {
        http.stub(
                GET,
                "/dribble",
                status(OK).body(new byte[] {1, 2, 3, 4}).chunkedDribbleDelay(4, Duration.ofSeconds(3)));
        final HttpTransportConfiguration configuration = callTimeoutConfiguration(Duration.ofMillis(500));
        final HttpRequest request = new HttpRequest(GET, http.uri("/dribble"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(InterruptedIOException.class).hasMessage("timeout");
        }
    }

    @Test
    void execute_shouldApplyCallTimeout_whenClientWrapped() {
        http.stub(
                GET,
                "/dribble",
                status(OK).body(new byte[] {1, 2, 3, 4}).chunkedDribbleDelay(4, Duration.ofSeconds(3)));
        final HttpRequest request = new HttpRequest(GET, http.uri("/dribble"), HttpHeaders.empty(), null);

        try (final HttpTransport transport = HttpTransports.okHttp(callTimeoutConfiguration(Duration.ofMillis(500)))) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(InterruptedIOException.class).hasMessage("timeout");
        }
    }

    @Test
    void execute_shouldSucceed_whenNoCallTimeoutAndBodyDripsSlowly() throws Exception {
        http.stub(
                GET,
                "/dribble",
                status(OK).body(new byte[] {1, 2, 3, 4}).chunkedDribbleDelay(4, Duration.ofSeconds(1)));
        final HttpRequest request = new HttpRequest(GET, http.uri("/dribble"), HttpHeaders.empty(), null);

        final HttpResponse response;
        try (final HttpTransport transport = newTransport()) {
            response = transport.execute(request);
        }

        assertThat(response.body().mapBytes(bytes -> bytes, () -> new byte[0])).containsExactly(1, 2, 3, 4);
    }

    @Test
    void execute_shouldNotRetryCallTimeout_whenWrappedInRetryingTransport() {
        http.stub(
                GET,
                "/dribble",
                status(OK).body(new byte[] {1, 2, 3, 4}).chunkedDribbleDelay(4, Duration.ofSeconds(3)));
        final HttpRequest request = new HttpRequest(GET, http.uri("/dribble"), HttpHeaders.empty(), null);

        try (final RetryingHttpTransport transport = new RetryingHttpTransport(
                HttpTransports.okHttp(callTimeoutConfiguration(Duration.ofMillis(500))),
                RetryConfiguration.builder().maxIoAttempts(3).build())) {
            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(InterruptedIOException.class).hasMessage("timeout");
        }
        http.verifyRequested(GET, "/dribble", 1);
    }

    @Test
    void execute_shouldFailFast_whenContentLengthExceedsLimit() throws Exception {
        try (final LargeContentLengthServer server = new LargeContentLengthServer();
                final HttpTransport transport = limitedTransport(1024)) {
            final var request = new HttpRequest(GET, server.uri(), HttpHeaders.empty(), null);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class).hasMessageContaining("1024 bytes");
        }
    }

    @Test
    void execute_shouldReturnHeaders_whenHeadAnnouncesLargeContentLength() throws Exception {
        http.stub(HttpMethod.HEAD, "/big", status(OK).header("Content-Length", "1000000"));
        final HttpRequest request = new HttpRequest(HttpMethod.HEAD, http.uri("/big"), HttpHeaders.empty(), null);

        final HttpResponse response;
        try (final HttpTransport transport = limitedTransport(1024)) {
            response = transport.execute(request);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(OK.code());
            softly.assertThat(response.header("Content-Length")).contains("1000000");
            softly.assertThat(response.body().isAbsent()).isTrue();
        });
    }

    @Test
    void execute_shouldFailWithNonRetryableException_whenTransportClosed() {
        http.stub(GET, USER_PATH, OK.code());
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        transport.close();

        final Throwable thrown = catchThrowable(() -> transport.execute(request));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessage("transport closed");
        http.verifyRequested(GET, USER_PATH, 0);
    }

    @Test
    void close_shouldBeIdempotent_whenCalledTwice() {
        final HttpTransport transport = newTransport();

        transport.close();
        final Throwable thrown = catchThrowable(transport::close);

        assertThat(thrown).isNull();
    }

    @Test
    void execute_shouldReportClosed_whenInFlightCallCancelledByClose() throws Exception {
        http.stub(GET, "/slow", status(OK).delay(Duration.ofSeconds(10)));
        final HttpRequest request = new HttpRequest(GET, http.uri("/slow"), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread caller = new Thread(() -> thrown.set(catchThrowable(() -> transport.execute(request))));
        caller.start();
        http.awaitRequested(GET, "/slow", Duration.ofSeconds(5));

        transport.close();
        final boolean finished = caller.join(Duration.ofSeconds(5));

        assertThat(finished).isTrue();
        assertThat(thrown.get()).isInstanceOf(NonRetryableIOException.class).hasMessage("transport closed");
    }

    @Test
    void execute_shouldThrowNonRetryable_whenStreamSourceFails() {
        http.stub(POST, "/upload", OK.code());
        final HttpBody body = HttpBody.ofStream(
                () -> new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("disk gone");
                    }
                },
                4);

        try (final HttpTransport transport = newTransport()) {
            final var request = new HttpRequest(POST, http.uri("/upload"), HttpHeaders.empty(), body);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown)
                    .isInstanceOf(NonRetryableIOException.class)
                    .hasMessage("request body source failed")
                    .hasRootCauseMessage("disk gone");
        }
    }

    @Test
    void execute_shouldThrowNonRetryable_whenStreamLongerThanAnnounced() {
        http.stub(POST, "/upload", OK.code());
        final HttpBody body = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[8]), 4);

        try (final HttpTransport transport = newTransport()) {
            final var request = new HttpRequest(POST, http.uri("/upload"), HttpHeaders.empty(), body);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessageContaining("Content-Length of 4");
        }
    }

    @Test
    void execute_shouldThrowNonRetryable_whenStreamShorterThanAnnounced() {
        http.stub(POST, "/upload", OK.code());
        final HttpBody body = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[2]), 4);

        try (final HttpTransport transport = newTransport()) {
            final var request = new HttpRequest(POST, http.uri("/upload"), HttpHeaders.empty(), body);

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessageContaining("Content-Length of 4");
        }
    }

    @Test
    void stream_shouldNotBufferOrLimitBody_whenResponseIsSuccessful() throws Exception {
        final byte[] payload = new byte[BODY_SIZE];
        Arrays.fill(payload, (byte) 7);
        http.stub(GET, USER_PATH, status(OK).header("X-Test", "value").body(payload));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);

        try (final HttpTransport transport = limitedTransport(16);
                final HttpStreamResponse response = transport.stream(request)) {
            assertSoftly(softly -> {
                softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(response.isStreaming()).isTrue();
                softly.assertThat(response.header("X-Test")).contains("value");
            });
            assertThat(response.body().readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void stream_shouldBufferBody_whenResponseIsNotSuccessful() throws Exception {
        http.stub(GET, USER_PATH, status(HttpStatus.NOT_FOUND).body(new byte[] {1, 2, 3}));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);

        try (final HttpTransport transport = newTransport();
                final HttpStreamResponse response = transport.stream(request)) {
            assertSoftly(softly -> {
                softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.code());
                softly.assertThat(response.isStreaming()).isFalse();
                softly.assertThat(response.bufferedResponse().body().mapBytes(bytes -> bytes, () -> new byte[0]))
                        .containsExactly(1, 2, 3);
            });
        }
    }

    @Test
    void stream_shouldThrowResponseTooLarge_whenErrorBodyExceedsLimit() {
        http.stub(GET, USER_PATH, status(HttpStatus.NOT_FOUND).body(new byte[64]));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);

        try (final HttpTransport transport = limitedTransport(16)) {
            final Throwable thrown = catchThrowable(() -> transport.stream(request));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
        }
    }

    @Test
    void stream_shouldFailWithNonRetryableIOException_whenTransportIsClosed() {
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        transport.close();

        final Throwable thrown = catchThrowable(() -> transport.stream(request));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class);
    }

    private static HttpTransport newTransport() {
        return HttpTransports.okHttp(HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .build());
    }

    private static HttpTransportConfiguration callTimeoutConfiguration(final Duration callTimeout) {
        return HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .callTimeout(callTimeout)
                .build();
    }

    private static HttpTransport limitedTransport(final long maxResponseBytes) {
        return HttpTransports.okHttp(HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .maxResponseBytes(maxResponseBytes)
                .build());
    }

    private static byte[] gzip(final byte[] plain) throws IOException {
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        return compressed.toByteArray();
    }

    @Test
    void stream_shouldReportClosed_whenInFlightCallCancelledByClose() throws Exception {
        http.stub(GET, "/slow", status(OK).delay(Duration.ofSeconds(10)));
        final HttpRequest request = new HttpRequest(GET, http.uri("/slow"), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread caller = new Thread(() -> thrown.set(catchThrowable(() -> transport.stream(request))));
        caller.start();
        http.awaitRequested(GET, "/slow", Duration.ofSeconds(5));

        transport.close();
        final boolean finished = caller.join(Duration.ofSeconds(5));

        assertThat(finished).isTrue();
        assertThat(thrown.get()).isInstanceOf(NonRetryableIOException.class).hasMessage("transport closed");
    }

    @Test
    void close_shouldFailRead_whenStreamedBodyStillOpen() throws Exception {
        final byte[] payload = new byte[BODY_SIZE * 8];
        Arrays.fill(payload, (byte) 7);
        http.stub(GET, USER_PATH, status(OK).body(payload));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        final HttpStreamResponse response = transport.stream(request);

        transport.close();
        final var bodyValue = response.body();

        final Throwable thrown = catchThrowable(() -> bodyValue.readAllBytes());

        assertThat(thrown).isInstanceOf(IOException.class);
    }

    @Test
    void close_shouldAllowClosingStreamedBody_whenTransportAlreadyClosed() throws Exception {
        http.stub(GET, USER_PATH, status(OK).body(new byte[] {1, 2, 3}));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final HttpTransport transport = newTransport();
        final HttpStreamResponse response = transport.stream(request);
        transport.close();

        final Throwable thrown = catchThrowable(response::close);

        assertThat(thrown).isNull();
    }

    @Test
    void close_shouldLeaveNoConnections_whenCallsRaceWithClose() throws Exception {
        http.stub(GET, USER_PATH, status(OK).body(new byte[] {1}));
        final HttpRequest request = new HttpRequest(GET, http.uri(USER_PATH), HttpHeaders.empty(), null);
        final List<Integer> leftover = new ArrayList<>();

        for (int round = 0; round < 20; round++) {
            final OkHttpClient client = new OkHttpClient();
            final OkHttpTransport transport = new OkHttpTransport(client, BODY_SIZE);
            final CountDownLatch served = new CountDownLatch(4);
            final ExecutorService executor = Executors.newFixedThreadPool(4);
            final List<Future<?>> callers = new ArrayList<>();
            for (int caller = 0; caller < 4; caller++) {
                callers.add(executor.submit(() -> callUntilClosed(transport, request, served)));
            }
            served.await(10, TimeUnit.SECONDS);

            transport.close();
            for (final Future<?> caller : callers) {
                caller.get(10, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            leftover.add(client.connectionPool().connectionCount());
        }

        assertThat(leftover).containsOnly(0);
    }

    private static void callUntilClosed(
            final HttpTransport transport, final HttpRequest request, final CountDownLatch served) {
        boolean isServed = false;
        while (true) {
            try {
                transport.execute(request);
            } catch (final IOException closed) {
                return;
            }
            if (!isServed) {
                isServed = true;
                served.countDown();
            }
        }
    }
}
