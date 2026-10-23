package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class OkHttpTransportTest {

    private static final URI API_URI = URI.create("https://api.example.test/user");

    @Test
    void request_shouldThrow_whenUriIsNotHttp() {
        final Throwable thrown = catchThrowable(() -> new HttpRequest(
                HttpMethod.GET, URI.create("mailto:dev@example.com"), HttpHeaders.empty(), HttpBody.absent()));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uri");
    }

    @Test
    void execute_shouldThrow_whenRequestIsNull() {
        try (final HttpTransport transport =
                wrap(cannedClient(200, new byte[0]), HttpTransportConfiguration.defaults())) {
            final Throwable thrown = catchThrowable(() -> transport.execute(null));

            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
        }
    }

    @Test
    void execute_shouldMapGetRequest_whenHeadersPresent() throws Exception {
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();
        try (final HttpTransport transport = wrapCapturing(sent, 204, new byte[0])) {
            transport.execute(
                    new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.of("X-Test", "value"), HttpBody.absent()));
        }

        final Request request = sent.get();
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo("GET");
            softly.assertThat(request.url().uri()).isEqualTo(API_URI);
            softly.assertThat(request.header("X-Test")).isEqualTo("value");
            softly.assertThat(request.body()).isNull();
        });
    }

    @Test
    void execute_shouldSendEmptyPostBody_whenBodyAbsent() throws Exception {
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();
        try (final HttpTransport transport = wrapCapturing(sent, 201, new byte[0])) {
            transport.execute(new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.absent()));
        }

        final RequestBody body = sent.get().body();
        assertThat(body).isNotNull();
        assertThat(bodyBytes(body)).isEmpty();
    }

    @Test
    void execute_shouldSendBodyBytes_whenPresent() throws Exception {
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();
        try (final HttpTransport transport = wrapCapturing(sent, 201, new byte[0])) {
            transport.execute(
                    new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1, 2, 3})));
        }

        assertThat(bodyBytes(sent.get().body())).containsExactly(1, 2, 3);
    }

    @Test
    void execute_shouldSendFileBytes_whenPost(@TempDir final Path dir) throws Exception {
        final byte[] payload = {1, 2, 3, 4};
        final Path file = dir.resolve("asset.bin");
        Files.write(file, payload);
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();

        try (final HttpTransport transport = wrapCapturing(sent, 201, new byte[0])) {
            transport.execute(new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.ofFile(file)));
        }

        final RequestBody body = sent.get().body();
        final long contentLength = body.contentLength();
        final byte[] sentBytes = bodyBytes(body);
        assertSoftly(softly -> {
            softly.assertThat(contentLength).isEqualTo(payload.length);
            softly.assertThat(sentBytes).containsExactly(payload);
        });
    }

    @Test
    void execute_shouldSendStreamBytes_whenPost() throws Exception {
        final byte[] payload = {5, 6, 7};
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();

        try (final HttpTransport transport = wrapCapturing(sent, 201, new byte[0])) {
            transport.execute(new HttpRequest(
                    HttpMethod.POST,
                    API_URI,
                    HttpHeaders.empty(),
                    HttpBody.ofStream(() -> new ByteArrayInputStream(payload), payload.length)));
        }

        final RequestBody body = sent.get().body();
        final long contentLength = body.contentLength();
        final byte[] sentBytes = bodyBytes(body);
        assertSoftly(softly -> {
            softly.assertThat(body.contentType()).isNull();
            softly.assertThat(contentLength).isEqualTo(payload.length);
            softly.assertThat(sentBytes).containsExactly(payload);
        });
    }

    @Test
    void execute_shouldMarkEveryWriteBodyOneShot_whenBodiesMapped(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("asset.bin");
        Files.write(file, new byte[] {1});
        final HttpBody stream = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {1}), 1);

        final boolean streamOneShot = sentBody(HttpMethod.POST, stream).isOneShot();
        final boolean bytesOneShot =
                sentBody(HttpMethod.PUT, HttpBody.of(new byte[] {1})).isOneShot();
        final boolean fileOneShot =
                sentBody(HttpMethod.PATCH, HttpBody.ofFile(file)).isOneShot();
        final boolean deleteOneShot =
                sentBody(HttpMethod.DELETE, HttpBody.of(new byte[] {1})).isOneShot();
        final boolean emptyPostOneShot =
                sentBody(HttpMethod.POST, HttpBody.absent()).isOneShot();
        final boolean bodylessDeleteOneShot =
                sentBody(HttpMethod.DELETE, HttpBody.absent()).isOneShot();

        assertSoftly(softly -> {
            softly.assertThat(streamOneShot).isTrue();
            softly.assertThat(bytesOneShot).isTrue();
            softly.assertThat(fileOneShot).isTrue();
            softly.assertThat(deleteOneShot).isTrue();
            softly.assertThat(emptyPostOneShot).isTrue();
            softly.assertThat(bodylessDeleteOneShot).isTrue();
        });
    }

    @Test
    void execute_shouldSendEmptyBody_whenDeleteHasNoBody() throws Exception {
        final long contentLength =
                sentBody(HttpMethod.DELETE, HttpBody.absent()).contentLength();

        assertThat(contentLength).isZero();
    }

    @Test
    void execute_shouldKeepBytesBodyRepeatable_whenMethodIsOptions() throws Exception {
        assertThat(sentBody(HttpMethod.OPTIONS, HttpBody.of(new byte[] {1})).isOneShot())
                .isFalse();
    }

    @Test
    void execute_shouldThrowResponseTooLarge_whenGetAnnouncesLargerContentLength() {
        try (final HttpTransport transport = announcingTransport(200)) {
            final Throwable thrown = catchThrowable(() -> transport.execute(
                    new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), HttpBody.absent())));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class).hasMessageContaining("10 bytes");
        }
    }

    @Test
    void execute_shouldReturnResponse_whenHeadAnnouncesLargerContentLength() throws Exception {
        final HttpResponse response;
        try (final HttpTransport transport = announcingTransport(200)) {
            response = transport.execute(
                    new HttpRequest(HttpMethod.HEAD, API_URI, HttpHeaders.empty(), HttpBody.absent()));
        }

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 204, 205, 304})
    void execute_shouldReturnResponse_whenBodylessStatusAnnouncesLargerContentLength(final int status)
            throws Exception {
        final HttpResponse response;
        try (final HttpTransport transport = announcingTransport(status)) {
            response =
                    transport.execute(new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), HttpBody.absent()));
        }

        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Test
    void execute_shouldMapResponse_whenMultiValueHeadersAndEmptyBody() throws Exception {
        final HttpResponse response;
        try (final HttpTransport transport = wrap(cannedMultiHeaderClient(), HttpTransportConfiguration.defaults())) {
            response = transport.execute(new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null));
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(200);
            softly.assertThat(response.header("X-Multi")).contains("one, two");
            softly.assertThat(response.body().isAbsent()).isTrue();
        });
    }

    @Test
    void close_shouldShutDownDispatcher_whenTransportOwnsClient() {
        final OkHttpClient client = new OkHttpClient();
        final HttpTransport transport = owned(client);

        transport.close();

        assertThat(client.dispatcher().executorService().isShutdown()).isTrue();
    }

    @Test
    void close_shouldNotThrow_whenTransportOwnsClient() {
        final HttpTransport transport = HttpTransports.okHttp(HttpTransportConfiguration.defaults());

        final Throwable thrown = catchThrowable(transport::close);

        assertThat(thrown).isNull();
    }

    private static HttpTransport wrapCapturing(
            final AtomicReference<@Nullable Request> sent, final int code, final byte[] body) {
        return wrap(
                new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            sent.set(chain.request());
                            return canned(chain.request(), code, body);
                        })
                        .build(),
                HttpTransportConfiguration.defaults());
    }

    private static RequestBody sentBody(final HttpBody body) throws Exception {
        return sentBody(HttpMethod.POST, body);
    }

    private static RequestBody sentBody(final HttpMethod method, final HttpBody body) throws Exception {
        final AtomicReference<@Nullable Request> sent = new AtomicReference<>();
        try (final HttpTransport transport = wrapCapturing(sent, 201, new byte[0])) {
            transport.execute(new HttpRequest(method, API_URI, HttpHeaders.empty(), body));
        }
        return sent.get().body();
    }

    private static HttpTransport announcingTransport(final int status) {
        final OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("announced")
                        .body(ResponseBody.create(new Buffer(), MediaType.parse("text/plain"), 1000))
                        .build())
                .build();
        return wrap(
                client,
                HttpTransportConfiguration.builder().maxResponseBytes(10).build());
    }

    @Test
    void stream_shouldCloseResponse_whenWrappingTheBodyThrows() {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final ResponseBody body = new ResponseBody() {
            @Override
            public @Nullable MediaType contentType() {
                return null;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public okio.BufferedSource source() {
                if (sourceCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("source broke");
                }
                return new Buffer();
            }
        };
        final OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build())
                .build();

        final Throwable thrown;
        try (final HttpTransport transport = wrap(client, HttpTransportConfiguration.defaults())) {
            thrown = catchThrowable(() ->
                    transport.stream(new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), HttpBody.absent())));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("source broke");
        assertThat(sourceCalls).hasValueGreaterThan(1);
    }

    private static OkHttpClient cannedClient(final int code, final byte[] body) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> canned(chain.request(), code, body))
                .build();
    }

    private static OkHttpClient cannedMultiHeaderClient() {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .addHeader("X-Multi", "one")
                        .addHeader("X-Multi", "two")
                        .body(ResponseBody.create(new byte[0], MediaType.parse("text/plain")))
                        .build())
                .build();
    }

    private static Response canned(final Request request, final int code, final byte[] body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("application/octet-stream")))
                .build();
    }

    private static HttpTransport wrap(final OkHttpClient client, final HttpTransportConfiguration configuration) {
        return new OkHttpTransport(client, configuration.maxResponseBytes());
    }

    private static HttpTransport owned(final OkHttpClient client) {
        return new OkHttpTransport(client, 64L * 1024 * 1024);
    }

    private static byte[] bodyBytes(final RequestBody body) throws IOException {
        final Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readByteArray();
    }

    @Test
    void execute_shouldThrowNonRetryable_whenFileBodyIsMissing(@TempDir final Path dir) {
        final HttpBody missing = HttpBody.ofFile(dir.resolve("missing.bin"));

        final Throwable thrown = catchThrowable(() -> sentBody(missing));

        assertThat(thrown)
                .isInstanceOf(NonRetryableIOException.class)
                .hasMessage("request body file is not readable")
                .hasRootCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void writeTo_shouldThrowNonRetryable_whenFileDeletedAfterRequestBuilt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("asset.bin");
        Files.write(file, new byte[] {1, 2});
        final RequestBody body = sentBody(HttpBody.ofFile(file));
        Files.delete(file);

        final Throwable thrown = catchThrowable(() -> bodyBytes(body));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessage("request body file is not readable");
    }

    @Test
    void writeTo_shouldThrowNonRetryable_whenFileGrowsAfterRequestBuilt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("asset.bin");
        Files.write(file, new byte[] {1, 2});
        final RequestBody body = sentBody(HttpBody.ofFile(file));
        Files.write(file, new byte[] {1, 2, 3, 4});

        final Throwable thrown = catchThrowable(() -> bodyBytes(body));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessageContaining("Content-Length of 2");
    }

    @Test
    void writeTo_shouldThrowNonRetryable_whenStreamSourceReturnsNull() throws Exception {
        final RequestBody body = sentBody(HttpBody.ofStream(() -> null, 1));

        final Throwable thrown = catchThrowable(() -> bodyBytes(body));

        assertThat(thrown).isInstanceOf(NonRetryableIOException.class).hasMessage("request body source returned null");
    }

    @Test
    void writeTo_shouldThrowNonRetryable_whenStreamSourceThrows() throws Exception {
        final RequestBody body = sentBody(HttpBody.ofStream(
                () -> {
                    throw new IllegalStateException("no stream");
                },
                1));

        final Throwable thrown = catchThrowable(() -> bodyBytes(body));

        assertThat(thrown)
                .isInstanceOf(NonRetryableIOException.class)
                .hasMessage("request body source failed")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void execute_shouldJoinRepeatedHeaderInWireOrder_whenNamesDifferInCase() throws Exception {
        final OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .addHeader("Vary", "a")
                        .addHeader("X-Other", "x")
                        .addHeader("vary", "b")
                        .body(ResponseBody.create(new byte[0], MediaType.parse("text/plain")))
                        .build())
                .build();
        final HttpResponse response;
        try (final HttpTransport transport = wrap(client, HttpTransportConfiguration.defaults())) {
            response = transport.execute(new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null));
        }

        assertSoftly(softly -> {
            softly.assertThat(response.header("Vary")).contains("a, b");
            softly.assertThat(response.headers().asMap().keySet()).containsExactlyInAnyOrder("Vary", "X-Other");
        });
    }
}
