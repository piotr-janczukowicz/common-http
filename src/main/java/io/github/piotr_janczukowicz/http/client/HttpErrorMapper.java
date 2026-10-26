package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Maps an unsuccessful HTTP response to an unchecked exception. */
@FunctionalInterface
public interface HttpErrorMapper {

    /**
     * Maps {@code response} to the failure of a call made with {@code method}.
     *
     * @param method method of the call
     * @param response HTTP response
     * @param requestUri resolved request URI
     * @return exception to throw
     */
    RuntimeException map(HttpMethod method, HttpResponse response, URI requestUri);

    /**
     * Maps a redirect-policy failure. The {@code Location} header is left out of the exception's headers because it
     * often carries signed tokens.
     *
     * @param response redirect or last response
     * @param message failure message
     * @param rawBody body or Location text, or {@code null}
     * @param method original method, or {@code null}
     * @param uri current request URI
     * @return exception to throw
     */
    default RuntimeException redirectFailure(
            final HttpResponse response,
            final String message,
            final @Nullable String rawBody,
            final @Nullable HttpMethod method,
            final URI uri) {
        return new HttpException(
                response.statusCode(), message, rawBody, response.headers().without(LOCATION), method, uri);
    }
}
