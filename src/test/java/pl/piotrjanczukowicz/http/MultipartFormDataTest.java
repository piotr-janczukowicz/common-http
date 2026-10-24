package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class MultipartFormDataTest {

    private static final String BOUNDARY = "xyz";

    @Test
    void build_shouldEncodeTextAndFileParts_whenBoundaryGiven() {
        final MultipartFormData.Builder builder = MultipartFormData.builder()
                .text("password", "p4ss")
                .file("license", "enterprise.ghl", "application/octet-stream", "LIC".getBytes(StandardCharsets.UTF_8));

        final MultipartFormData form = builder.build(BOUNDARY);

        assertSoftly(softly -> {
            softly.assertThat(form.boundary()).isEqualTo(BOUNDARY);
            softly.assertThat(form.contentType()).isEqualTo("multipart/form-data; boundary=\"xyz\"");
            softly.assertThat(text(form)).isEqualTo("""
                            --xyz\r
                            Content-Disposition: form-data; name="password"\r
                            \r
                            p4ss\r
                            --xyz\r
                            Content-Disposition: form-data; name="license"; filename="enterprise.ghl"\r
                            Content-Type: application/octet-stream\r
                            \r
                            LIC\r
                            --xyz--\r
                            """);
        });
    }

    @Test
    void contentType_shouldQuoteTheBoundary_whenItHoldsSeparators() {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("a", "b");

        final MultipartFormData form = builder.build("a=b/c:d");

        assertSoftly(softly -> {
            softly.assertThat(form.contentType()).isEqualTo("multipart/form-data; boundary=\"a=b/c:d\"");
            softly.assertThat(text(form)).startsWith("--a=b/c:d\r\n").endsWith("--a=b/c:d--\r\n");
        });
    }

    @Test
    void text_shouldEncodeUtf8_whenValueHasNonAsciiCharacters() {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("note", "zażółć");

        final MultipartFormData form = builder.build(BOUNDARY);

        assertThat(text(form)).contains("zażółć");
    }

    @Test
    void build_shouldPercentEncodeQuotesAndLineBreaks_whenNamesHaveThem() {
        final MultipartFormData.Builder builder =
                MultipartFormData.builder().file("a\"b", "c\r\nd.txt", "text/plain", new byte[] {1});

        final MultipartFormData form = builder.build(BOUNDARY);

        assertThat(text(form)).contains("name=\"a%22b\"; filename=\"c%0D%0Ad.txt\"");
    }

    @Test
    void file_shouldCopyTheContent_whenCallerChangesTheArrayAfterwards() {
        final byte[] content = {'a'};
        final MultipartFormData.Builder builder = MultipartFormData.builder().file("f", "f.bin", "text/plain", content);
        content[0] = 'z';

        final MultipartFormData form = builder.build(BOUNDARY);

        assertThat(text(form)).contains("\r\n\r\na\r\n");
    }

    @Test
    void build_shouldUseAFreshBoundary_whenBuiltTwice() {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("a", "b");

        final MultipartFormData first = builder.build();
        final MultipartFormData second = builder.build();

        assertSoftly(softly -> {
            softly.assertThat(first.boundary()).isNotEqualTo(second.boundary());
            softly.assertThat(first.contentType()).endsWith("\"" + first.boundary() + "\"");
            softly.assertThat(text(first)).startsWith("--" + first.boundary() + "\r\n");
        });
    }

    @Test
    void build_shouldThrow_whenNoPartAdded() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("at least one part");
    }

    @Test
    void buildWithBoundary_shouldThrow_whenNoPartAdded() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.build(BOUNDARY));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("at least one part");
    }

    @Test
    void buildWithBoundary_shouldThrow_whenBoundaryNull() {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("a", "b");

        final Throwable thrown = catchThrowable(() -> builder.build(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("boundary");
    }

    @ParameterizedTest
    @MethodSource("invalidBoundaries")
    void buildWithBoundary_shouldThrow_whenBoundaryNotValid(final String boundary) {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("a", "b");

        final Throwable thrown = catchThrowable(() -> builder.build(boundary));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("boundary");
    }

    @Test
    void buildWithBoundary_shouldThrow_whenBoundaryOccursInAPart() {
        final MultipartFormData.Builder builder = MultipartFormData.builder().text("a", "--xyz");

        final Throwable thrown = catchThrowable(() -> builder.build(BOUNDARY));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurs in a part");
    }

    @Test
    void text_shouldThrow_whenNameBlank() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.text(" ", "value"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    void text_shouldThrow_whenValueNull() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.text("name", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void file_shouldThrow_whenContentNull() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.file("f", "f.bin", "text/plain", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("content");
    }

    @Test
    void file_shouldThrow_whenFilenameBlank() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.file("f", " ", "text/plain", new byte[0]));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filename");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain\r\nX: y", "text/plain\n"})
    void file_shouldThrow_whenContentTypeHasALineBreak(final String contentType) {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.file("f", "f.bin", contentType, new byte[0]));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line break");
    }

    @Test
    void file_shouldThrow_whenContentTypeBlank() {
        final MultipartFormData.Builder builder = MultipartFormData.builder();

        final Throwable thrown = catchThrowable(() -> builder.file("f", "f.bin", " ", new byte[0]));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contentType");
    }

    private static Stream<String> invalidBoundaries() {
        return Stream.of("", "has space", "semi;colon", "quote\"", "a".repeat(71));
    }

    private static String text(final MultipartFormData form) {
        return form.body().mapBytes(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> "");
    }
}
