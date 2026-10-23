package pl.piotrjanczukowicz.http;

import java.io.IOException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * An I/O failure that repeating the call cannot fix: a request body source (stream or file) that was missing, failed,
 * returned nothing or announced the wrong length, or a transport that was closed. Never retried by
 * {@code RetryingHttpTransport}.
 */
public final class NonRetryableIOException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Failure with {@code message} and no cause.
     *
     * @param message description of the failure
     */
    public NonRetryableIOException(final String message) {
        super(message);
    }

    /**
     * Failure with {@code message} caused by {@code cause}.
     *
     * @param message description of the failure
     * @param cause underlying failure, if any
     */
    public NonRetryableIOException(final String message, final @Nullable Throwable cause) {
        super(message, cause);
    }
}
