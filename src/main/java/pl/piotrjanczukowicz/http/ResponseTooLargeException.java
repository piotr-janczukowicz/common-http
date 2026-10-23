package pl.piotrjanczukowicz.http;

import java.io.IOException;
import java.io.Serial;

/** The response body exceeded {@code HttpTransportConfiguration.maxResponseBytes()}. Not retried. */
public final class ResponseTooLargeException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long maxBytes;

    /**
     * Failure for a body larger than {@code maxBytes}.
     *
     * @param maxBytes configured limit
     */
    public ResponseTooLargeException(final long maxBytes) {
        super("Response body exceeds the limit of " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    /**
     * Configured limit that was exceeded.
     *
     * @return limit in bytes
     */
    public long maxBytes() {
        return maxBytes;
    }
}
