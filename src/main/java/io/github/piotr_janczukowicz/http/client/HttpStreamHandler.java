package io.github.piotr_janczukowicz.http.client;

import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import java.io.IOException;

/**
 * Consumes the body of a successful streamed response.
 *
 * <p>The stream is open only for the duration of the call and is closed by the caller afterwards; the handler must not
 * keep it. A failure while reading, including a connection that breaks mid-body, surfaces as an {@link IOException} and
 * is not retried.
 *
 * @param <T> result type
 */
@FunctionalInterface
public interface HttpStreamHandler<T> {

    /**
     * Reads {@code response}'s body.
     *
     * @param response successful (2xx) response with a live body; must not be used after this method returns
     * @return handler result
     * @throws IOException if reading or writing fails
     */
    T handle(HttpStreamResponse response) throws IOException;
}
