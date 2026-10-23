package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpBodyTest {

    @Test
    void absent_shouldHaveNoBytes_whenCalled() throws Exception {
        final HttpBody body = HttpBody.absent();

        assertSoftly(softly -> {
            softly.assertThat(body.isAbsent()).isTrue();
            softly.assertThat(body.bytes()).isNull();
            softly.assertThat(body.length()).isEmpty();
        });
        assertThat(body.openStream().readAllBytes()).isEmpty();
    }

    @Test
    void of_shouldReturnAbsent_whenNullOrEmpty() {
        final HttpBody nullBody = HttpBody.of(null);
        final HttpBody emptyBody = HttpBody.of(new byte[0]);

        assertSoftly(softly -> {
            softly.assertThat(nullBody).isEqualTo(HttpBody.absent());
            softly.assertThat(emptyBody).isEqualTo(HttpBody.absent());
        });
    }

    @Test
    void of_shouldTakeOwnership_whenSourceMutated() {
        final byte[] source = {1, 2};
        final HttpBody body = HttpBody.of(source);
        source[0] = 9;

        assertSoftly(softly -> {
            softly.assertThat(body.isAbsent()).isFalse();
            softly.assertThat(body.bytes()).containsExactly(9, 2);
            softly.assertThat(body.bytes()).isSameAs(source);
            softly.assertThat(body.length()).hasValue(2);
        });
    }

    @Test
    void ofFile_shouldReadPath_whenOpened(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("asset.bin");
        Files.write(file, new byte[] {4, 5});
        final HttpBody body = HttpBody.ofFile(file);

        final byte[] fromFile = body.openStream().readAllBytes();

        assertSoftly(softly -> {
            softly.assertThat(body).isInstanceOf(HttpBody.FileBody.class);
            softly.assertThat(body.isAbsent()).isFalse();
            softly.assertThat(body.length()).hasValue(2);
            softly.assertThat(body.bytes()).isNull();
            softly.assertThat(fromFile).containsExactly(4, 5);
            softly.assertThat(HttpBody.ofFile(file)).isEqualTo(body);
        });
    }

    @Test
    void ofStream_shouldThrow_whenContentLengthNegative() {
        final Throwable thrown =
                catchThrowable(() -> HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[0]), -1));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contentLength");
    }

    @Test
    void streamBody_shouldThrow_whenContentLengthNegative() {
        final Supplier<InputStream> source = InputStream::nullInputStream;

        final Throwable thrown = catchThrowable(() -> new HttpBody.StreamBody(source, -1));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("contentLength must be at least 0");
    }

    @Test
    void ofStream_shouldAccept_whenContentLengthZero() {
        final HttpBody body = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[0]), 0);

        assertThat(body.length()).hasValue(0);
    }

    @Test
    void ofStream_shouldUseIdentityEquality_whenCompared() throws Exception {
        final HttpBody first = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {1}), 1);
        final HttpBody sameFactory = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {1}), 1);
        final HttpBody.StreamBody stream = (HttpBody.StreamBody) first;

        final byte[] streamed = first.openStream().readAllBytes();
        final byte[] fromSource = stream.source().get().readAllBytes();

        assertSoftly(softly -> {
            softly.assertThat(fromSource).containsExactly(1);
            softly.assertThat(stream.contentLength()).isEqualTo(1);
            softly.assertThat(streamed).containsExactly(1);
            softly.assertThat(first).isEqualTo(first).isNotEqualTo(sameFactory);
            softly.assertThat(first.length()).hasValue(1);
            softly.assertThat(first.isAbsent()).isFalse();
            softly.assertThat(first.bytes()).isNull();
            softly.assertThat(first.hashCode()).isEqualTo(System.identityHashCode(first));
        });
    }

    @Test
    void mapBytes_shouldInvokeCorrectHandler_whenBodyPresentOrAbsent() {
        final byte[] source = {1};
        final HttpBody body = HttpBody.of(source);
        final int presentValue = body.mapBytes(
                bytes -> {
                    bytes[0] = 9;
                    return (int) bytes[0];
                },
                () -> -1);
        final int absentValue = HttpBody.absent().mapBytes(unused -> 1, () -> 0);
        final int fileValue = HttpBody.ofFile(Path.of("unused.bin")).mapBytes(unused -> 1, () -> 0);
        final int streamValue = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {1}), 1)
                .mapBytes(unused -> 1, () -> 0);

        assertSoftly(softly -> {
            softly.assertThat(presentValue).isEqualTo(9);
            softly.assertThat(body.bytes()).containsExactly(9);
            softly.assertThat(absentValue).isZero();
            softly.assertThat(fileValue).isZero();
            softly.assertThat(streamValue).isZero();
        });
    }

    @Test
    void toString_shouldPrintOnlyLength_whenBytesBody() {
        final HttpBody body = HttpBody.of("secret-token".getBytes(StandardCharsets.UTF_8));

        assertThat(body.toString()).isEqualTo("Bytes[length=12]").doesNotContain("secret");
    }

    @Test
    void equals_shouldUseByteContent_whenCompared() {
        final HttpBody first = HttpBody.of(new byte[] {1, 2});
        final HttpBody same = HttpBody.of(new byte[] {1, 2});
        final HttpBody different = HttpBody.of(new byte[] {3});

        assertSoftly(softly -> {
            softly.assertThat(first)
                    .isEqualTo(first)
                    .isEqualTo(same)
                    .hasSameHashCodeAs(same)
                    .isNotEqualTo(different)
                    .hasToString("Bytes[length=2]");
            softly.assertThat(HttpBody.absent()).isEqualTo(HttpBody.absent());
            softly.assertThat(first).isNotEqualTo("not-a-body");
        });
    }

    @Test
    void openStream_shouldReturnBytes_whenBytesBody() throws Exception {
        final HttpBody body = HttpBody.of(new byte[] {7, 8});

        final byte[] streamed = body.openStream().readAllBytes();

        assertThat(streamed).containsExactly(7, 8);
    }

    @Test
    void empty_shouldBeZeroLengthBytes_whenCalled() {
        final HttpBody empty = HttpBody.zeroLength();

        assertSoftly(softly -> {
            softly.assertThat(empty).isInstanceOf(HttpBody.Bytes.class);
            softly.assertThat(empty.isAbsent()).isFalse();
            softly.assertThat(empty.bytes()).isEmpty();
            softly.assertThat(empty.length()).hasValue(0);
        });
    }

    @Test
    void ofFile_shouldThrow_whenPathNull() {
        final Throwable thrown = catchThrowable(() -> HttpBody.ofFile(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("file");
    }

    @Test
    void ofFile_shouldThrowIoOnOpen_whenPathMissing(@TempDir final Path dir) {
        final HttpBody body = HttpBody.ofFile(dir.resolve("missing.bin"));

        final Throwable thrown = catchThrowable(body::openStream);

        assertThat(thrown).isInstanceOf(IOException.class);
    }

    @Test
    void ofStream_shouldThrowUncheckedIo_whenStreamFails() throws IOException {
        final HttpBody body = HttpBody.ofStream(
                () -> new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("boom");
                    }
                },
                1);

        final var openStream = body.openStream();

        final Throwable thrown = catchThrowable(openStream::readAllBytes);

        assertThat(thrown).isInstanceOf(IOException.class);
    }

    @Test
    void ofStream_shouldThrow_whenSourceNull() {
        final Throwable thrown = catchThrowable(() -> HttpBody.ofStream(null, 0));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("source");
    }

    @Test
    void ofStream_shouldThrowIoException_whenSourceReturnsNull() {
        final HttpBody body = HttpBody.ofStream(() -> null, 0);

        final Throwable thrown = catchThrowable(body::openStream);

        assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("returned null");
    }

    @Test
    void ofFile_shouldReportUnknownLength_whenPathMissing(@TempDir final Path dir) {
        final HttpBody body = HttpBody.ofFile(dir.resolve("missing.bin"));

        assertThat(body.length()).isEmpty();
    }

    @Test
    void ofStream_shouldThrowIoException_whenSourceThrows() {
        final HttpBody body = HttpBody.ofStream(
                () -> {
                    throw new IllegalStateException("boom");
                },
                1);

        final Throwable thrown = catchThrowable(body::openStream);

        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessage("stream body source failed")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
