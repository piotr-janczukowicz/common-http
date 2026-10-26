package io.github.piotr_janczukowicz.http.client;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.io.Serial;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * A successful response that carries no value to decode: no body, for example HTTP 202 from an endpoint still being
 * computed or HTTP 204. The JSON literal {@code null} is not this case: the default codec fails with an
 * {@link IllegalStateException} for it.
 *
 * <p>Thrown by {@link AbstractHttpCaller#fetch(HttpCall, Class)}, {@link AbstractHttpCaller#fetchOrEmpty(HttpCall,
 * Class)} and the {@link HttpBodyDecoder} that {@link AbstractHttpCaller#fetchByStatus} passes to its functions. A
 * caller that expects such answers uses {@link AbstractHttpCaller#fetchOptional(HttpCall, Class)}, which returns an
 * empty result instead. {@link #statusCode()} is the status of the response, which was not an error.
 */
public class HttpEmptyResponseException extends HttpException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a response without a value.
     *
     * @param statusCode HTTP status of the response
     * @param message what was empty, naming the method and target without query
     * @param headers response headers
     * @param method request method, or {@code null}
     * @param uri request URI without query, or {@code null}
     */
    public HttpEmptyResponseException(
            final int statusCode,
            final String message,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, message, null, headers, method, uri);
    }
}
