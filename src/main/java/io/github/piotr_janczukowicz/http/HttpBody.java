package io.github.piotr_janczukowicz.http;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * HTTP entity body. {@link #of(byte[])} takes ownership of the array; callers must not modify it afterwards.
 *
 * <p>{@link Bytes#bytes()} returns the internal array and must not be modified. {@link Bytes} equality is by content,
 * {@link FileBody} by {@link Path}, {@link StreamBody} by identity.
 */
public sealed interface HttpBody permits HttpBody.Absent, HttpBody.Bytes, HttpBody.FileBody, HttpBody.StreamBody {

    /**
     * Body with no entity bytes.
     *
     * @return an absent body
     */
    static HttpBody absent() {
        return new Absent();
    }

    /**
     * Zero-length entity body. Unlike {@link #of(byte[])} with an empty array, this is not {@link #absent()}.
     *
     * @return empty entity
     */
    static HttpBody zeroLength() {
        return new Bytes(new byte[0]);
    }

    /**
     * Body that takes ownership of {@code bytes}, or {@link #absent()} when {@code bytes} is {@code null} or empty. The
     * caller must not modify {@code bytes} afterwards.
     *
     * @param bytes entity bytes, or {@code null}
     * @return present body or {@link #absent()}
     */
    static HttpBody of(final byte @Nullable [] bytes) {
        if (bytes == null || bytes.length == 0) {
            return absent();
        }
        return new Bytes(bytes);
    }

    /**
     * Body that reads {@code file} at send time ({@link Files#size} for {@code Content-Length}).
     *
     * @param file path to send
     * @return file body
     */
    static HttpBody ofFile(final Path file) {
        return new FileBody(requireNonNull(file, "file"));
    }

    /**
     * Body whose {@code source} must return a <em>new</em> stream on every call (retries, and redirects that keep the
     * body: 307 and 308, and 301 and 302 of a PUT, PATCH or DELETE).
     *
     * @param source fresh stream factory
     * @param contentLength known length in bytes; the transport checks that the stream delivers exactly this many
     * @return stream body
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code contentLength} is negative
     */
    static HttpBody ofStream(final Supplier<? extends InputStream> source, final long contentLength) {
        return new StreamBody(requireNonNull(source, StreamBody.SOURCE_ARGUMENT)::get, contentLength);
    }

    /**
     * Whether this body has no entity bytes.
     *
     * @return {@code true} if absent
     */
    boolean isAbsent();

    /**
     * Entity length when known.
     *
     * @return length, or empty when unknown (including a file body whose size cannot be read; the failure then surfaces
     *     as an {@link IOException} when the body is opened or sent)
     */
    OptionalLong length();

    /**
     * Stream over this body. The caller must close it.
     *
     * @return stream
     * @throws IOException if the body cannot be opened
     */
    InputStream openStream() throws IOException;

    /**
     * In-memory entity bytes without a copy, or {@code null} when this is not {@link Bytes}. Does not read file or
     * stream bodies. Prefer {@link #mapBytes} when both cases need handling.
     *
     * @return owned bytes, or {@code null}
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    default byte @Nullable [] bytes() {
        return null;
    }

    /**
     * Maps in-memory {@link Bytes} through {@code present}. {@link Absent}, {@link FileBody}, and {@link StreamBody}
     * use {@code absent} and are never loaded into heap.
     *
     * @param present mapping for in-memory bytes
     * @param absent mapping when there is no in-memory body
     * @param <T> result type
     * @return result of {@code present} or {@code absent}
     * @throws NullPointerException if {@code present} or {@code absent} is {@code null}
     */
    <T> T mapBytes(Function<byte[], T> present, Supplier<T> absent);

    private static <T> T withoutBytes(final Function<byte[], T> present, final Supplier<T> absent) {
        requireNonNull(present, "present");
        return requireNonNull(absent, "absent").get();
    }

    /** No entity. */
    record Absent() implements HttpBody {

        @Override
        public boolean isAbsent() {
            return true;
        }

        @Override
        public OptionalLong length() {
            return OptionalLong.empty();
        }

        @Override
        public InputStream openStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public <T> T mapBytes(final Function<byte[], T> present, final Supplier<T> absent) {
            return withoutBytes(present, absent);
        }
    }

    /**
     * In-memory body. {@link #bytes()} is the owned array and must not be modified.
     *
     * @param bytes owned entity bytes
     */
    record Bytes(byte[] bytes) implements HttpBody {

        public Bytes {
            requireNonNull(bytes, "bytes");
        }

        @Override
        public boolean isAbsent() {
            return false;
        }

        @Override
        public OptionalLong length() {
            return OptionalLong.of(bytes.length);
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public <T> T mapBytes(final Function<byte[], T> present, final Supplier<T> absent) {
            requireNonNull(absent, "absent");
            return requireNonNull(present, "present").apply(bytes);
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            return this == other || other instanceof Bytes(final byte[] otherBytes) && Arrays.equals(bytes, otherBytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        /** Length only: bodies may carry secrets and are often large, so contents are never printed. */
        @Override
        public String toString() {
            return "Bytes[length=" + bytes.length + "]";
        }
    }

    /**
     * File body. Equality is {@link Path#equals(Object)}.
     *
     * @param path file to send
     */
    record FileBody(Path path) implements HttpBody {

        public FileBody {
            requireNonNull(path, "path");
        }

        @Override
        public boolean isAbsent() {
            return false;
        }

        @Override
        public OptionalLong length() {
            try {
                return OptionalLong.of(Files.size(path));
            } catch (final IOException unused) {
                // A missing or unreadable file fails with an IOException when it is opened or sent.
                return OptionalLong.empty();
            }
        }

        @Override
        public InputStream openStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public <T> T mapBytes(final Function<byte[], T> present, final Supplier<T> absent) {
            return withoutBytes(present, absent);
        }
    }

    /**
     * Repeatable stream body. Equality is identity. {@code source} must open a new stream each time; a source that
     * returns {@code null} or throws fails {@link #openStream()} with an {@link IOException}.
     *
     * @param source fresh stream factory
     * @param contentLength known length, not negative
     */
    record StreamBody(Supplier<InputStream> source, long contentLength) implements HttpBody {

        static final String SOURCE_ARGUMENT = "source";

        public StreamBody {
            requireNonNull(source, SOURCE_ARGUMENT);
            requireAtLeast(contentLength, 0L, "contentLength");
        }

        @Override
        public boolean isAbsent() {
            return false;
        }

        @Override
        public OptionalLong length() {
            return OptionalLong.of(contentLength);
        }

        @Override
        public InputStream openStream() throws IOException {
            final InputStream stream;
            try {
                stream = source.get();
            } catch (final RuntimeException ex) {
                throw new IOException("stream body source failed", ex);
            }
            // A caller-supplied source can return null whatever its declared type says.
            //noinspection ConstantValue
            if (stream == null) {
                throw new IOException("stream body source returned null");
            }
            return stream;
        }

        @Override
        public <T> T mapBytes(final Function<byte[], T> present, final Supplier<T> absent) {
            return withoutBytes(present, absent);
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
