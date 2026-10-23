package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.NonRetryableIOException;

/** Adapts an {@link HttpBody} to an OkHttp {@link RequestBody}. */
final class OkHttpRequestBodies {

    private OkHttpRequestBodies() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * OkHttp body for {@code request}.
     *
     * @param request request whose effective body is sent
     * @return body, or {@code null} when a GET, HEAD or OPTIONS request carries none; a write without a body gets an
     *     empty one so that it can be marked one-shot
     * @throws NonRetryableIOException if a file body cannot be read
     */
    static @Nullable RequestBody create(final HttpRequest request) throws NonRetryableIOException {
        final HttpBody body = request.effectiveBody();
        final RequestBody created =
                switch (body) {
                    case HttpBody.Bytes(final byte[] bytes) -> RequestBody.create(requireNonNull(bytes, "bytes"));
                    case HttpBody.FileBody(final Path path) -> fileRequestBody(path);
                    case HttpBody.StreamBody(final Supplier<InputStream> source, final long contentLength) ->
                        streamRequestBody(source, contentLength);
                    case final HttpBody.Absent ignored -> null;
                };
        if (isReplaySafe(request.method())) {
            return created;
        }
        return new OneShotBody(created == null ? RequestBody.create(new byte[0]) : created);
    }

    // A read can be sent again whatever happened; a write must not be once its bytes may have reached the server.
    private static boolean isReplaySafe(final HttpMethod method) {
        return switch (method) {
            case GET, HEAD, OPTIONS -> true;
            case POST, PUT, PATCH, DELETE -> false;
        };
    }

    private static RequestBody streamRequestBody(final Supplier<InputStream> source, final long contentLength) {
        return new BodyRequestBody(
                () -> {
                    final InputStream stream;
                    try {
                        stream = source.get();
                    } catch (final RuntimeException ex) {
                        throw new NonRetryableIOException("request body source failed", ex);
                    }
                    // A caller-supplied source can return null whatever its declared type says.
                    //noinspection ConstantValue
                    if (stream == null) {
                        throw new NonRetryableIOException("request body source returned null");
                    }
                    return stream;
                },
                contentLength);
    }

    // The size is read once, so Content-Length and the length check agree. A missing or unreadable file is permanent.
    private static RequestBody fileRequestBody(final Path path) throws NonRetryableIOException {
        final long size;
        try {
            size = Files.size(path);
        } catch (final IOException ex) {
            throw new NonRetryableIOException("request body file is not readable", ex);
        }
        return new BodyRequestBody(
                () -> {
                    try {
                        return Files.newInputStream(path);
                    } catch (final IOException ex) {
                        throw new NonRetryableIOException("request body file is not readable", ex);
                    }
                },
                size);
    }

    @FunctionalInterface
    private interface BodyOpener {
        InputStream open() throws IOException;
    }

    /** Request body written from a stream opened for each write; a failing source is not a network failure. */
    private static final class BodyRequestBody extends RequestBody {

        private final BodyOpener opener;
        private final long contentLength;

        BodyRequestBody(final BodyOpener opener, final long contentLength) {
            super();
            this.opener = opener;
            this.contentLength = contentLength;
        }

        @Override
        public @Nullable MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public void writeTo(final BufferedSink sink) throws IOException {
            try (InputStream in = opener.open();
                    Source bodySource = new BodySource(Okio.source(in), contentLength)) {
                sink.writeAll(bodySource);
            }
        }
    }

    /**
     * Reads the caller's stream and reports a failing source, or one that does not match the announced length, as a
     * {@link NonRetryableIOException}, so it is not mistaken for a network failure and replayed.
     */
    private static final class BodySource extends ForwardingSource {

        private final long contentLength;
        private long total;

        BodySource(final Source delegate, final long contentLength) {
            super(delegate);
            this.contentLength = contentLength;
        }

        @Override
        public long read(final Buffer sink, final long byteCount) throws IOException {
            final long read;
            try {
                read = super.read(sink, byteCount);
            } catch (final IOException e) {
                throw new NonRetryableIOException("request body source failed", e);
            }
            if (read == -1) {
                if (total != contentLength) {
                    throw lengthMismatch();
                }
                return read;
            }
            total += read;
            if (total > contentLength) {
                throw lengthMismatch();
            }
            return read;
        }

        private NonRetryableIOException lengthMismatch() {
            return new NonRetryableIOException(
                    "request body length does not match the announced Content-Length of " + contentLength);
        }
    }

    /** Marks a body as sendable once, so OkHttp does not resend it after a failure that may have reached the server. */
    private static final class OneShotBody extends RequestBody {

        private final RequestBody delegate;

        OneShotBody(final RequestBody delegate) {
            super();
            this.delegate = delegate;
        }

        @Override
        public @Nullable MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(final BufferedSink sink) throws IOException {
            delegate.writeTo(sink);
        }
    }
}
