package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Accepts or rejects absolute URIs against the API base origin and path prefix. */
@SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "invalid baseUri must fail construction")
public class HttpOriginGuard {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOriginGuard.class);
    private static final String HTTPS = "https";

    /** Scheme of the base URI: {@code http} or {@code https}. */
    protected final String scheme;

    /** Host of the base URI, in lower case or as written, without brackets for an IPv6 address. */
    protected final String host;

    /** Effective port of the base URI: the explicit port, or the default of its scheme. */
    protected final int port;

    /** Raw path of the base URI with a trailing {@code /}; an accepted URI must lie under it. */
    protected final String basePath;

    /**
     * Guard for {@code baseUri}. A base path without a trailing slash gets one, so {@code /api/v3} accepts
     * {@code /api/v3/x} but not {@code /api/v3evil/x}.
     *
     * @param baseUri API base URI
     * @throws NullPointerException if {@code baseUri}, its scheme or its host is {@code null}; {@link URI#getHost()}
     *     returns {@code null} for a host it cannot read, for example an internationalized host name, which must be
     *     written in punycode
     * @throws IllegalArgumentException if the scheme is not http or https
     */
    public HttpOriginGuard(final URI baseUri) {
        requireNonNull(baseUri, "baseUri");
        this.scheme = requireHttpScheme(requireNonNull(baseUri.getScheme(), "scheme"));
        requireNonNull(baseUri.getHost(), "host");
        // Parsed like the targets, so a host or path that OkHttp writes differently, such as [0:0:0:0:0:0:0:1], still
        // matches.
        final ParsedUrl base = ParsedUrl.of(baseUri);
        this.host = base.host();
        this.port = base.port();
        this.basePath = withTrailingSlash(base.encodedPath());
    }

    /**
     * Allows {@code uri} for {@code method}, given its already parsed form, so the URI is not parsed twice. The
     * dot-segment check runs on the raw {@code uri}, the origin and path prefix check on {@code parsed}, as in
     * {@link #requireApiTarget(ParsedUrl)}. A path containing a {@code .} or {@code ..} segment, literal or
     * percent-encoded, is rejected because the server would resolve it outside the checked prefix.
     *
     * <p>For {@link HttpCall.Path} targets OkHttp already normalises {@code ..} and {@code %2e%2e} while resolving,
     * before this guard runs, so for them the dot-segment check is unreachable and only the base path prefix check
     * keeps a {@code ../} escape out of a base such as {@code /api/v3/}. The dot-segment check still protects absolute
     * URI targets and subclasses that skip resolution. Subclasses that admit other targets override this method.
     *
     * @param method HTTP method
     * @param uri absolute URI, as supplied
     * @param parsed {@link ParsedUrl#parse(URI) ParsedUrl.parse(uri)}, or {@code null} when {@code uri} is not an
     *     absolute HTTP(S) URL
     * @return the parsed target
     * @throws NullPointerException if {@code method} or {@code uri} is {@code null}
     * @throws HttpOriginViolationException if {@code uri} is not HTTP(S), has a dot segment, is off origin or outside
     *     the base path
     */
    public ParsedUrl requireTarget(final HttpMethod method, final URI uri, final @Nullable ParsedUrl parsed) {
        requireNonNull(method, "method");
        requireNonNull(uri, "uri");
        if (parsed == null || hasDotSegments(uri)) {
            throw violation(HttpUris.describeSafe(uri));
        }
        return requireApiTarget(parsed);
    }

    /**
     * Allows {@code url} for {@code method}. Default: same as {@link #requireApiTarget(ParsedUrl)}.
     *
     * @param method HTTP method
     * @param url absolute URL
     * @return {@code url}
     */
    public ParsedUrl requireTarget(final HttpMethod method, final ParsedUrl url) {
        requireNonNull(method, "method");
        return requireApiTarget(url);
    }

    /**
     * Accepts {@code url} when it is on the API origin and under the base path. The URL is already normalised, so a
     * {@code .} or {@code ..} segment, literal or percent-encoded, has been resolved before this check and cannot lead
     * outside the checked prefix.
     *
     * @param url absolute URL
     * @return {@code url}
     * @throws NullPointerException if {@code url} is {@code null}
     * @throws HttpOriginViolationException if {@code url} is off origin or outside the base path
     */
    public ParsedUrl requireApiTarget(final ParsedUrl url) {
        requireNonNull(url, "url");
        if (isOffOrigin(url) || !url.encodedPath().startsWith(basePath)) {
            throw violation(url.describeSafe());
        }
        return url;
    }

    /**
     * Whether {@code actual} differs from the base URI in scheme, host, or effective port. Subclasses may widen what
     * counts as the same origin.
     *
     * @param actual absolute URL to test
     * @return {@code true} if {@code actual} is not on the API origin
     */
    protected boolean isOffOrigin(final ParsedUrl actual) {
        return !scheme.equalsIgnoreCase(actual.scheme())
                || !host.equalsIgnoreCase(actual.host())
                || port != actual.port();
    }

    /**
     * Whether the raw path of {@code uri} has a {@code .} or {@code ..} segment, also when percent-encoded.
     *
     * @param uri absolute URI
     * @return {@code true} if a dot segment is present
     */
    protected static boolean hasDotSegments(final URI uri) {
        for (final String segment : HttpUris.rawPathOrEmpty(uri).split("/", -1)) {
            final String decoded = segment.replace("%2e", ".").replace("%2E", ".");
            if (".".equals(decoded) || "..".equals(decoded)) {
                return true;
            }
        }
        return false;
    }

    private static String withTrailingSlash(final String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private HttpOriginViolationException violation(final String describedTarget) {
        LOG.atDebug()
                .setMessage("Refused {}: it is not under the API base {}://{}:{}{}")
                .addArgument(describedTarget)
                .addArgument(scheme)
                .addArgument(host)
                .addArgument(port)
                .addArgument(basePath)
                .log();
        return new HttpOriginViolationException("request URI is not under the API base URI: " + describedTarget);
    }

    private static String requireHttpScheme(final String scheme) {
        if ("http".equalsIgnoreCase(scheme) || HTTPS.equalsIgnoreCase(scheme)) {
            return scheme;
        }
        throw new IllegalArgumentException("baseUri scheme must be http or https: " + scheme);
    }
}
