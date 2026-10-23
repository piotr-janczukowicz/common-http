package pl.piotrjanczukowicz.http.uri;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.jspecify.annotations.Nullable;

/** Base-URI normalisation, resolve, query application, and log-safe URI description. */
public final class HttpUris {

    private static final Pattern URI_TEMPLATE = Pattern.compile("\\{[^}]*}");
    private static final Pattern API_SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:");
    private static final String API_PATH_MESSAGE =
            "path must be a relative API path with no scheme, authority, query, fragment or dot segment";
    private static final String QUERY = "query";
    private static final String BASE_URI = "baseUri";
    private static final String PATH = "path";
    private static final String URI_ARG = "uri";

    private HttpUris() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Scheme, host, port, and path of {@code uri}. User info, query, and fragment are dropped because they often carry
     * credentials or signed tokens. A host that {@link URI#getHost()} cannot read, such as {@code my_host.internal}
     * with its underscore, is taken from the raw authority, still without user info.
     *
     * @param uri URI to describe
     * @return {@code scheme://host[:port]path}, or {@code <unsupported-uri>} when {@code uri} has no scheme or no host
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    public static String describeSafe(final URI uri) {
        requireNonNull(uri, URI_ARG);
        final String authority = authorityWithoutUserInfo(uri);
        if (uri.getScheme() == null || authority == null) {
            return "<unsupported-uri>";
        }
        return uri.getScheme() + "://" + authority + Objects.toString(uri.getRawPath(), "");
    }

    /**
     * {@code uri} with a trailing {@code /} on the path.
     *
     * @param uri HTTP(S) base URI
     * @param name argument name for null / invalid failures
     * @return normalized URI
     * @throws NullPointerException if {@code uri} is null
     * @throws IllegalArgumentException if {@code uri} is not an absolute HTTP(S) URL
     */
    public static URI withTrailingSlash(final URI uri, final String name) {
        requireNonNull(uri, name);
        final HttpUrl url = requireHttpUrl(uri, name);
        final String path = url.encodedPath();
        if (path.endsWith("/")) {
            return url.uri();
        }
        return url.newBuilder().encodedPath(path + "/").build().uri();
    }

    /**
     * Resolves {@code relativePath} against {@code baseWithTrailingSlash} and appends {@code query}.
     *
     * @param baseWithTrailingSlash base URI whose path ends with {@code /}
     * @param relativePath path without a leading {@code /}
     * @param query query parameters
     * @return absolute URI
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the base is not an absolute HTTP(S) URL
     * @throws IllegalArgumentException if {@code relativePath} does not resolve to an HTTP(S) URL
     */
    public static URI resolve(
            final URI baseWithTrailingSlash, final String relativePath, final Map<String, List<String>> query) {
        requireNonNull(baseWithTrailingSlash, BASE_URI);
        requireNonNull(relativePath, PATH);
        requireNonNull(query, QUERY);
        return resolveHttpUrl(requireHttpUrl(baseWithTrailingSlash, BASE_URI), relativePath, query)
                .uri();
    }

    /**
     * Resolves {@code reference} against absolute HTTP(S) {@code base}.
     *
     * @param base current request URI
     * @param reference {@code Location} or other URI reference
     * @return absolute URI
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code base} is not an absolute HTTP(S) URL
     * @throws IllegalArgumentException if {@code reference} cannot be resolved
     */
    public static URI resolveReference(final URI base, final String reference) {
        requireNonNull(base, "base");
        requireNonNull(reference, "reference");
        final HttpUrl url = requireHttpUrl(base, "base");
        final HttpUrl resolved = url.resolve(reference);
        if (resolved == null) {
            throw new IllegalArgumentException("reference must resolve against the current request URI");
        }
        return resolved.uri();
    }

    /**
     * Resolves {@code path} against {@code baseWithTrailingSlash} and appends {@code query}.
     *
     * <p>Strips one leading {@code /}. The path must be a relative API path: it may not start with {@code //}, carry a
     * scheme, or contain a query ({@code ?}), a fragment ({@code #}) or a dot segment. Put the query in {@code query}.
     *
     * @param baseWithTrailingSlash base URI whose path ends with {@code /}
     * @param path API path or URI reference
     * @param query query parameters
     * @return absolute URI
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the base is not an absolute HTTP(S) URL, {@code path} is not a relative API
     *     path (see above), or {@code path} does not resolve to an HTTP(S) URL, for example {@code mailto:x}
     */
    public static URI resolveApiPath(
            final URI baseWithTrailingSlash, final String path, final Map<String, List<String>> query) {
        requireNonNull(path, PATH);
        return resolveApiHttpUrl(requireHttpUrl(baseWithTrailingSlash, BASE_URI), path, query)
                .uri();
    }

    /**
     * Removes RFC 6570 URI Template expansions and requires an absolute HTTP(S) URL.
     *
     * @param uri absolute HTTP(S) URI string, optionally containing template expansions
     * @return URI with template text stripped
     * @throws NullPointerException if {@code uri} is {@code null}
     * @throws IllegalArgumentException if {@code uri} is not an HTTP(S) URL after stripping
     */
    public static URI stripUriTemplate(final String uri) {
        requireNonNull(uri, URI_ARG);
        final String stripped = URI_TEMPLATE.matcher(uri).replaceAll("");
        final HttpUrl parsed = HttpUrl.parse(stripped);
        if (parsed == null) {
            throw new IllegalArgumentException("uri must be an absolute HTTP(S) URL");
        }
        return parsed.uri();
    }

    /**
     * Requires {@code uri} to be absolute.
     *
     * @param uri URI to check
     * @return {@code uri}
     * @throws NullPointerException if {@code uri} is {@code null}
     * @throws IllegalArgumentException if {@code uri} is relative
     */
    public static URI requireAbsolute(final URI uri) {
        requireNonNull(uri, URI_ARG);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("uri must be absolute: " + describeSafe(uri));
        }
        return uri;
    }

    /**
     * Raw path of {@code uri}, or an empty string when it has none, as for {@code mailto:} or {@code https://host}
     * written without a path.
     *
     * @param uri URI to read
     * @return raw path, never {@code null}
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    public static String rawPathOrEmpty(final URI uri) {
        final String path = requireNonNull(uri, URI_ARG).getRawPath();
        return path == null ? "" : path;
    }

    /**
     * Copies {@code query} into a new mutable map, in the order of {@code query}, with immutable value lists. Callers
     * that share the result wrap it with {@link java.util.Collections#unmodifiableMap}.
     *
     * @param query name to values
     * @return copy
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public static Map<String, List<String>> copyQueryValues(final Map<String, List<String>> query) {
        requireNonNull(query, QUERY);
        final Map<String, List<String>> copy = new LinkedHashMap<>();
        query.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return copy;
    }

    /**
     * Resolves {@code path} against {@code baseWithTrailingSlash} and appends {@code query}, keeping the parsed form.
     *
     * <p>Strips one leading {@code /}. The path must be a relative API path: it may not start with {@code //}, carry a
     * scheme, or contain a query ({@code ?}), a fragment ({@code #}) or a dot segment. Put the query in {@code query}.
     * Whatever the text of {@code path}, the result must be on the origin of the base and below its path, so a
     * backslash or leading space that makes the path leave them is refused too.
     *
     * @param baseWithTrailingSlash base URL whose path ends with {@code /}
     * @param path API path or URI reference
     * @param query query parameters
     * @return absolute URL
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the path of the base does not end with {@code /}, or {@code path} does not
     *     resolve to an HTTP(S) URL
     */
    public static HttpUrl resolveApiHttpUrl(
            final HttpUrl baseWithTrailingSlash, final String path, final Map<String, List<String>> query) {
        requireNonNull(path, PATH);
        if (!requireNonNull(baseWithTrailingSlash, BASE_URI).encodedPath().endsWith("/")) {
            throw new IllegalArgumentException("the base URL path must end with /");
        }
        requireApiPath(path);
        final String relative = path.startsWith("/") ? path.substring(1) : path;
        final HttpUrl resolved = resolveHttpUrl(baseWithTrailingSlash, relative, query);
        requireUnderBase(baseWithTrailingSlash, resolved);
        return resolved;
    }

    private static @Nullable String authorityWithoutUserInfo(final URI uri) {
        final String host = uri.getHost();
        if (host != null) {
            return host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        }
        final String raw = uri.getRawAuthority();
        if (raw == null) {
            return null;
        }
        final String withoutUserInfo = raw.substring(raw.lastIndexOf('@') + 1);
        return withoutUserInfo.isEmpty() ? null : withoutUserInfo;
    }

    // OkHttp reads a backslash as a slash and trims leading and trailing control characters and spaces, so the text
    // checks of requireApiPath do not see everything that resolution does. What counts is where the path ends up: on
    // the origin of the base and below its path.
    private static void requireUnderBase(final HttpUrl base, final HttpUrl resolved) {
        final boolean isSameOrigin = resolved.scheme().equals(base.scheme())
                && resolved.host().equals(base.host())
                && resolved.port() == base.port();
        if (!isSameOrigin || !resolved.encodedPath().startsWith(base.encodedPath())) {
            throw new IllegalArgumentException(API_PATH_MESSAGE);
        }
    }

    // The base and the query map decide the origin, the path below the base and the query, so a path that carries any
    // of them, or a dot segment that could leave the base path, is refused rather than resolved.
    @SuppressWarnings("PMD.CyclomaticComplexity") // one flat list of the forbidden shapes, clearer than split up
    private static void requireApiPath(final String path) {
        if (path.startsWith("//")
                || API_SCHEME.matcher(path).lookingAt()
                || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException(API_PATH_MESSAGE);
        }
        for (final String segment : path.split("/", -1)) {
            final String decoded = segment.replace("%2e", ".").replace("%2E", ".");
            if (".".equals(decoded) || "..".equals(decoded)) {
                throw new IllegalArgumentException(API_PATH_MESSAGE);
            }
        }
    }

    private static HttpUrl resolveHttpUrl(
            final HttpUrl baseWithTrailingSlash, final String relativePath, final Map<String, List<String>> query) {
        requireNonNull(baseWithTrailingSlash, BASE_URI);
        requireNonNull(relativePath, PATH);
        requireNonNull(query, QUERY);
        final HttpUrl resolved = baseWithTrailingSlash.resolve(relativePath);
        if (resolved == null) {
            // OkHttp returns null for a path that parses as another scheme, such as mailto:x or ftp://h/x.
            throw new IllegalArgumentException("path must be a relative path or an http(s) URI reference");
        }
        final HttpUrl.Builder builder = resolved.newBuilder();
        addQueryParameters(builder, query);
        return builder.build();
    }

    private static void addQueryParameters(final HttpUrl.Builder builder, final Map<String, List<String>> query) {
        query.forEach((name, values) -> values.forEach(value -> builder.addQueryParameter(name, value)));
    }

    private static HttpUrl requireHttpUrl(final URI uri, final String name) {
        requireNonNull(uri, name);
        final HttpUrl url = HttpUrl.get(uri);
        if (url == null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL");
        }
        return url;
    }
}
