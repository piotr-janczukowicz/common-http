package pl.piotrjanczukowicz.http.uri;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import org.jspecify.annotations.Nullable;

/**
 * An absolute HTTP(S) URL that is parsed once and carried from request resolution to the transport.
 *
 * <p>The URL is normalised the way OkHttp does it: the host is lower case, a default port is implied, dot segments are
 * resolved and the path and query are percent-encoded. A {@link URI} is built only on demand, with {@link #toUri()},
 * for logs, exceptions and the public API.
 *
 * <p>{@link #toString()} shows scheme, host, port and path only, because the query and user info often carry
 * credentials or signed tokens.
 */
public final class ParsedUrl {

    private final HttpUrl url;

    private ParsedUrl(final HttpUrl url) {
        this.url = url;
    }

    /**
     * Parsed form of {@code uri}.
     *
     * @param uri absolute HTTP(S) URI
     * @return parsed URL, or {@code null} when {@code uri} is not an absolute HTTP(S) URL
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    public static @Nullable ParsedUrl parse(final URI uri) {
        final HttpUrl parsed = HttpUrl.get(requireNonNull(uri, "uri"));
        return parsed == null ? null : new ParsedUrl(parsed);
    }

    /**
     * Parsed form of {@code uri}.
     *
     * @param uri absolute HTTP(S) URI
     * @return parsed URL
     * @throws NullPointerException if {@code uri} is {@code null}
     * @throws IllegalArgumentException if {@code uri} is not an absolute HTTP(S) URL
     */
    public static ParsedUrl of(final URI uri) {
        final ParsedUrl parsed = parse(uri);
        if (parsed == null) {
            throw new IllegalArgumentException("uri must be an absolute http or https URL");
        }
        return parsed;
    }

    /**
     * Resolves {@code reference} against this URL (RFC 3986), as a browser resolves a link.
     *
     * @param reference {@code Location} or other URI reference
     * @return absolute URL, or {@code null} when the reference does not resolve to an HTTP(S) URL
     * @throws NullPointerException if {@code reference} is {@code null}
     */
    public @Nullable ParsedUrl resolve(final String reference) {
        final HttpUrl resolved = url.resolve(requireNonNull(reference, "reference"));
        return resolved == null ? null : new ParsedUrl(resolved);
    }

    /**
     * This URL with {@code query} appended.
     *
     * @param query query parameters to add
     * @return this URL when {@code query} is empty, otherwise a new URL
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public ParsedUrl withQueryValues(final Map<String, List<String>> query) {
        if (requireNonNull(query, "query").isEmpty()) {
            return this;
        }
        final HttpUrl.Builder builder = url.newBuilder();
        query.forEach((name, values) -> values.forEach(value -> builder.addQueryParameter(name, value)));
        return new ParsedUrl(builder.build());
    }

    /**
     * Resolves {@code path} against this URL as an API path, then appends {@code query}.
     *
     * <p>Strips one leading {@code /}. The path must be a relative API path: it may not start with {@code //}, carry a
     * scheme, or contain a query ({@code ?}), a fragment ({@code #}) or a dot segment. Put the query in {@code query}.
     *
     * @param path API path or URI reference
     * @param query query parameters
     * @return absolute URL
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code path} is not a relative API path, or does not resolve to an HTTP(S)
     *     URL
     */
    public ParsedUrl resolveApi(final String path, final Map<String, List<String>> query) {
        return new ParsedUrl(HttpUris.resolveApiHttpUrl(url, path, query));
    }

    /**
     * Scheme in lower case.
     *
     * @return {@code http} or {@code https}
     */
    public String scheme() {
        return url.scheme();
    }

    /**
     * Host in lower case, without brackets for an IPv6 address.
     *
     * @return host
     */
    public String host() {
        return url.host();
    }

    /**
     * Effective port: the explicit port, or the default of the scheme.
     *
     * @return port
     */
    public int port() {
        return url.port();
    }

    /**
     * Percent-encoded path, always starting with {@code /}.
     *
     * @return encoded path
     */
    public String encodedPath() {
        return url.encodedPath();
    }

    /**
     * Whether the URL uses {@code https}.
     *
     * @return {@code true} for {@code https}
     */
    public boolean isHttps() {
        return url.isHttps();
    }

    /**
     * Whether {@code other} has the same scheme, host and effective port.
     *
     * @param other URL to compare with
     * @return {@code true} when the origins match
     */
    public boolean hasSameOrigin(final ParsedUrl other) {
        return url.scheme().equals(other.url.scheme())
                && url.host().equals(other.url.host())
                && url.port() == other.url.port();
    }

    /**
     * The URL as a {@link URI}, with the query, for the public API.
     *
     * @return URI
     */
    public URI toUri() {
        return url.uri();
    }

    /**
     * {@code scheme://host[:port]path}: without user info, query and fragment, which often carry credentials or signed
     * tokens. The port shows only when it is not the default of the scheme.
     *
     * @return log-safe form
     */
    public String describeSafe() {
        final String host = url.host().indexOf(':') >= 0 ? "[" + url.host() + "]" : url.host();
        final String port = url.port() == HttpUrl.defaultPort(url.scheme()) ? "" : ":" + url.port();
        return url.scheme() + "://" + host + port + url.encodedPath();
    }

    /**
     * The OkHttp form of this URL, for the OkHttp transport.
     *
     * @return parsed OkHttp URL
     */
    public HttpUrl toOkHttpUrl() {
        return url;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return this == other || other instanceof final ParsedUrl that && url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    @Override
    public String toString() {
        return describeSafe();
    }
}
