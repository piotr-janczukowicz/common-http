package pl.piotrjanczukowicz.http.client;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;

/**
 * A response together with its decoded body, which is absent for HTTP 204, 304 and any response without a body, an HTTP
 * 202 without one included.
 *
 * <p>Use it to read response metadata, such as the {@code ETag} for the next conditional request, next to the value.
 *
 * @param response the HTTP response
 * @param decoded decoded body, or {@code null} when the response carries none; {@link #value()} wraps it
 * @param <T> value type
 */
public record HttpResult<T>(HttpResponse response, @Nullable T decoded) {

    public HttpResult {
        requireNonNull(response, "response");
    }

    /**
     * Decoded body of the response.
     *
     * @return decoded value, or empty when the response carries none
     */
    public Optional<T> value() {
        return Optional.ofNullable(decoded);
    }

    /**
     * Whether the server answered a conditional request with HTTP 304, so the caller's cached copy is current.
     *
     * <p>Sending validators yourself is optional: the client already sends conditional requests from its in-memory
     * cache, and caller-managed ETags are useful only to keep validators across process restarts.
     *
     * @return {@code true} for HTTP 304
     */
    public boolean isNotModified() {
        return response.hasStatus(HttpStatus.NOT_MODIFIED);
    }

    /**
     * {@code ETag} of the response, to send as {@code If-None-Match} next time.
     *
     * <p>Optional: the client already sends conditional requests from its in-memory cache, and caller-managed ETags are
     * useful only to keep validators across process restarts.
     *
     * @return entity tag as sent, including quotes and any {@code W/} prefix, or empty
     */
    public Optional<String> etag() {
        return response.etag();
    }
}
