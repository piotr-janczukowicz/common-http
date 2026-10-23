package pl.piotrjanczukowicz.http.client;

import java.io.Serial;

/** A request target is off the API origin or outside the API base path. */
public final class HttpOriginViolationException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param message what the target violates; never contains a query string */
    public HttpOriginViolationException(final String message) {
        super(message);
    }
}
