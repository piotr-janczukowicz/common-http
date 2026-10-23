package pl.piotrjanczukowicz.http;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An inbound HTTP response whose body is read from a stream instead of being held in memory. The caller must
 * {@link #close()} it, which releases the connection even when the body was not read to its end.
 *
 * <p>A transport streams only the body of a successful (2xx) response: {@link #isStreaming()} is then {@code true} and
 * {@link #body()} reads from the network, with no size limit. Any other response is read into memory (bounded like
 * {@link HttpResponse} bodies) so that it can be mapped to an error or inspected for a retry or redirect:
 * {@link #isStreaming()} is {@code false}, {@link #body()} reads the buffered bytes and {@link #bufferedResponse()}
 * returns the response.
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "HttpHeaders is immutable")
public final class HttpStreamResponse implements AutoCloseable {

    private final int statusCode;
    private final HttpHeaders headers;
    private final InputStream body;
    private final @Nullable HttpResponse buffered;

    private HttpStreamResponse(
            final int statusCode,
            final HttpHeaders headers,
            final InputStream body,
            final @Nullable HttpResponse buffered) {
        HttpStatus.requireValid(statusCode);
        this.statusCode = statusCode;
        this.headers = requireNonNull(headers, "headers");
        this.body = requireNonNull(body, "body");
        this.buffered = buffered;
    }

    /**
     * Response whose {@code body} is read from the network. Closing the response closes {@code body}.
     *
     * @param statusCode HTTP status code
     * @param headers response headers
     * @param body live body stream
     * @return streaming response
     * @throws NullPointerException if {@code headers} or {@code body} is {@code null}
     */
    public static HttpStreamResponse streaming(
            final int statusCode, final HttpHeaders headers, final InputStream body) {
        return new HttpStreamResponse(statusCode, headers, body, null);
    }

    /**
     * Response backed by an in-memory {@code response}.
     *
     * @param response buffered response
     * @return non-streaming response over the same status, headers and body
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static HttpStreamResponse buffered(final HttpResponse response) {
        requireNonNull(response, "response");
        final InputStream bytes = response.mapBody(ByteArrayInputStream::new, InputStream::nullInputStream);
        return new HttpStreamResponse(response.statusCode(), response.headers(), bytes, response);
    }

    /**
     * HTTP status code.
     *
     * @return status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Response headers.
     *
     * @return headers
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * First non-blank value of header {@code name}, trimmed.
     *
     * @param name header name
     * @return header value, or empty
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<String> header(final String name) {
        requireNonNull(name, "name");
        return headers.get(name).map(String::trim).filter(value -> !value.isEmpty());
    }

    /**
     * Whether {@link #statusCode()} is HTTP 2xx.
     *
     * @return {@code true} if successful
     */
    public boolean isSuccessful() {
        return statusCode / 100 == 2;
    }

    /**
     * Whether {@link #body()} reads from the network rather than from memory.
     *
     * @return {@code true} when the body is streamed
     */
    public boolean isStreaming() {
        return buffered == null;
    }

    /**
     * Body stream, to be read once. Closing it, or this response, ends the exchange.
     *
     * @return body stream; empty when the response has no body
     */
    public InputStream body() {
        return body;
    }

    /**
     * The in-memory form of a response that was not streamed.
     *
     * @return buffered response
     * @throws IllegalStateException if this response is streaming
     */
    public HttpResponse bufferedResponse() {
        if (buffered == null) {
            throw new IllegalStateException("HTTP " + statusCode + " response is streamed, not buffered");
        }
        return buffered;
    }

    /**
     * Closes the body stream and releases the connection.
     *
     * @throws IOException if closing the stream fails
     */
    @Override
    public void close() throws IOException {
        body.close();
    }
}
