package pl.piotrjanczukowicz.http.client;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.LOCATION;
import static pl.piotrjanczukowicz.util.StringUtils.isBlank;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpErrorBodies;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpResponse;

/**
 * Unsuccessful HTTP API response.
 *
 * <p>Transport failures stay as {@link java.io.IOException}.
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "HttpHeaders is immutable")
public class HttpException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final @Nullable String rawBody;

    @SuppressWarnings("java:S1165")
    //    Field is assigned in readObject method
    private transient HttpHeaders headers;

    private final @Nullable HttpMethod method;
    private final @Nullable URI uri;

    /**
     * Creates an exception for an unsuccessful response.
     *
     * @param statusCode HTTP status
     * @param message failure message
     * @param rawBody response body as text, or {@code null}
     * @param headers response headers; {@code Location} is dropped from a 3xx response, because the target of an
     *     unfollowed redirect can carry a signed token
     * @param method request method, or {@code null}
     * @param uri request URI, or {@code null}
     */
    public HttpException(
            final int statusCode,
            final String message,
            final @Nullable String rawBody,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(message);
        this.statusCode = statusCode;
        this.rawBody = HttpErrorBodies.cappedBody(blankToNull(rawBody));
        this.headers = safeHeaders(statusCode, headers);
        this.method = method;
        this.uri = uri;
    }

    /**
     * Maps {@code response} to an exception. The body is decoded with the {@code charset} of the response
     * {@code Content-Type}, or UTF-8 when it names none or an unknown one. Only the first
     * {@value HttpErrorBodies#MAX_RAW_BODY_CHARS} characters are decoded and kept in {@link #rawBody()}, so a huge
     * error page never becomes a huge string; the message is cut to 1024 characters and ends with an ellipsis marker
     * when it was longer.
     *
     * @param method method of the failed call
     * @param response HTTP response
     * @param requestUri resolved request URI
     * @return exception
     */
    public static HttpException from(final HttpMethod method, final HttpResponse response, final URI requestUri) {
        final Charset charset = response.charset();
        final String body = response.mapBody(bytes -> HttpErrorBodies.decodeBounded(bytes, charset), () -> "");
        final String message =
                blankToNull(body) == null ? "HTTP " + response.statusCode() : HttpErrorBodies.truncated(body);
        return new HttpException(response.statusCode(), message, body, response.headers(), method, requestUri);
    }

    /**
     * HTTP status of the failed response.
     *
     * @return status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Raw response body when it was non-blank, cut to {@value HttpErrorBodies#MAX_RAW_BODY_CHARS} characters.
     *
     * @return body text, or empty
     */
    public Optional<String> rawBody() {
        return Optional.ofNullable(rawBody);
    }

    /**
     * Response headers. They are not part of the serialized form, because they can carry credentials such as
     * {@code Set-Cookie}: after Java serialization this is empty. The status, message, raw body, method and URI do
     * survive.
     *
     * @return headers
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * HTTP method of the failed request.
     *
     * @return method, or empty
     */
    public Optional<HttpMethod> method() {
        return Optional.ofNullable(method);
    }

    /**
     * URI of the failed request. When a followed redirect left the original origin, only
     * {@code scheme://host[:port]path} of the final URI is kept: its query, user info and fragment (often a signed
     * token) are dropped. For a request that stayed on its origin the URI is unchanged.
     *
     * @return URI, or empty
     */
    public Optional<URI> uri() {
        return Optional.ofNullable(uri);
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return isBlank(value) ? null : value;
    }

    private static HttpHeaders safeHeaders(final int statusCode, final @Nullable HttpHeaders headers) {
        if (headers == null) {
            return HttpHeaders.empty();
        }
        return statusCode >= 300 && statusCode < 400 ? headers.without(LOCATION) : headers;
    }

    @Serial
    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        headers = HttpHeaders.empty();
    }
}
