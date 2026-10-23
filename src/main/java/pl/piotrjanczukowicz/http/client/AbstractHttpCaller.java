package pl.piotrjanczukowicz.http.client;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static pl.piotrjanczukowicz.util.ConcurrencyUtils.sleepInterruptibly;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtLeast;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.client.HttpCall.AbsoluteUri;
import pl.piotrjanczukowicz.http.uri.HttpUris;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;
import pl.piotrjanczukowicz.json.JsonCodec;
import pl.piotrjanczukowicz.json.JsonDecodeException;
import pl.piotrjanczukowicz.json.JsonEncodeException;
import pl.piotrjanczukowicz.json.JsonType;

/**
 * Executes {@link HttpCall}s and decodes their responses: the operations that used to hang off the call itself. It
 * holds what they need, the {@link HttpClientExecutor} that sends a call and the {@link JsonCodec} that encodes request
 * bodies and decodes responses, and nothing about any one call.
 *
 * <p>JSON {@code Content-Type} is set only by {@link #json(HttpCall, Object)} and {@link #jsonIfPresent(HttpCall,
 * Object)}.
 */
public abstract class AbstractHttpCaller {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractHttpCaller.class);

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CALL = "call";
    private static final String TYPE = "type";
    private static final Set<Integer> NOT_FOUND = Set.of(HttpStatus.NOT_FOUND.code());
    private static final Set<Integer> NOT_MODIFIED = Set.of(HttpStatus.NOT_MODIFIED.code());

    private final HttpClientExecutor executor;
    private final JsonCodec jsonCodec;

    /**
     * Caller over {@code executor}.
     *
     * @param executor sends the calls
     * @param jsonCodec encodes request bodies and decodes responses
     * @throws NullPointerException if an argument is {@code null}
     */
    protected AbstractHttpCaller(final HttpClientExecutor executor, final JsonCodec jsonCodec) {
        this.executor = requireNonNull(executor, "executor");
        this.jsonCodec = requireNonNull(jsonCodec, "jsonCodec");
    }

    /**
     * {@code call} with {@code value} encoded as JSON as its body and {@code Content-Type: application/json}.
     *
     * @param call call to extend
     * @param value request body
     * @return new call
     * @throws NullPointerException if an argument is {@code null}
     * @throws JsonEncodeException if {@code value} cannot be written as JSON
     */
    public HttpCall json(final HttpCall call, final Object value) {
        requireNonNull(call, CALL);
        requireNonNull(value, "value");
        return call.withBody(HttpBody.of(jsonCodec.encode(value))).withHeader(CONTENT_TYPE, JSON_CONTENT_TYPE);
    }

    /**
     * {@code call} with {@code value} encoded as JSON when it is present; otherwise {@code call} as it is.
     *
     * @param call call to extend
     * @param value request body, or {@code null}
     * @return new call, or {@code call}
     * @throws NullPointerException if {@code call} is {@code null}
     * @throws JsonEncodeException if {@code value} is present and cannot be written as JSON
     */
    public HttpCall jsonIfPresent(final HttpCall call, final @Nullable Object value) {
        requireNonNull(call, CALL);
        return value == null ? call : json(call, value);
    }

    /**
     * Sends {@code call} and returns the response when it is HTTP 2xx.
     *
     * @param call call to send
     * @return response
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code call} is {@code null}
     */
    public HttpResponse send(final HttpCall call) throws IOException {
        return send(call, Set.of());
    }

    /**
     * Sends {@code call} and returns the response when it is HTTP 2xx or one of {@code expectedStatuses}. HTTP 304 is
     * an error unless listed there, so a conditional call made with a conditional header lists it to get it back.
     *
     * @param call call to send
     * @param expectedStatuses extra statuses treated as a completed call
     * @return response
     * @throws IOException if the transport fails
     * @throws NullPointerException if an argument is {@code null}
     */
    public HttpResponse send(final HttpCall call, final Set<Integer> expectedStatuses) throws IOException {
        requireNonNull(call, CALL);
        requireNonNull(expectedStatuses, "expectedStatuses");
        return executor.execute(call, expectedStatuses, true);
    }

    /**
     * Sends {@code call} and decodes the body as {@code type}. An unsuccessful response, HTTP 404 included, throws: use
     * this for a collection, a setting, a capability, a public key or a report, where a 404 means a wrong scope or a
     * disabled feature, and {@link #fetchOrEmpty(HttpCall, Class)} for a lookup of one resource by a key, where "not
     * found" is a normal answer.
     *
     * @param call call to send
     * @param type response class
     * @param <T> response type
     * @return decoded value
     * @throws IOException if the transport fails
     * @throws HttpEmptyResponseException if the response has no body, for example HTTP 202 without one or HTTP 204; use
     *     {@link #fetchOptional(HttpCall, Class)} for endpoints that answer that way
     * @throws JsonDecodeException if the body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> T fetch(final HttpCall call, final Class<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchDecoded(call, response -> decode(response, type));
    }

    /**
     * Sends {@code call} and decodes the body as {@code type}. An unsuccessful response, HTTP 404 included, throws; see
     * {@link #fetch(HttpCall, Class)} for when to prefer {@link #fetchOrEmpty(HttpCall, Class)}.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param <T> response type
     * @return decoded value
     * @throws IOException if the transport fails
     * @throws HttpEmptyResponseException if the response has no body, for example HTTP 202 without one or HTTP 204; use
     *     {@link #fetchOptional(HttpCall, JsonType)} for endpoints that answer that way
     * @throws JsonDecodeException if the body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> T fetch(final HttpCall call, final JsonType<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchDecoded(call, response -> decode(response, type));
    }

    /**
     * Decodes as {@code type}, or empty on HTTP 404. Use it for a lookup of one resource by a key (an id, a name or a
     * number), where "not found" is a normal answer; use {@link #fetch(HttpCall, Class)} for a collection, a setting, a
     * capability, a public key or a report. Use {@link #fetchResult(HttpCall, Class)} when the status matters, and
     * {@link #fetchOptional(HttpCall, Class)} for endpoints that answer without a body (202, 204).
     *
     * @param call call to send
     * @param type response class
     * @param <T> response type
     * @return decoded value, or empty when the server returns 404
     * @throws IOException if the transport fails
     * @throws HttpEmptyResponseException if another response has no body; the message names the status, method, and
     *     target
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchOrEmpty(final HttpCall call, final Class<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchOrEmptyDecoded(call, response -> decode(response, type));
    }

    /**
     * Decodes the body as {@code type}, or empty when the server returns 404; see {@link #fetchOrEmpty(HttpCall,
     * Class)}.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param <T> response type
     * @return decoded value, or empty when the server returns 404
     * @throws IOException if the transport fails
     * @throws HttpEmptyResponseException if another response has no body; the message names the status, method, and
     *     target
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchOrEmpty(final HttpCall call, final JsonType<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchOrEmptyDecoded(call, response -> decode(response, type));
    }

    /**
     * Decodes the body as {@code type}, or empty when the response carries no value: HTTP 202 without a body (for
     * example an endpoint still computing), 204, 304 (a conditional call), or a response with an empty body. An HTTP
     * 202 with a body is decoded, as {@link #fetch(HttpCall, Class)} does. HTTP 304 is accepted even without a
     * conditional header on the call.
     *
     * @param call call to send
     * @param type response class
     * @param <T> response type
     * @return decoded value, or empty when the response has none
     * @throws IOException if the transport fails
     * @throws JsonDecodeException if a non-empty body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchOptional(final HttpCall call, final Class<T> type) throws IOException {
        return fetchResult(call, type).value();
    }

    /**
     * Decodes the body as {@code type}, or empty when the response carries no value; see
     * {@link #fetchOptional(HttpCall, Class)}.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param <T> response type
     * @return decoded value, or empty when the response has none
     * @throws IOException if the transport fails
     * @throws JsonDecodeException if a non-empty body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchOptional(final HttpCall call, final JsonType<T> type) throws IOException {
        return fetchResult(call, type).value();
    }

    /**
     * Like {@link #fetchOptional(HttpCall, Class)}, with the response so that its status and headers, such as the
     * {@code ETag}, stay available.
     *
     * @param call call to send
     * @param type response class
     * @param <T> response type
     * @return the response and its decoded value, empty when the response has none
     * @throws IOException if the transport fails
     * @throws JsonDecodeException if a non-empty body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> HttpResult<T> fetchResult(final HttpCall call, final Class<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchResultDecoded(call, response -> decode(response, type));
    }

    /**
     * Like {@link #fetchOptional(HttpCall, JsonType)}, with the response so that its status and headers stay available.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param <T> response type
     * @return the response and its decoded value, empty when the response has none
     * @throws IOException if the transport fails
     * @throws JsonDecodeException if a non-empty body is not valid JSON for {@code type}
     * @throws IllegalStateException if the body is the JSON literal {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> HttpResult<T> fetchResult(final HttpCall call, final JsonType<T> type) throws IOException {
        requireNonNull(type, TYPE);
        return fetchResultDecoded(call, response -> decode(response, type));
    }

    /**
     * Sends {@code call} and passes the body of a successful response to {@code handler} as a stream, never holding it
     * in memory. Redirects are followed (a cross-origin hop drops the credentials). Retries apply only until the
     * response headers arrive; a failure while reading the body is not retried. An unsuccessful response is mapped to
     * the API exception without calling the handler. See {@link HttpClientExecutor#stream}, which also explains how
     * {@code callTimeout} limits a long download.
     *
     * @param call call to send
     * @param handler reads the body; must not keep the stream
     * @param <T> handler result type
     * @return the handler's result
     * @throws IOException if the transport fails, or the handler does
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> T stream(final HttpCall call, final HttpStreamHandler<T> handler) throws IOException {
        requireNonNull(call, CALL);
        return executor.stream(call, requireNonNull(handler, "handler"));
    }

    /**
     * Streams the body of a successful response into {@code out}, without buffering it. {@code out} is not closed.
     *
     * @param call call to send
     * @param out stream the body is copied to
     * @return number of bytes written
     * @throws IOException if the transport fails or {@code out} cannot be written; {@code out} may then hold a partial
     *     body
     * @throws NullPointerException if an argument is {@code null}
     */
    public long download(final HttpCall call, final OutputStream out) throws IOException {
        requireNonNull(out, "out");
        return stream(call, response -> response.body().transferTo(out));
    }

    /**
     * Streams the body of a successful response into {@code target}. The body is first written to a temporary file in
     * the same directory and moved onto {@code target} (atomically where the file system supports it, replacing an
     * existing file) only when it was received completely, so {@code target} never holds a partial download. The
     * temporary file is deleted on failure, and nothing is created for an unsuccessful response. When the file system
     * cannot move atomically, a plain replacing move is used instead.
     *
     * <p>Permissions: where the file system has POSIX permissions, the file is created owner-only ({@code rw-------}),
     * since the body may be a private archive; loosen them afterwards if others must read it. Elsewhere it gets the
     * default permissions of a new file. An existing {@code target} is replaced by a new file, so it does not keep its
     * old permissions or owner.
     *
     * <p>A {@code callTimeout} of the transport also limits the download; see {@link #stream(HttpCall,
     * HttpStreamHandler)}.
     *
     * @param call call to send
     * @param target destination file; its directory must exist
     * @return {@code target}
     * @throws IOException if the transport fails or the file cannot be written
     * @throws NullPointerException if an argument is {@code null}
     */
    public Path download(final HttpCall call, final Path target) throws IOException {
        requireNonNull(target, "target");
        return stream(call, response -> HttpDownloads.toFile(response.body(), target));
    }

    /**
     * Like {@link #download(HttpCall, Path)}, but refuses a body larger than {@code maxBytes}. The copy stops at the
     * first byte over the limit, the temporary file is deleted and {@code target} is not touched, so a hostile or
     * misbehaving server cannot fill the disk.
     *
     * @param call call to send
     * @param target destination file; its directory must exist
     * @param maxBytes most bytes accepted, zero or more
     * @return {@code target}
     * @throws pl.piotrjanczukowicz.http.ResponseTooLargeException if the body is larger than {@code maxBytes}
     * @throws IOException if the transport fails or the file cannot be written
     * @throws NullPointerException if {@code call} or {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code maxBytes} is negative
     */
    public Path download(final HttpCall call, final Path target, final long maxBytes) throws IOException {
        requireNonNull(target, "target");
        requireAtLeast(maxBytes, 0L, "maxBytes");
        return stream(call, response -> HttpDownloads.toFile(response.body(), target, maxBytes));
    }

    /**
     * Sends {@code call} without following redirects and returns {@code Location} from HTTP 301, 302, 303, 307, or 308.
     *
     * @param call call to send
     * @return redirect target
     * @throws IOException if the transport fails
     * @throws IllegalStateException if the response is successful (2xx) and so is not a redirect
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     * @throws NullPointerException if {@code call} is {@code null}
     */
    public URI redirectLocation(final HttpCall call) throws IOException {
        return executor.redirectLocation(requireNonNull(call, CALL));
    }

    /**
     * Like {@link #redirectLocation(HttpCall)}, but a successful (2xx) response means that there is no redirect and
     * yields empty, for example when the content is sent directly or the server is still preparing the result (HTTP
     * 202).
     *
     * @param call call to send
     * @return redirect target from HTTP 301, 302, 303, 307, or 308, or empty when the response is successful
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     * @throws NullPointerException if {@code call} is {@code null}
     */
    public Optional<URI> redirectLocationIfAny(final HttpCall call) throws IOException {
        return executor.redirectLocationIfAny(requireNonNull(call, CALL));
    }

    /**
     * Whether {@code call} succeeds (any 2xx). HTTP 404 yields {@code false}. Redirects are not followed.
     *
     * @param call call to send
     * @return {@code true} if the resource exists
     * @throws IOException if the transport fails
     * @throws HttpException if the response is a redirect (3xx) or another non-404 error
     * @throws NullPointerException if {@code call} is {@code null}
     */
    public boolean isExisting(final HttpCall call) throws IOException {
        return executor.execute(requireNonNull(call, CALL), NOT_FOUND, false).isSuccessful();
    }

    /**
     * Empty when the status is one of {@code statuses}, or when any 2xx response has no body or a body equal to
     * {@code {}}; otherwise the body is decoded as {@code type}.
     *
     * <p>The {@code {}} rule is deliberate: some APIs answer a read of a value that is not set with {@code 200 {}} to
     * mean "nothing here", and decoding that into a model would produce an object with every field missing. It applies
     * to any 2xx status, not only the listed ones, so use it only for endpoints with that behavior;
     * {@link #fetchOptional(HttpCall, Class)} treats HTTP 204 and 304, an HTTP 202 without a body, and an empty body as
     * empty.
     *
     * @param call call to send
     * @param type response class
     * @param statuses statuses treated as empty, including non-2xx ones such as 404
     * @param <T> response type
     * @return decoded value, or empty when the status is one of {@code statuses}, or when any 2xx response has no body
     *     or a body equal to {@code {}}; otherwise the body is decoded as {@code type}
     * @throws IOException if the transport fails
     * @throws HttpException if the response is unsuccessful
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchUnless(final HttpCall call, final Class<T> type, final HttpStatus... statuses)
            throws IOException {
        requireNonNull(type, TYPE);
        return fetchUnlessDecoded(call, response -> decode(response, type), statuses);
    }

    /**
     * Empty when the status is one of {@code statuses}, or when any 2xx response has no body or a body equal to
     * {@code {}}; otherwise the body is decoded as {@code type}. See {@link #fetchUnless(HttpCall, Class,
     * HttpStatus...)} for the {@code {}} rule.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param statuses statuses treated as empty, including non-2xx ones such as 404
     * @param <T> response type
     * @return decoded value, or empty when the status is one of {@code statuses}, or when any 2xx response has no body
     *     or a body equal to {@code {}}; otherwise the body is decoded as {@code type}
     * @throws IOException if the transport fails
     * @throws HttpException if the response is unsuccessful
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> Optional<T> fetchUnless(final HttpCall call, final JsonType<T> type, final HttpStatus... statuses)
            throws IOException {
        requireNonNull(type, TYPE);
        return fetchUnlessDecoded(call, response -> decode(response, type), statuses);
    }

    /**
     * Like {@link #fetchUnless(HttpCall, Class, HttpStatus...) fetchUnless(call, type, HttpStatus.NO_CONTENT)}, but
     * waits for a result that the server computes in the background: while the answer is HTTP 202 Accepted the request
     * is sent again, after the {@code polling} delay, until the data arrives or {@code polling} has no tries left.
     *
     * @param call call to send
     * @param type response class
     * @param polling number of tries and the pause between them
     * @param <T> response type
     * @return {@link HttpComputed.Ready} with the decoded value; {@link HttpComputed.NotReady} when the answer is still
     *     HTTP 202 after the last try; {@link HttpComputed.NoData} for HTTP 204, an empty body or {@code {}}
     * @throws IOException if the transport fails, or the thread is interrupted while waiting (the interrupt flag is set
     *     again and the exception is an {@link java.io.InterruptedIOException})
     * @throws HttpException if the response is unsuccessful
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> HttpComputed<T> fetchWhenReady(
            final HttpCall call, final Class<T> type, final HttpAcceptedPolling polling) throws IOException {
        requireNonNull(type, TYPE);
        return fetchWhenReadyDecoded(call, response -> decode(response, type), polling);
    }

    /**
     * Like {@link #fetchWhenReady(HttpCall, Class, HttpAcceptedPolling)} for a response type with type arguments.
     *
     * @param call call to send
     * @param type response type, including type arguments
     * @param polling number of tries and the pause between them
     * @param <T> response type
     * @return {@link HttpComputed.Ready} with the decoded value; {@link HttpComputed.NotReady} when the answer is still
     *     HTTP 202 after the last try; {@link HttpComputed.NoData} for HTTP 204, an empty body or {@code {}}
     * @throws IOException if the transport fails, or the thread is interrupted while waiting
     * @throws HttpException if the response is unsuccessful
     * @throws NullPointerException if an argument is {@code null}
     */
    public <T> HttpComputed<T> fetchWhenReady(
            final HttpCall call, final JsonType<T> type, final HttpAcceptedPolling polling) throws IOException {
        requireNonNull(type, TYPE);
        return fetchWhenReadyDecoded(call, response -> decode(response, type), polling);
    }

    /**
     * Sends {@code call} and builds the result from the response with the function that belongs to its status. Use it
     * when the status, not only the body, decides what the response means, such as HTTP 201 and 202 returning different
     * models.
     *
     * <p>The statuses in {@code byStatus} are expected, so a non-2xx one such as 404 reaches its function instead of
     * becoming an exception. Any other 2xx status uses {@code otherwise}; any other unsuccessful status is an exception
     * as usual.
     *
     * @param call call to send
     * @param otherwise result for a successful status that has no entry in {@code byStatus}
     * @param byStatus result per status; a function decodes the body, if it needs it, with the {@link HttpBodyDecoder}
     * @param <R> result type
     * @return the result of the function chosen by the status of the response
     * @throws IOException if the transport fails
     * @throws HttpException if the response is unsuccessful and its status is not in {@code byStatus}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code byStatus} has a 3xx status, which is followed before it reaches a
     *     function
     */
    public <R> R fetchByStatus(
            final HttpCall call,
            final Function<HttpBodyDecoder, R> otherwise,
            final Map<HttpStatus, Function<HttpBodyDecoder, R>> byStatus)
            throws IOException {
        requireNonNull(otherwise, "otherwise");
        final Map<Integer, Function<HttpBodyDecoder, R>> byCode = new HashMap<>();
        requireNonNull(byStatus, "byStatus").forEach((status, mapper) -> {
            final int code = requireNonNull(status, "status").code();
            if (code >= 300 && code < 400) {
                throw new IllegalArgumentException(
                        "byStatus cannot hold a redirect status, which is followed before it reaches a function: "
                                + code);
            }
            byCode.put(code, requireNonNull(mapper, "mapper"));
        });
        final HttpResponse response = send(call, byCode.keySet());
        final HttpBodyDecoder decoder = new HttpBodyDecoder() {
            @Override
            public <T> T as(final Class<T> type) {
                return decode(requireBody(call, response), type);
            }

            @Override
            public <T> T as(final JsonType<T> type) {
                return decode(requireBody(call, response), type);
            }
        };
        return byCode.getOrDefault(response.statusCode(), otherwise).apply(decoder);
    }

    /**
     * The call as {@code METHOD target}, for messages; the target has no query, user info or fragment.
     *
     * @param call call to describe
     * @return description of the call
     */
    protected static String describe(final HttpCall call) {
        return call.method() + " " + call.target();
    }

    /**
     * Decodes the body of {@code response} as {@code type} with the JSON codec of this caller.
     *
     * @param response response with a body
     * @param type target class
     * @param <T> result type
     * @return decoded value
     */
    protected final <T> T decode(final HttpResponse response, final Class<T> type) {
        return jsonCodec.decode(response.requireBodyBytes(), type);
    }

    /**
     * Decodes the body of {@code response} as {@code type} with the JSON codec of this caller.
     *
     * @param response response with a body
     * @param type target type
     * @param <T> result type
     * @return decoded value
     */
    protected final <T> T decode(final HttpResponse response, final JsonType<T> type) {
        return jsonCodec.decode(response.requireBodyBytes(), type);
    }

    /**
     * Fails with the status, method, and target when {@code response} has no body to decode, such as HTTP 202 or 204.
     *
     * @param call call that was sent
     * @param response response about to be decoded
     * @return {@code response}
     * @throws HttpEmptyResponseException if the body is absent
     */
    private HttpResponse requireBody(final HttpCall call, final HttpResponse response) {
        if (response.body().isAbsent()) {
            throw emptyResponse(call, response, "has no body to decode");
        }
        return response;
    }

    /**
     * Whether {@code response} has no value to decode: HTTP 204 or 304, or no body. An HTTP 202 is empty only without a
     * body, so a body that the server sends with it is decoded.
     *
     * @param response response to inspect
     * @return {@code true} if there is nothing to decode
     */
    private static boolean hasNoValue(final HttpResponse response) {
        return response.hasStatus(HttpStatus.NO_CONTENT)
                || response.hasStatus(HttpStatus.NOT_MODIFIED)
                || response.body().isAbsent();
    }

    private <T> Optional<T> fetchUnlessDecoded(
            final HttpCall call, final Function<HttpResponse, T> decoder, final HttpStatus... statuses)
            throws IOException {
        final HttpResponse response = send(call, expectedCodes(statuses));
        if (isEmpty(response, statuses)) {
            LOG.atDebug()
                    .setMessage(
                            "{} gave HTTP {}; returning an empty result (listed status, no body or an empty JSON object)")
                    .addArgument(() -> describe(call))
                    .addArgument(response.statusCode())
                    .log();
            return Optional.empty();
        }
        return Optional.of(decoder.apply(response));
    }

    private <T> HttpComputed<T> fetchWhenReadyDecoded(
            final HttpCall call, final Function<HttpResponse, T> decoder, final HttpAcceptedPolling polling)
            throws IOException {
        requireNonNull(polling, "polling");
        HttpResponse response = send(call);
        for (int attempt = 1; response.hasStatus(HttpStatus.ACCEPTED) && attempt < polling.maxAttempts(); attempt++) {
            logAccepted(call, attempt, polling);
            sleepInterruptibly(polling.delay(), "interrupted while waiting for the server");
            response = send(call);
        }
        if (response.hasStatus(HttpStatus.ACCEPTED)) {
            LOG.atDebug()
                    .setMessage("{} is still HTTP 202 after {} tries; returning NotReady")
                    .addArgument(() -> describe(call))
                    .addArgument(polling.maxAttempts())
                    .log();
            return new HttpComputed.NotReady<>();
        }
        if (isEmpty(response, HttpStatus.NO_CONTENT)) {
            return new HttpComputed.NoData<>();
        }
        return new HttpComputed.Ready<>(decoder.apply(response));
    }

    private static void logAccepted(final HttpCall call, final int attempt, final HttpAcceptedPolling polling) {
        LOG.atDebug()
                .setMessage("{} answered HTTP 202 (try {} of {}); asking again in {} ms")
                .addArgument(() -> describe(call))
                .addArgument(attempt)
                .addArgument(polling.maxAttempts())
                .addArgument(() -> polling.delay().toMillis())
                .log();
    }

    // Listed statuses are accepted by the executor, so a listed non-2xx status such as 404 yields empty, not an error.
    // Null statuses are rejected here, before the request is sent.
    private static Set<Integer> expectedCodes(final HttpStatus... statuses) {
        requireNonNull(statuses, "statuses");
        return Arrays.stream(statuses)
                .map(status -> requireNonNull(status, "status").code())
                .collect(Collectors.toSet());
    }

    private static boolean isEmpty(final HttpResponse response, final HttpStatus... statuses) {
        for (final HttpStatus status : statuses) {
            if (response.hasStatus(status)) {
                return true;
            }
        }
        return isEmptyJsonObject(response);
    }

    private static boolean isEmptyJsonObject(final HttpResponse response) {
        return response.body().mapBytes(AbstractHttpCaller::isEmptyJsonObjectBytes, () -> true);
    }

    // Scans the bytes in place: whitespace, '{', whitespace, '}', whitespace. The whitespace is space, tab, LF and CR,
    // plus vertical tab and form feed that earlier versions also tolerated around the braces.
    private static boolean isEmptyJsonObjectBytes(final byte[] bytes) {
        int index = skipWhitespace(bytes, 0);
        if (index == bytes.length || bytes[index] != '{') {
            return false;
        }
        index = skipWhitespace(bytes, index + 1);
        if (index == bytes.length || bytes[index] != '}') {
            return false;
        }
        return skipWhitespace(bytes, index + 1) == bytes.length;
    }

    private static int skipWhitespace(final byte[] bytes, final int from) {
        int index = from;
        while (index < bytes.length && isWhitespace(bytes[index])) {
            index++;
        }
        return index;
    }

    private static boolean isWhitespace(final byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == 0x0B || value == '\f';
    }

    private <T> T fetchDecoded(final HttpCall call, final Function<HttpResponse, T> decoder) throws IOException {
        final HttpResponse response = send(call);
        return decoder.apply(requireBody(call, response));
    }

    private <T> HttpResult<T> fetchResultDecoded(final HttpCall call, final Function<HttpResponse, T> decoder)
            throws IOException {
        requireNonNull(call, CALL);
        final HttpResponse response = executor.execute(call, NOT_MODIFIED, true);
        if (hasNoValue(response)) {
            logEmpty(call, response);
            return new HttpResult<>(response, null);
        }
        return new HttpResult<>(response, decoder.apply(response));
    }

    private static void logEmpty(final HttpCall call, final HttpResponse response) {
        LOG.atDebug()
                .setMessage("{} gave HTTP {}; returning an empty result")
                .addArgument(() -> describe(call))
                .addArgument(response.statusCode())
                .log();
    }

    private <T> Optional<T> fetchOrEmptyDecoded(final HttpCall call, final Function<HttpResponse, T> decoder)
            throws IOException {
        requireNonNull(call, CALL);
        final HttpResponse response = executor.execute(call, NOT_FOUND, true);
        if (response.hasStatus(HttpStatus.NOT_FOUND)) {
            logEmpty(call, response);
            return Optional.empty();
        }
        return Optional.of(decoder.apply(requireBody(call, response)));
    }

    private HttpEmptyResponseException emptyResponse(
            final HttpCall call, final HttpResponse response, final String problem) {
        return new HttpEmptyResponseException(
                response.statusCode(),
                "HTTP " + response.statusCode() + " response to " + describe(call) + " " + problem,
                response.headers(),
                call.method(),
                safeUri(call));
    }

    // Scheme, host, port and path without query, user info or fragment; a path call is resolved against the base URI,
    // as the client resolved it when it sent the call.
    private URI safeUri(final HttpCall call) {
        if (call.target() instanceof AbsoluteUri(final URI uri)) {
            return URI.create(HttpUris.describeSafe(uri));
        }
        final String path = ((HttpCall.Path) call.target()).path();
        return URI.create(
                ParsedUrl.of(executor.baseUri()).resolveApi(path, Map.of()).describeSafe());
    }
}
