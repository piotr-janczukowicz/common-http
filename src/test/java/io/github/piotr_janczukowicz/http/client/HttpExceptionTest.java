package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class HttpExceptionTest {

    private static final URI REQUEST_URI = URI.create("https://api.example.test/x");

    @Test
    void from_shouldUseBodyAsMessage_whenBodyPresent() {
        final HttpResponse response =
                new HttpResponse(500, HttpHeaders.of("X-A", "b"), HttpBody.of("boom".getBytes(StandardCharsets.UTF_8)));

        final HttpException exception = HttpException.from(HttpMethod.GET, response, REQUEST_URI);

        assertSoftly(softly -> {
            softly.assertThat(exception).hasMessage("boom");
            softly.assertThat(exception.statusCode()).isEqualTo(500);
            softly.assertThat(exception.rawBody()).contains("boom");
            softly.assertThat(exception.method()).contains(HttpMethod.GET);
            softly.assertThat(exception.uri()).contains(REQUEST_URI);
            softly.assertThat(exception.headers()).isEqualTo(HttpHeaders.of("X-A", "b"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void from_shouldDropLocationHeader_whenRedirectStatus(final int status) {
        final HttpHeaders headers = HttpHeaders.empty()
                .with(LOCATION, "https://objects.example.test/file?X-Amz-Signature=secret")
                .with("X-A", "b");

        final HttpException exception =
                HttpException.from(HttpMethod.GET, new HttpResponse(status, headers, null), REQUEST_URI);

        assertThat(exception.headers()).isEqualTo(HttpHeaders.of("X-A", "b"));
    }

    @Test
    void from_shouldKeepLocationHeader_whenNotRedirectStatus() {
        final HttpHeaders headers = HttpHeaders.of(LOCATION, "https://api.example.test/created");

        final HttpException exception =
                HttpException.from(HttpMethod.GET, new HttpResponse(201, headers, null), REQUEST_URI);

        assertThat(exception.headers()).isEqualTo(headers);
    }

    @Test
    void from_shouldUseStatusMessage_whenBodyAbsent() {
        final HttpException exception =
                HttpException.from(HttpMethod.GET, new HttpResponse(404, HttpHeaders.empty(), null), REQUEST_URI);

        assertSoftly(softly -> {
            softly.assertThat(exception).hasMessage("HTTP 404");
            softly.assertThat(exception.rawBody()).isEmpty();
        });
    }

    @Test
    void constructor_shouldDefaultOptionalFields_whenNullOrBlank() {
        final HttpException exception = new HttpException(400, "bad", "  ", null, null, null);

        assertSoftly(softly -> {
            softly.assertThat(exception.rawBody()).isEmpty();
            softly.assertThat(exception.headers()).isEqualTo(HttpHeaders.empty());
            softly.assertThat(exception.method()).isEmpty();
            softly.assertThat(exception.uri()).isEmpty();
        });
    }

    @Test
    void deserialize_shouldResetHeaders() throws Exception {
        final HttpException original =
                new HttpException(400, "bad", "body", HttpHeaders.of("X-A", "b"), HttpMethod.GET, REQUEST_URI);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (final ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        final HttpException copy;
        try (final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (HttpException) in.readObject();
        }

        assertThat(copy.headers()).isEqualTo(HttpHeaders.empty());
        assertThat(copy.statusCode()).isEqualTo(400);
    }

    @Test
    void from_shouldTruncateMessageButKeepRawBody_whenBodyIsLong() {
        final String body = "x".repeat(5000);

        final HttpException exception = from(body.getBytes(StandardCharsets.UTF_8), null);

        assertSoftly(softly -> {
            softly.assertThat(exception.getMessage()).hasSize(1024 + "... [truncated]".length());
            softly.assertThat(exception.getMessage()).startsWith("xxx").endsWith("... [truncated]");
            softly.assertThat(exception.rawBody()).contains(body);
        });
    }

    @Test
    void from_shouldNotSplitSurrogatePair_whenTruncating() {
        final String body = "x".repeat(1023) + "😀" + "tail";

        final HttpException exception = from(body.getBytes(StandardCharsets.UTF_8), null);

        assertThat(exception.getMessage()).isEqualTo("x".repeat(1023) + "... [truncated]");
    }

    @Test
    void from_shouldKeepMessage_whenBodyIsExactlyAtLimit() {
        final String body = "y".repeat(1024);

        final HttpException exception = from(body.getBytes(StandardCharsets.UTF_8), null);

        assertThat(exception).hasMessage(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=ISO-8859-1", "text/plain; CHARSET=\"iso-8859-1\""})
    void from_shouldDecodeWithResponseCharset_whenContentTypeNamesOne(final String contentType) {
        final HttpException exception = from("café".getBytes(StandardCharsets.ISO_8859_1), contentType);

        assertThat(exception).hasMessage("café");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=no-such-charset", "text/plain; charset=bad name!", "text/plain"})
    void from_shouldDecodeAsUtf8_whenCharsetUnknownOrAbsent(final String contentType) {
        final HttpException exception = from("café".getBytes(StandardCharsets.UTF_8), contentType);

        assertThat(exception).hasMessage("café");
    }

    @Test
    void rawBody_shouldBeCapped_whenBodyIsLongerThanLimit() {
        final String body = "x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS + 10);

        final HttpException exception = new HttpException(500, "bad", body, null, null, null);

        assertThat(exception.rawBody()).contains("x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS));
    }

    @Test
    void rawBody_shouldNotSplitSurrogatePair_whenCapFallsInsideOne() {
        final String body = "x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS - 1) + "😀" + "tail";

        final HttpException exception = new HttpException(500, "bad", body, null, null, null);

        assertThat(exception.rawBody()).contains("x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS - 1));
    }

    @Test
    void decodeBounded_shouldDecodeOnlyTheLeadingCharacters_whenBodyIsHuge() {
        final byte[] bytes = "ab".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS).getBytes(StandardCharsets.UTF_8);

        final String text = HttpErrorBodies.decodeBounded(bytes, StandardCharsets.UTF_8);

        assertThat(text).hasSize(HttpErrorBodies.MAX_RAW_BODY_CHARS).startsWith("abab");
    }

    @Test
    void decodeBounded_shouldNotSplitSurrogatePair_whenCapFallsInsideOne() {
        final String body = "x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS - 1) + "😀" + "tail";

        final String text =
                HttpErrorBodies.decodeBounded(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS - 1));
    }

    @Test
    void decodeBounded_shouldReturnEmptyText_whenBodyIsEmpty() {
        assertThat(HttpErrorBodies.decodeBounded(new byte[0], StandardCharsets.UTF_8))
                .isEmpty();
    }

    @Test
    void decodeBounded_shouldDecodeMultiByteCharset_whenBodyIsShort() {
        final byte[] bytes = "héllo".getBytes(StandardCharsets.UTF_16BE);

        assertThat(HttpErrorBodies.decodeBounded(bytes, StandardCharsets.UTF_16BE))
                .isEqualTo("héllo");
    }

    @Test
    void decodeBounded_shouldReplace_whenBytesAreMalformed() {
        final String text = HttpErrorBodies.decodeBounded(new byte[] {'a', (byte) 0xC3, 'b'}, StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("a" + (char) 0xFFFD + "b");
    }

    private static HttpException from(final byte[] body, final String contentType) {
        final HttpHeaders headers =
                contentType == null ? HttpHeaders.empty() : HttpHeaders.of(CONTENT_TYPE, contentType);
        return HttpException.from(HttpMethod.GET, new HttpResponse(500, headers, HttpBody.of(body)), REQUEST_URI);
    }
}
