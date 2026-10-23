package pl.piotrjanczukowicz.http;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNotBlank;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;

/**
 * An outbound HTTP request, as the client built it from a call: method, resolved URL, final headers, and optional body.
 * It is immutable and is what the transports and their decorators send.
 *
 * <p>Null {@code headers} become empty. Null {@code body} becomes {@link HttpBody#absent()}. GET and HEAD requests
 * cannot carry a body, so every transport rejects the same requests.
 *
 * @param method HTTP method
 * @param url request target, parsed once; see {@link #uri()} for the {@link URI} form
 * @param headers request headers
 * @param body request body, possibly absent
 * @param credentialId stable identity of the principal the request is sent for, as {@code Authentication.identity()}
 *     gives it, or {@code null} when there is none. It is never a secret: decorators key their per-credential state by
 *     it, through {@link #credentialKey()}, and without it they key by a digest of the {@code Authorization} header
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "HttpHeaders is immutable")
public record HttpRequest(
        HttpMethod method,
        ParsedUrl url,
        HttpHeaders headers,
        HttpBody body,
        @Nullable String credentialId) {

    private static final String ANONYMOUS = "anonymous";

    /**
     * Request without a credential identity.
     *
     * @param method HTTP method
     * @param url request target
     * @param headers request headers; {@code null} becomes empty
     * @param body request body; {@code null} becomes absent
     * @throws NullPointerException if {@code method} or {@code url} is {@code null}
     * @throws IllegalArgumentException if a GET or HEAD request has a body
     */
    public HttpRequest(
            final HttpMethod method,
            final ParsedUrl url,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpBody body) {
        this(method, url, headers, body, null);
    }

    /**
     * Request with a credential identity.
     *
     * @param method HTTP method
     * @param url request target
     * @param headers request headers; {@code null} becomes empty
     * @param body request body; {@code null} becomes absent
     * @param credentialId identity of the principal, or {@code null} for none; a blank value is refused
     * @throws NullPointerException if {@code method} or {@code url} is {@code null}
     * @throws IllegalArgumentException if a GET or HEAD request has a body, or {@code credentialId} is blank
     */
    public HttpRequest(
            final HttpMethod method,
            final ParsedUrl url,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpBody body,
            final @Nullable String credentialId) {
        this.method = requireNonNull(method, "method");
        this.url = requireNonNull(url, "url");
        this.headers = headers == null ? HttpHeaders.empty() : headers;
        this.body = body == null ? HttpBody.absent() : body;
        if (credentialId != null) {
            requireNotBlank(credentialId, "credentialId");
        }
        this.credentialId = credentialId;
        if (this.method.isBodyless() && !this.body.isAbsent()) {
            throw new IllegalArgumentException(this.method + " request must not have a body");
        }
    }

    /**
     * Same as the canonical constructor without a credential identity, with the target as a {@link URI}.
     *
     * @param uri absolute HTTP(S) URI
     * @throws NullPointerException if {@code method} or {@code uri} is {@code null}
     * @throws IllegalArgumentException if {@code uri} is not an absolute HTTP(S) URL
     */
    public HttpRequest(
            final HttpMethod method,
            final URI uri,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpBody body) {
        this(method, ParsedUrl.of(requireNonNull(uri, "uri")), headers, body);
    }

    /**
     * Same as the canonical constructor, with the target as a {@link URI} and headers as a map.
     *
     * @param headers header names to values; {@code null} becomes empty
     */
    public HttpRequest(final HttpMethod method, final URI uri, final Map<String, String> headers, final HttpBody body) {
        this(method, uri, HttpHeaders.of(headers), body);
    }

    /**
     * The request target as a {@link URI}, built on each call: use {@link #url()} on the hot path.
     *
     * @return URI of the request
     */
    public URI uri() {
        return url.toUri();
    }

    /**
     * Percent-encoded path of the request target, without the query.
     *
     * @return path, always starting with {@code /}
     */
    public String encodedPath() {
        return url.encodedPath();
    }

    /**
     * Key under which decorators keep per-credential state, such as a rate-limit block or a cached response. It is
     * never the credential itself. A request with a {@link #credentialId() credential identity} is keyed by that
     * identity, with the prefix {@code id:}. One without is keyed by a keyed digest
     * ({@link Digests#keyedSha256Hex(String)}) of its {@code Authorization} header value, with the prefix
     * {@code auth:}. One with neither is keyed as {@code anonymous}. The prefixes keep the three forms apart.
     *
     * @return key of the credential this request is sent for
     */
    public String credentialKey() {
        if (credentialId != null) {
            return "id:" + credentialId;
        }
        return headers.get(AUTHORIZATION)
                .map(value -> "auth:" + Digests.keyedSha256Hex(value))
                .orElse(ANONYMOUS);
    }

    /**
     * Maps in-memory body bytes through {@code present}, or {@code absent} when there is no in-memory body. File and
     * stream bodies use {@code absent} and are never loaded into heap ({@link HttpBody#mapBytes}).
     *
     * @param present mapping for present bytes
     * @param absent mapping when the body is absent
     * @return result of {@code present} or {@code absent}
     * @throws NullPointerException if {@code present} or {@code absent} is {@code null}
     */
    public <T> T mapBody(final Function<byte[], T> present, final Supplier<T> absent) {
        return body.mapBytes(present, absent);
    }

    /**
     * Body sent on the wire. POST, PUT, and PATCH with an absent body become a zero-length entity.
     *
     * @return body to transmit
     */
    public HttpBody effectiveBody() {
        if (!body.isAbsent()) {
            return body;
        }
        return method.isEmptyBodySentWhenAbsent() ? HttpBody.zeroLength() : body;
    }
}
