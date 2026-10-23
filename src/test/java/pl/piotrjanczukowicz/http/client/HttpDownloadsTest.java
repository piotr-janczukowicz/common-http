package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpDownloadsTest {

    @TempDir
    Path tempDir;

    @Test
    void toFile_shouldReplaceTarget_whenCopySucceeds() throws IOException {
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});

        final Path result = HttpDownloads.toFile(new ByteArrayInputStream(new byte[] {1, 2, 3}), target);

        assertThat(result).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
    }

    @Test
    void toFile_shouldThrowAndKeepDirectory_whenTargetIsAnEmptyDirectory() throws IOException {
        final Path target = Files.createDirectory(tempDir.resolve("out"));

        final var stream = new ByteArrayInputStream(new byte[] {1});

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(stream, target));

        assertThat(thrown).isInstanceOf(FileAlreadyExistsException.class).hasMessageContaining("target is a directory");
        assertThat(target).isDirectory();
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void toFile_shouldKeepTargetAndLeaveNoTempFile_whenCopyFails() throws IOException {
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});
        final InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(failing, target));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("boom");
        assertThat(Files.readAllBytes(target)).containsExactly(9);
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void toFile_shouldKeepTargetAndLeaveNoTempFile_whenBodyIsTruncatedMidStream() throws IOException {
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});
        final InputStream truncated =
                new SequenceInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), failing());

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(truncated, target));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("connection lost");
        assertThat(Files.readAllBytes(target)).containsExactly(9);
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void toFile_shouldKeepOriginalFailure_whenTemporaryFileCannotBeDeleted() {
        final Path target = tempDir.resolve("out.bin");
        final IOException moveFailure = new IOException("move failed");
        final HttpDownloads.AtomicMover nonEmptyDirectoryInPlaceOfTemporaryFile = (from, to) -> {
            Files.delete(from);
            Files.createDirectory(from);
            Files.write(from.resolve("child"), new byte[] {1});
            throw moveFailure;
        };

        final var stream = new ByteArrayInputStream(new byte[] {1});

        final Throwable thrown =
                catchThrowable(() -> HttpDownloads.toFile(stream, target, nonEmptyDirectoryInPlaceOfTemporaryFile));

        assertThat(thrown).isSameAs(moveFailure);
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(IOException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void toFile_shouldMoveWithoutAtomicity_whenFileSystemCannotMoveAtomically() throws IOException {
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});
        final HttpDownloads.AtomicMover unsupported = (from, to) -> {
            throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "not supported");
        };

        final Path result = HttpDownloads.toFile(new ByteArrayInputStream(new byte[] {1, 2}), target, unsupported);

        assertThat(result).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2);
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void toFile_shouldCreateOwnerOnlyFile_whenPosix() throws IOException {
        final Path target = tempDir.resolve("out.bin");

        HttpDownloads.toFile(new ByteArrayInputStream(new byte[] {1}), target);

        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void toFile_shouldReplaceWorldReadableTargetWithOwnerOnlyFile_whenPosix() throws IOException {
        final Path target = Files.createFile(
                tempDir.resolve("out.bin"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        HttpDownloads.toFile(new ByteArrayInputStream(new byte[] {1}), target);

        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    private static InputStream failing() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection lost");
            }
        };
    }

    @Test
    void toFile_shouldThrow_whenTargetIsFileSystemRoot() {
        final Path root = tempDir.toAbsolutePath().getRoot();

        final var nullInputStream = InputStream.nullInputStream();

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(nullInputStream, root));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldThrow_whenInvoked() throws NoSuchMethodException {
        final Constructor<HttpDownloads> constructor = HttpDownloads.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toFile_shouldThrowAndLeaveNothing_whenBodyExceedsLimit() {
        final Path target = tempDir.resolve("limited.bin");
        final InputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3, 4});

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(body, target, 3L));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
            softly.assertThat(target).doesNotExist();
            softly.assertThat(tempDir.toFile().list()).isEmpty();
        });
    }

    @Test
    void toFile_shouldWriteBody_whenBodyFitsTheLimit() throws IOException {
        final Path target = tempDir.resolve("fits.bin");

        final Path result = HttpDownloads.toFile(new ByteArrayInputStream(new byte[] {1, 2, 3}), target, 3L);

        assertThat(result).hasBinaryContent(new byte[] {1, 2, 3});
    }

    @Test
    void toFile_shouldThrow_whenLimitNegative() {
        final Path target = tempDir.resolve("negative.bin");
        final InputStream body = new ByteArrayInputStream(new byte[0]);

        final Throwable thrown = catchThrowable(() -> HttpDownloads.toFile(body, target, -1L));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBytes");
    }
}
