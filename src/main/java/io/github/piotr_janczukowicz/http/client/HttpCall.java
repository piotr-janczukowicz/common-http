package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data of one API call, which is all an {@link HttpClientExecutor} reads: what varies per request. The base URI,
 * default headers and authentication are not part of it, because they belong to the executor.
 *
 * @param method HTTP method
 * @param target a path relative to the API base URI, or an absolute URI
 * @param query query names to one or more values; an unmodifiable copy
 * @param headers headers of this call only
 * @param body request body, absent when there is none
 */
public record HttpCall(
        HttpMethod method, Target target, Map<String, List<String>> query, HttpHeaders headers, HttpBody body) {

    public HttpCall {
        requireNonNull(method, "method");
        requireNonNull(target, "target");
        requireNonNull(headers, "headers");
        requireNonNull(body, "body");
        query = Collections.unmodifiableMap(HttpUris.copyQueryValues(requireNonNull(query, "query")));
    }

    /**
     * Call of {@code method} at {@code path}, with no query, headers or body.
     *
     * @param method HTTP method
     * @param path path relative to the API base URI
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpCall of(final HttpMethod method, final String path) {
        return new HttpCall(method, new Path(path), Map.of(), HttpHeaders.empty(), HttpBody.absent());
    }

    /**
     * Call of {@code method} at an absolute {@code uri}, with no query, headers or body.
     *
     * @param method HTTP method
     * @param uri absolute HTTP(S) URI
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code uri} is not an absolute HTTP(S) URI
     */
    public static HttpCall of(final HttpMethod method, final URI uri) {
        return new HttpCall(method, new AbsoluteUri(uri), Map.of(), HttpHeaders.empty(), HttpBody.absent());
    }

    /**
     * GET {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall get(final String path) {
        return of(HttpMethod.GET, path);
    }

    /**
     * GET an absolute {@code uri}.
     *
     * @param uri absolute HTTP(S) URI
     * @return call
     */
    public static HttpCall get(final URI uri) {
        return of(HttpMethod.GET, uri);
    }

    /**
     * HEAD {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall head(final String path) {
        return of(HttpMethod.HEAD, path);
    }

    /**
     * POST {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall post(final String path) {
        return of(HttpMethod.POST, path);
    }

    /**
     * POST {@code path} with {@code body}.
     *
     * @param path path relative to the API base URI
     * @param body request body
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpCall post(final String path, final HttpBody body) {
        return post(path).withBody(body);
    }

    /**
     * PUT {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall put(final String path) {
        return of(HttpMethod.PUT, path);
    }

    /**
     * PUT {@code path} with {@code body}.
     *
     * @param path path relative to the API base URI
     * @param body request body
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpCall put(final String path, final HttpBody body) {
        return put(path).withBody(body);
    }

    /**
     * PATCH {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall patch(final String path) {
        return of(HttpMethod.PATCH, path);
    }

    /**
     * PATCH {@code path} with {@code body}.
     *
     * @param path path relative to the API base URI
     * @param body request body
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpCall patch(final String path, final HttpBody body) {
        return patch(path).withBody(body);
    }

    /**
     * DELETE {@code path}.
     *
     * @param path path relative to the API base URI
     * @return call
     */
    public static HttpCall delete(final String path) {
        return of(HttpMethod.DELETE, path);
    }

    /**
     * DELETE {@code path} with {@code body}, for the endpoints that take one.
     *
     * @param path path relative to the API base URI
     * @param body request body
     * @return call
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpCall delete(final String path, final HttpBody body) {
        return delete(path).withBody(body);
    }

    /**
     * This call with single-valued query parameters instead of its current query.
     *
     * @param query name to value
     * @return new call
     * @throws NullPointerException if {@code query} or a value is {@code null}
     */
    public HttpCall withQuery(final Map<String, String> query) {
        requireNonNull(query, "query");
        final Map<String, List<String>> values = new LinkedHashMap<>();
        query.forEach(
                (name, value) -> values.put(name, List.of(requireNonNull(value, "query value of '" + name + "'"))));
        return new HttpCall(method, target, values, headers, body);
    }

    /**
     * This call with multi-valued query parameters instead of its current query.
     *
     * @param query name to values
     * @return new call
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public HttpCall withQueryValues(final Map<String, List<String>> query) {
        return new HttpCall(method, target, query, headers, body);
    }

    /**
     * This call with header {@code name} set to {@code value}, replacing a header of the same name (case-insensitive).
     *
     * <p>Do not set {@code Accept-Encoding} to a compressing value such as {@code gzip}: the transport then leaves the
     * response compressed, so the body arrives as compressed bytes that cannot be decoded and the size limit counts
     * them as they are. Without the header the transport asks for gzip and inflates it itself.
     *
     * @param name header name
     * @param value header value
     * @return new call
     * @throws NullPointerException if {@code name} or {@code value} is {@code null}
     * @throws IllegalArgumentException if the name or value breaks the request header rules
     */
    public HttpCall withHeader(final String name, final String value) {
        return new HttpCall(method, target, query, headers.with(name, value), body);
    }

    /**
     * This call with {@code headers} on top of its own; a header of the same name is replaced (case-insensitive). See
     * {@link #withHeader(String, String)} on {@code Accept-Encoding}.
     *
     * @param headers headers to add
     * @return new call
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    public HttpCall withHeaders(final HttpHeaders headers) {
        return new HttpCall(method, target, query, this.headers.merge(headers), body);
    }

    /**
     * This call with {@code body} instead of its current body.
     *
     * @param body request body; {@link HttpBody#absent()} sends none
     * @return new call
     * @throws NullPointerException if {@code body} is {@code null}
     */
    public HttpCall withBody(final HttpBody body) {
        return new HttpCall(method, target, query, headers, body);
    }

    /**
     * Summary for logs: method, target without query or user info, the names (not the values) of the query parameters,
     * and the body size. Query values are left out because they often hold tokens, and the headers print as
     * {@link HttpHeaders#toString()} does, with credentials hidden.
     *
     * @return the summary
     */
    @Override
    public String toString() {
        return "HttpCall[" + method + " " + target + ", query=" + query.keySet() + ", headers=" + headers + ", body="
                + bodySummary() + "]";
    }

    /**
     * The API path of this call.
     *
     * @return path relative to the API base URI
     * @throws IllegalArgumentException if this call targets an absolute URI and so has no API path
     */
    public String requirePath() {
        if (target instanceof Path(final String path)) {
            return path;
        }
        throw new IllegalArgumentException("this call targets an absolute URI and has no API path");
    }

    private String bodySummary() {
        if (body.isAbsent()) {
            return "none";
        }
        // A file body is not measured: that would read the file system on every toString.
        return body instanceof HttpBody.FileBody ? "file" : body.length().orElse(0) + " bytes";
    }

    /** Where the call is sent: a path relative to the API base URI, or an absolute URI. */
    public sealed interface Target permits Path, AbsoluteUri {}

    /**
     * Path resolved against the client's base URI.
     *
     * @param path API-relative path
     */
    public record Path(String path) implements Target {
        public Path {
            requireNonNull(path, "path");
        }

        @Override
        public String toString() {
            final int query = path.indexOf('?');
            return query < 0 ? path : path.substring(0, query);
        }
    }

    /**
     * Absolute URI, checked by the client's origin policy.
     *
     * @param uri absolute URI
     */
    public record AbsoluteUri(URI uri) implements Target {
        public AbsoluteUri {
            HttpUris.requireAbsolute(uri);
        }

        @Override
        public String toString() {
            return HttpUris.describeSafe(uri);
        }
    }
}
