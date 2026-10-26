package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.NonRetryableIOException;
import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link HttpTransport} that sends {@link HttpRequest}s with OkHttp.
 *
 * <p>Timeouts come from {@link HttpTransportConfiguration}: connect, read and write (the response timeout), and an
 * optional overall {@code callTimeout} for one call including the body. {@link #close()} cancels in-flight calls, shuts
 * down the dispatcher's executor, and evicts the connection pool of the client this transport created. The transport
 * never follows redirects. {@link #stream(HttpRequest)} hands out a successful body unbuffered instead. The body is
 * buffered up to {@link HttpTransportConfiguration#maxResponseBytes()}, counted after gzip inflation; more fails with
 * {@link io.github.piotr_janczukowicz.http.ResponseTooLargeException}, immediately and without reading when the
 * response announces a larger {@code Content-Length} (except for HEAD, 1xx, 204, 205 and 304, which carry no body).
 * Request headers and GET/HEAD bodies are validated by {@link HttpHeaders} and {@link HttpRequest}, so invalid input
 * fails before any I/O.
 *
 * <p>Gzip is negotiated transparently: when the caller does not set {@code Accept-Encoding}, OkHttp sends {@code gzip}
 * and inflates a gzip body, dropping {@code Content-Encoding} and {@code Content-Length}. An explicit
 * {@code Accept-Encoding} disables decompression.
 *
 * <p>Connection retries: OkHttp's {@code retryOnConnectionFailure} stays enabled (the default), so a request is retried
 * on another route when no byte of it was sent (a connect failure, the next address of a host) and, for GET, HEAD and
 * OPTIONS, also when it fails on a stale pooled connection. The body of any other method is marked one-shot (a write
 * without a body, such as a plain DELETE, gets an empty one, sent with {@code Content-Length: 0}): OkHttp never sends
 * it again once sending began, because a failure after that point may have been applied by the server and a silent
 * resend would repeat a create or update. That failure surfaces as an {@link IOException}, and any replay is left to
 * the caller or to {@code RetryingHttpTransport}, which applies the method and idempotency rules. The same applies to
 * an HTTP/2 stream the server refuses after the request was sent, and to the retry that OkHttp makes on a 408 response
 * or on a 503 response with {@code Retry-After: 0}: a write is not repeated for any of them.
 *
 * <p>Connection pool: the transport owns a pool of {@link HttpTransportConfiguration#maxIdleConnections()} idle
 * connections (by default the concurrency limit) kept for {@link HttpTransportConfiguration#connectionKeepAlive()}
 * (default 5 minutes), so that more threads than OkHttp's default of 5 idle connections do not reopen connections over
 * HTTP/1.1.
 *
 * <p>Caching: the transport sets no cache.
 *
 * <p>Created only by {@link HttpTransports}, which is the public way to obtain this transport.
 */
final class OkHttpTransport implements HttpTransport {

    private static final String HTTP_CLIENT = "httpClient";
    private static final String CONFIGURATION = "configuration";
    private static final String CLOSED_MESSAGE = "transport closed";

    private final OkHttpClient httpClient;
    private final long maxResponseBytes;
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final Set<Call> openCalls = ConcurrentHashMap.newKeySet();

    OkHttpTransport(final HttpTransportConfiguration configuration) {
        this(
                configuredClient(
                        new OkHttpClient.Builder().connectionPool(connectionPool(configuration)),
                        requireNonNull(configuration, CONFIGURATION)),
                configuration.maxResponseBytes());
    }

    OkHttpTransport(final OkHttpClient httpClient, final long maxResponseBytes) {
        this.httpClient = requireNonNull(httpClient, HTTP_CLIENT);
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Sends {@code request} and returns the HTTP response.
     *
     * @param request request to send
     * @return status, headers, and body
     * @throws IOException if the call fails
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws IllegalArgumentException if the request URI is not an absolute http or https URL
     */
    @Override
    public HttpResponse execute(final HttpRequest request) throws IOException {
        requireNonNull(request, "request");
        throwIfClosed();

        try {
            return send(request);
        } catch (final IOException e) {
            if (isClosed.get()) {
                // A call cancelled by close() surfaces as IOException("Canceled"); report the real cause.
                throw new NonRetryableIOException(CLOSED_MESSAGE, e);
            }
            throw e;
        }
    }

    /**
     * Sends {@code request} and returns the response with its body unread. A 2xx body is not buffered and is not
     * subject to {@link HttpTransportConfiguration#maxResponseBytes()}; closing the response releases the connection.
     * Other responses are buffered like {@link #execute(HttpRequest)}, with the same size limit.
     *
     * @param request request to send
     * @return open response, to be closed by the caller
     * @throws IOException if the call fails before the response headers arrive
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws IllegalArgumentException if the request URI is not an absolute http or https URL
     */
    @Override
    public HttpStreamResponse stream(final HttpRequest request) throws IOException {
        requireNonNull(request, "request");
        throwIfClosed();

        try {
            return openStream(request);
        } catch (final IOException e) {
            if (isClosed.get()) {
                throw new NonRetryableIOException(CLOSED_MESSAGE, e);
            }
            throw e;
        }
    }

    /**
     * Marks this transport closed; every later {@link #execute(HttpRequest)} fails with a
     * {@link NonRetryableIOException}, which {@code RetryingHttpTransport} does not retry. It also cancels every call
     * that has not finished, including the response bodies that {@link #stream(HttpRequest)} handed out and nobody has
     * closed yet (reading one then fails with an {@link IOException}), shuts down the dispatcher's executor, and evicts
     * idle connections. Idempotent.
     */
    @Override
    public void close() {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        openCalls.forEach(Call::cancel);
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private HttpResponse send(final HttpRequest request) throws IOException {
        final Call call = open(request);
        try (Response response = executeOpened(call);
                ResponseBody body = requireNonNull(response.body(), "body")) {
            return bufferedResponse(request, response, body);
        } finally {
            release(call);
        }
    }

    private HttpStreamResponse openStream(final HttpRequest request) throws IOException {
        final Call call = open(request);
        final Response response = executeOpened(call);
        if (!response.isSuccessful()) {
            try (response;
                    ResponseBody body = requireNonNull(response.body(), "body")) {
                return HttpStreamResponse.buffered(bufferedResponse(request, response, body));
            } finally {
                release(call);
            }
        }
        boolean isHandedOver = false;
        try {
            final HttpStreamResponse streamed = streaming(call, response);
            isHandedOver = true;
            return streamed;
        } finally {
            if (!isHandedOver) {
                abandon(call, response);
            }
        }
    }

    // The response never reached the caller, so nobody else can close it or release its call.
    private void abandon(final Call call, final Response response) {
        response.close();
        release(call);
    }

    // The caller now owns the response; closing the body stream releases the connection.
    private HttpStreamResponse streaming(final Call call, final Response response) {
        return HttpStreamResponse.streaming(
                response.code(),
                fromOkHttpHeaders(response.headers()),
                new OnCloseInputStream(requireNonNull(response.body(), "body").byteStream(), () -> release(call)));
    }

    // Registers the call before it starts, so that close() can cancel it; a call registered after close() began never
    // starts.
    private Call open(final HttpRequest request) throws IOException {
        final Call call = httpClient.newCall(newRequest(request));
        openCalls.add(call);
        if (isClosed.get()) {
            openCalls.remove(call);
            throw new NonRetryableIOException(CLOSED_MESSAGE);
        }
        return call;
    }

    // A call that already has its whole response ignores cancel(), so its connection can still go back to the pool
    // after close() evicted; evict again when such a call finishes.
    private void release(final Call call) {
        openCalls.remove(call);
        if (isClosed.get()) {
            httpClient.connectionPool().evictAll();
        }
    }

    private Response executeOpened(final Call call) throws IOException {
        boolean isExecuted = false;
        try {
            final Response response = call.execute();
            isExecuted = true;
            if (!HttpStatus.isValid(response.code())) {
                final int status = response.code();
                response.close();
                release(call);
                throw new NonRetryableIOException("server answered with unsupported HTTP status " + status);
            }
            return response;
        } finally {
            if (!isExecuted) {
                release(call);
            }
        }
    }

    private HttpResponse bufferedResponse(final HttpRequest request, final Response response, final ResponseBody body)
            throws IOException {
        if (isOversizedBodyDeclared(request, response, body)) {
            throw new ResponseTooLargeException(maxResponseBytes);
        }
        // The length is only a sizing hint for the buffer; it is unknown (-1) for a gzip body and ignored when the
        // response carries no body.
        final long expectedLength = hasResponseBody(request, response) ? body.contentLength() : -1;
        final byte[] bytes = BoundedBodyReaders.readAll(body.byteStream(), maxResponseBytes, expectedLength);
        return new HttpResponse(response.code(), fromOkHttpHeaders(response.headers()), HttpBody.of(bytes));
    }

    private static Request newRequest(final HttpRequest request) throws IOException {
        return new Request.Builder()
                .url(request.url().toOkHttpUrl())
                .headers(toOkHttpHeaders(request.headers()))
                .method(request.method().name(), OkHttpRequestBodies.create(request))
                .build();
    }

    private void throwIfClosed() throws NonRetryableIOException {
        if (isClosed.get()) {
            throw new NonRetryableIOException(CLOSED_MESSAGE);
        }
    }

    private boolean isOversizedBodyDeclared(
            final HttpRequest request, final Response response, final ResponseBody body) {
        return body.contentLength() > maxResponseBytes && hasResponseBody(request, response);
    }

    // A HEAD response, 204, 205 and 304 may announce a Content-Length but have no body.
    private static boolean hasResponseBody(final HttpRequest request, final Response response) {
        final int code = response.code();
        return request.method() != HttpMethod.HEAD
                && code >= HttpStatus.OK.code()
                && code != HttpStatus.NO_CONTENT.code()
                && code != HttpStatus.RESET_CONTENT.code()
                && code != HttpStatus.NOT_MODIFIED.code();
    }

    private static ConnectionPool connectionPool(final HttpTransportConfiguration configuration) {
        return new ConnectionPool(
                configuration.maxIdleConnections(),
                configuration.connectionKeepAlive().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    // Timeouts, redirects and network only: the pool is set before this.
    private static OkHttpClient configuredClient(
            final OkHttpClient.Builder builder, final HttpTransportConfiguration configuration) {
        OkHttpNetworks.apply(builder, configuration.network());
        return builder.connectTimeout(configuration.connectTimeout())
                .readTimeout(configuration.responseTimeout())
                .writeTimeout(configuration.responseTimeout())
                .callTimeout(configuration.callTimeout())
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private static Headers toOkHttpHeaders(final HttpHeaders headers) {
        requireNonNull(headers, "headers");
        if (headers.isEmpty()) {
            return Headers.EMPTY;
        }
        return Headers.of(headers.asMap());
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // one list per distinct name
    private static HttpHeaders fromOkHttpHeaders(final Headers headers) {
        requireNonNull(headers, "headers");
        // Headers.toMultimap() would lowercase the names, and values(name) per name rescans all headers. One indexed
        // pass keeps the first-seen casing and the wire order of repeated names.
        final Map<String, List<String>> wire = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final int size = headers.size();
        for (int i = 0; i < size; i++) {
            wire.computeIfAbsent(headers.name(i), name -> new ArrayList<>(2)).add(headers.value(i));
        }
        return HttpHeaders.fromWire(wire);
    }
}
