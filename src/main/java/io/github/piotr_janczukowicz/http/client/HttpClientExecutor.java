package io.github.piotr_janczukowicz.http.client;

import io.github.piotr_janczukowicz.http.HttpResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Sends {@link HttpCall}s and returns their responses: the entry point that callers one layer up depend on. A call
 * holds only what varies per request; the executor adds what is the same for every request, such as the base URI, the
 * default headers and the authentication.
 *
 * <p>{@link HttpClient} is the generic implementation. A client built on top of it, such as a service-specific client,
 * can implement this interface too, so its callers never reach past it to the generic client.
 */
public interface HttpClientExecutor {

    /**
     * Sends {@code call}, following redirects, and returns the response when it is HTTP 2xx.
     *
     * @param call call to send
     * @return response
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is unsuccessful
     * @throws NullPointerException if {@code call} is {@code null}
     */
    default HttpResponse execute(final HttpCall call) throws IOException {
        return execute(call, Set.of(), true);
    }

    /**
     * Sends {@code call}, following redirects, and returns the response when it is HTTP 2xx or one of
     * {@code expectedStatuses}.
     *
     * @param call call to send
     * @param expectedStatuses extra statuses treated as a completed call, not an error
     * @return response
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is unsuccessful and not in {@code expectedStatuses}
     * @throws NullPointerException if {@code call} or {@code expectedStatuses} is {@code null}
     */
    default HttpResponse execute(final HttpCall call, final Set<Integer> expectedStatuses) throws IOException {
        return execute(call, expectedStatuses, true);
    }

    /**
     * Sends {@code call} and returns the response when it is HTTP 2xx or one of {@code expectedStatuses}. HTTP 304 is
     * an error unless included in {@code expectedStatuses}.
     *
     * @param call call to send
     * @param expectedStatuses extra statuses treated as a completed call, not an error
     * @param followRedirects whether to follow redirects
     * @return response
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is unsuccessful and not in {@code expectedStatuses}
     * @throws NullPointerException if {@code call} or {@code expectedStatuses} is {@code null}
     */
    HttpResponse execute(HttpCall call, Set<Integer> expectedStatuses, boolean followRedirects) throws IOException;

    /**
     * Sends {@code call} and passes the body of a successful (2xx) response to {@code handler} as a stream, without
     * buffering it or applying the transport's response size limit. Origin guard, auth, headers and redirects apply as
     * for {@link #execute(HttpCall, Set, boolean)} with redirects followed; a cross-origin redirect (for example a
     * download that ends on a CDN host) drops the credentials as usual.
     *
     * <p>Retries apply only before the body starts: I/O failures, rate limits and retryable 5xx while the response
     * headers are awaited. Once the handler runs, a failure while reading the body is not retried and surfaces from the
     * handler. The response is closed when the handler returns. An unsuccessful response is read into memory (bounded)
     * and mapped like {@link #execute(HttpCall, Set, boolean)}, without calling the handler.
     *
     * <p>Timeouts: the response size limit does not apply, but a {@code callTimeout} of the transport does. It covers
     * the whole body, so a download that takes longer fails with an {@link java.io.InterruptedIOException} mid-stream,
     * and that is not retried. For large downloads leave {@code callTimeout} at {@link java.time.Duration#ZERO} and
     * rely on {@code responseTimeout}, which only limits the time without data.
     *
     * <p>The handler holds a request slot of a
     * {@link io.github.piotr_janczukowicz.http.transport.ConcurrencyLimitingHttpTransport} until it returns, so do not
     * make other calls through the same client or transport from inside it, not even indirectly through a token source
     * on a shared transport; with the limit exhausted such a call waits for a slot until {@code maxSlotWait} and then
     * fails.
     *
     * @param call call to send
     * @param handler reads the body; must not keep the stream
     * @param <T> handler result type
     * @return the handler's result
     * @throws IOException if the transport fails, or the handler does
     * @throws RuntimeException if the response is unsuccessful
     * @throws NullPointerException if {@code call} or {@code handler} is {@code null}
     */
    <T> T stream(HttpCall call, HttpStreamHandler<T> handler) throws IOException;

    /**
     * Sends {@code call} without following redirects and returns {@code Location} from HTTP 301, 302, 303, 307, or 308.
     *
     * @param call call to send
     * @return redirect target
     * @throws IOException if the transport fails
     * @throws IllegalStateException if the response is successful (2xx) and so is not a redirect
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     */
    URI redirectLocation(HttpCall call) throws IOException;

    /**
     * Like {@link #redirectLocation(HttpCall)}, but a successful (2xx) response is an answer and not an error: it means
     * the endpoint did not redirect, for example because the content is small enough to be sent directly or because the
     * server is still preparing the result (HTTP 202).
     *
     * @param call call to send
     * @return redirect target from HTTP 301, 302, 303, 307, or 308, or empty when the response is successful
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     * @throws NullPointerException if {@code call} is {@code null}
     */
    Optional<URI> redirectLocationIfAny(HttpCall call) throws IOException;

    /**
     * Configured API base URI, with a trailing slash on the path.
     *
     * @return normalised base URI
     */
    URI baseUri();
}
