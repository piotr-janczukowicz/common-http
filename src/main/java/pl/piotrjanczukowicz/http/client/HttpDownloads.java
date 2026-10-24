package pl.piotrjanczukowicz.http.client;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtLeast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.util.BoundedReadLimitException;
import pl.piotrjanczukowicz.util.IoUtils;

/** Writes a streamed body to a file without ever exposing a partial result. */
final class HttpDownloads {

    private static final Logger LOG = LoggerFactory.getLogger(HttpDownloads.class);

    private static final String TEMP_PREFIX = ".download-";
    private static final String TEMP_SUFFIX = ".part";
    private static final long UNLIMITED = Long.MAX_VALUE;

    private HttpDownloads() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Copies {@code body} to a temporary file next to {@code target}, then moves it onto {@code target}, atomically
     * where the file system can and otherwise by a plain replacing move. The temporary file is flushed to disk before
     * the move, so a crash cannot leave a truncated {@code target}. The temporary file is deleted when anything fails;
     * a failure to delete it is added to the exception as a suppressed one. Where the file system has POSIX permissions
     * the file is created owner-only ({@code rw-------}) and keeps them on {@code target}, because a download may be a
     * private archive; elsewhere it gets the default permissions of a new file.
     *
     * @param body stream to drain; not closed
     * @param target final file
     * @return {@code target}
     * @throws IllegalArgumentException if {@code target} has no parent directory
     * @throws FileAlreadyExistsException if {@code target} is a directory, which a replacing move would otherwise
     *     replace when it is empty
     * @throws IOException if the copy or the move fails
     */
    static Path toFile(final InputStream body, final Path target) throws IOException {
        return toFile(body, target, UNLIMITED);
    }

    /**
     * Like {@link #toFile(InputStream, Path)}, but refuses a body larger than {@code maxBytes}: the copy stops at the
     * first byte over the limit, the temporary file is deleted, and {@code target} is not touched.
     *
     * @param body stream to drain; not closed
     * @param target final file
     * @param maxBytes most bytes accepted, zero or more
     * @return {@code target}
     * @throws ResponseTooLargeException if the body is larger than {@code maxBytes}
     * @throws IllegalArgumentException if {@code maxBytes} is negative, or {@code target} has no parent directory
     * @throws IOException if the copy or the move fails
     */
    static Path toFile(final InputStream body, final Path target, final long maxBytes) throws IOException {
        return toFile(body, target, HttpDownloads::moveAtomically, requireAtLeast(maxBytes, 0L, "maxBytes"));
    }

    static Path toFile(final InputStream body, final Path target, final AtomicMover atomicMover) throws IOException {
        return toFile(body, target, atomicMover, UNLIMITED);
    }

    private static Path toFile(
            final InputStream body, final Path target, final AtomicMover atomicMover, final long maxBytes)
            throws IOException {
        final Path directory = target.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IllegalArgumentException("target must be a file, not a root: " + target);
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString(), null, "target is a directory");
        }
        // The JDK creates a temporary file owner-only (rw-------) where the file system has POSIX permissions, in one
        // step, so the content is never readable by others, not even briefly.
        final Path partialFile = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            try (OutputStream out = Files.newOutputStream(partialFile)) {
                copyBounded(body, out, maxBytes);
            }
            flushToDisk(partialFile);
            moveOntoTarget(partialFile, target, atomicMover);
        } catch (final IOException | RuntimeException failure) {
            IoUtils.closeSuppressing(() -> Files.deleteIfExists(partialFile), failure);
            throw failure;
        }
        return target;
    }

    private static void copyBounded(final InputStream body, final OutputStream out, final long maxBytes)
            throws IOException {
        try {
            IoUtils.copyBounded(body, out, maxBytes);
        } catch (final BoundedReadLimitException limit) {
            final ResponseTooLargeException tooLarge = new ResponseTooLargeException(limit.limit());
            tooLarge.initCause(limit);
            throw tooLarge;
        }
    }

    // Without this the move can reach the disk before the data does, and a crash leaves an empty or short target.
    private static void flushToDisk(final Path partialFile) throws IOException {
        try (FileChannel channel = FileChannel.open(partialFile, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    // The temporary file is next to the target, so the move stays on one file store and can be atomic.
    private static void moveOntoTarget(final Path partialFile, final Path target, final AtomicMover atomicMover)
            throws IOException {
        try {
            atomicMover.move(partialFile, target);
        } catch (final AtomicMoveNotSupportedException notAtomic) {
            LOG.atDebug()
                    .setMessage("The file store of {} has no atomic move, replacing the target instead")
                    .addArgument(target)
                    .log();
            Files.move(partialFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveAtomically(final Path from, final Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Moves a finished file onto its target, replacing it, in one atomic step. */
    @FunctionalInterface
    interface AtomicMover {
        void move(Path from, Path to) throws IOException;
    }
}
