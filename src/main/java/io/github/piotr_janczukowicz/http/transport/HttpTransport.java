package io.github.piotr_janczukowicz.http.transport;

import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import java.io.IOException;

/**
 * The network layer of the HTTP stack: performs one request/response exchange and owns the connection resources (pools,
 * executors, caches) behind it. It knows nothing about base URIs, authentication, redirects or error mapping, which
 * {@code HttpClientExecutor} and the layers above it add.
 *
 * <p>{@link #execute(HttpRequest)} reads the whole response into memory, and {@link #stream} hands out the body of a
 * successful response unread. Decorators such as {@code RetryingHttpTransport} wrap another transport and add behavior
 * around the exchange. {@link #close()} releases the resources the implementation owns.
 */
public sealed interface HttpTransport extends AutoCloseable
        permits ConcurrencyLimitingHttpTransport,
                ConditionalCachingHttpTransport,
                MutationPacingHttpTransport,
                RetryingHttpTransport,
                OkHttpTransport {

    /**
     * Executes {@code request} and returns the HTTP response.
     *
     * <p>Retry contract of the transports in this package (the interface is sealed): {@code RetryingHttpTransport}
     * repeats a request of <em>any</em> method after a {@link java.net.ConnectException} or
     * {@link java.net.NoRouteToHostException}. A transport may throw these only when no byte of the request was sent. A
     * failure after sending began, even a refused or reset connection, must have another type, for example
     * {@link java.net.SocketException} or a plain {@link IOException}.
     *
     * @param request request to send
     * @return status, headers, and body
     * @throws IOException if the exchange fails
     */
    HttpResponse execute(HttpRequest request) throws IOException;

    /**
     * Executes {@code request} and returns the response with its body still to be read. A successful (2xx) body is
     * streamed from the network, without a size limit and without being held in memory; any other response is read into
     * memory first, bounded like {@link #execute}, so that it can be mapped to an error. The caller must close the
     * returned response.
     *
     * <p>Only the exchange up to the response headers can be retried or redirected; once a body is streaming, a failure
     * while reading it surfaces to the reader. An overall call timeout, when configured, also covers the time spent
     * reading the stream.
     *
     * <p>The retry contract of {@link #execute(HttpRequest)} applies: throw {@link java.net.ConnectException} or
     * {@link java.net.NoRouteToHostException} only when no byte of the request was sent.
     *
     * @param request request to send
     * @return open response
     * @throws IOException if the exchange fails before the headers arrive
     */
    HttpStreamResponse stream(HttpRequest request) throws IOException;

    /** Releases resources this transport owns. */
    @Override
    void close();
}
