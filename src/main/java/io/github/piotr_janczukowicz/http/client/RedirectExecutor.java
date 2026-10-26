package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a request and follows its HTTP redirects under origin, hop, method, and header policy.
 *
 * <p>301, 302, and 303 are followed for GET and HEAD. 301 and 302 are also followed for PUT, PATCH, and DELETE with the
 * method and body kept, which is how an API redirects a renamed or moved resource. For POST, and for any non-GET/HEAD
 * method on 303, they are not followed, because the conventional rewrite to GET would silently turn a write into a
 * read: the call fails with the status and the {@code Location} without query. A caller that disabled following gets
 * the redirect response itself. 307 and 308 keep method and body. Cross-origin hops are allowed only for configured
 * methods, drop sticky auth, and keep the policy header allowlist. HTTPS to HTTP is refused. When a redirect drops the
 * body, {@code Content-Type}, {@code Content-Length}, and {@code Content-Encoding} are dropped too.
 *
 * <p>Redirect failures report the request URI and the {@code Location} without user info, query, or fragment, because
 * they often carry signed tokens. Each followed hop is logged at debug level in that same form.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
        justification = "redirect policy failures are mapped to an unchecked API exception")
final class RedirectExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RedirectExecutor.class);

    private static final URI UNSUPPORTED_URI = URI.create("urn:unsupported-uri");
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");
    private static final String[] BODY_HEADERS = {CONTENT_LENGTH, CONTENT_ENCODING};
    private static final String ENTITY_TYPE = CONTENT_TYPE.toLowerCase(Locale.ROOT);
    private static final String ENTITY_ENCODING = CONTENT_ENCODING.toLowerCase(Locale.ROOT);

    private final HttpTransport transport;
    private final RedirectPolicy policy;
    private final HttpErrorMapper errors;
    private final ParsedUrl apiBase;

    /**
     * Executor over {@code transport}.
     *
     * @param transport HTTP transport
     * @param policy redirect policy
     * @param errors maps redirect failures
     */
    RedirectExecutor(
            final HttpTransport transport,
            final RedirectPolicy policy,
            final HttpErrorMapper errors,
            final ParsedUrl apiBase) {
        this.transport = requireNonNull(transport, "transport");
        this.policy = requireNonNull(policy, "policy");
        this.errors = requireNonNull(errors, "errors");
        this.apiBase = requireNonNull(apiBase, "apiBase");
    }

    Exchange execute(final HttpRequest request, final int hopLimit, final boolean follow) throws IOException {
        final Followed<HttpResponse> followed = follow(request, hopLimit, follow, new BufferedHops(transport));
        return new Exchange(followed.response(), followed.url(), followed.isSanitized());
    }

    /**
     * Like {@link #execute} but the final response is opened as a stream: a successful body is not read, other
     * responses are buffered. The caller must close the returned response. Superseded redirect responses are closed
     * here.
     */
    StreamExchange stream(final HttpRequest request, final int hopLimit, final boolean follow) throws IOException {
        final Followed<HttpStreamResponse> followed = follow(request, hopLimit, follow, new StreamedHops(transport));
        return new StreamExchange(followed.response(), followed.url(), followed.isSanitized());
    }

    URI requireRedirectLocation(final Exchange exchange, final HttpMethod method) {
        requireNonNull(exchange, "exchange");
        requireNonNull(method, "method");
        if (!isRedirectStatus(exchange.response().statusCode())) {
            throw new IllegalStateException("response is not a redirect");
        }
        return resolveRedirectLocation(exchange.url(), exchange.response(), method)
                .toUri();
    }

    static boolean isRedirectStatus(final int statusCode) {
        return statusCode == HttpStatus.MOVED_PERMANENTLY.code()
                || statusCode == HttpStatus.FOUND.code()
                || statusCode == HttpStatus.SEE_OTHER.code()
                || statusCode == HttpStatus.TEMPORARY_REDIRECT.code()
                || statusCode == HttpStatus.PERMANENT_REDIRECT.code();
    }

    private <R> Followed<R> follow(
            final HttpRequest request, final int hopLimit, final boolean follow, final HopHandler<R> hops)
            throws IOException {
        requireNonNull(request, "request");
        HttpRequest current = request;
        R last = hops.send(current);
        if (!follow) {
            return new Followed<>(last, current.url(), false);
        }

        final ParsedUrl origin = request.url();
        boolean leftOrigin = false;
        int hopCount = 0;
        while (isRedirectStatus(hops.statusCode(last))) {
            // A redirect is never a streamed response, so this does not read a body that was meant to stream.
            final HttpResponse response = hops.redirectView(last);
            if (hopCount >= hopLimit) {
                throw errors.redirectFailure(
                        response, "redirect hop limit exceeded", null, request.method(), safeUri(current.uri()));
            }
            final Hop hop = nextHop(current, response, origin, leftOrigin);
            logHop(response.statusCode(), hop.request().url(), hopCount + 1);
            current = hop.request();
            leftOrigin = hop.leftOrigin();
            last = hops.send(current);
            hopCount++;
        }
        // After a cross-origin hop the final URI may carry a signed query meant for another host; keep only its path.
        return new Followed<>(last, current.url(), leftOrigin);
    }

    private Hop nextHop(
            final HttpRequest current, final HttpResponse response, final ParsedUrl origin, final boolean leftOrigin) {
        requireMethodPreservingRedirect(current, response);
        final ParsedUrl nextUrl = resolveRedirectLocation(current.url(), response, current.method());
        final boolean leavesAuthority = requireFollowable(current, response, nextUrl, origin);
        final boolean hasLeftOrigin = leftOrigin || leavesAuthority;
        return new Hop(redirectRequest(current, nextUrl, response.statusCode(), hasLeftOrigin), hasLeftOrigin);
    }

    /**
     * Refuses a downgrade to http and a hop the policy does not allow, and tells whether the hop leaves the authority:
     * a cross-origin hop, or a same-origin hop of the API origin that leaves the API base path (such as a download link
     * beside the API). A hop that leaves the authority is followed without the credentials, and only for a method the
     * policy allows across origins.
     */
    private boolean requireFollowable(
            final HttpRequest current, final HttpResponse response, final ParsedUrl nextUrl, final ParsedUrl origin) {
        if (current.url().isHttps() && !nextUrl.isHttps()) {
            throw errors.redirectFailure(
                    response, "https to http redirect is not followed", null, current.method(), safeUri(current.uri()));
        }
        final boolean isCrossOrigin = !origin.hasSameOrigin(nextUrl);
        final boolean leavesApiPath = !isCrossOrigin && origin.hasSameOrigin(apiBase) && !isUnderApiBase(nextUrl);
        if (!isCrossOrigin && !leavesApiPath) {
            return false;
        }
        if (!policy.crossOriginMethods().contains(current.method())) {
            final String reason = isCrossOrigin ? "cross-origin redirect" : "redirect outside the API base path";
            throw errors.redirectFailure(
                    response,
                    reason + " not followed for " + current.method(),
                    null,
                    current.method(),
                    safeUri(current.uri()));
        }
        return true;
    }

    // The base path ends with a slash, so a path under it starts with the same prefix.
    private boolean isUnderApiBase(final ParsedUrl nextUrl) {
        return nextUrl.encodedPath().startsWith(apiBase.encodedPath());
    }

    private static void logHop(final int statusCode, final ParsedUrl nextUrl, final int hop) {
        LOG.atDebug()
                .setMessage("Following redirect {} to {} (hop {})")
                .addArgument(statusCode)
                .addArgument(nextUrl::describeSafe)
                .addArgument(hop)
                .log();
    }

    private ParsedUrl resolveRedirectLocation(
            final ParsedUrl current, final HttpResponse response, final HttpMethod method) {
        final String location = response.header(LOCATION)
                .orElseThrow(() -> errors.redirectFailure(
                        response, "redirect response missing Location header", null, method, safeUri(current.toUri())));
        final ParsedUrl resolved = current.resolve(location);
        if (resolved == null) {
            // The parser's message would quote the Location, which can carry a signed token; the failure reports a
            // sanitised form instead.
            throw errors.redirectFailure(
                    response,
                    redirectFailureMessage(location),
                    safeLocation(location),
                    method,
                    safeUri(current.toUri()));
        }
        return resolved;
    }

    // 303, and 301/302 of a POST, would turn a write into a GET; refuse instead of silently dropping the write.
    private void requireMethodPreservingRedirect(final HttpRequest current, final HttpResponse response) {
        final int status = response.statusCode();
        final boolean downgrades = status == HttpStatus.SEE_OTHER.code()
                || (current.method() == HttpMethod.POST
                        && (status == HttpStatus.MOVED_PERMANENTLY.code() || status == HttpStatus.FOUND.code()));
        if (downgrades && !current.method().isBodyless()) {
            throw errors.redirectFailure(
                    response,
                    "HTTP " + status + " redirect of a " + current.method()
                            + " request is not followed because it would be sent as GET",
                    response.header(LOCATION)
                            .map(RedirectExecutor::safeLocation)
                            .orElse(null),
                    current.method(),
                    safeUri(current.uri()));
        }
    }

    private static @Nullable String safeLocation(final String location) {
        try {
            final URI parsed = new URI(location);
            if (parsed.getHost() != null) {
                return HttpUris.describeSafe(parsed);
            }
            return HttpUris.rawPathOrEmpty(parsed);
        } catch (final URISyntaxException unused) {
            // A malformed Location has no safe form to report; the failure then omits it.
            return null;
        }
    }

    private static URI exchangeUri(final ParsedUrl url, final boolean isSanitized) {
        return isSanitized ? safeUri(url.toUri()) : url.toUri();
    }

    private static URI safeUri(final URI uri) {
        // A URI without a host has no safe form to keep: its query could carry a signed token.
        return uri.getHost() == null ? UNSUPPORTED_URI : URI.create(HttpUris.describeSafe(uri));
    }

    private static String redirectFailureMessage(final String location) {
        return hasUnsupportedScheme(location) ? "redirect to unsupported scheme" : "redirect Location is malformed";
    }

    private static boolean hasUnsupportedScheme(final String location) {
        try {
            return !isHttpScheme(new URI(location).getScheme());
        } catch (final URISyntaxException unused) {
            // Reported as malformed instead; the syntax error text would quote the Location.
            return false;
        }
    }

    private static boolean isHttpScheme(final @Nullable String scheme) {
        return scheme == null || HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    private HttpRequest redirectRequest(
            final HttpRequest current, final ParsedUrl nextUrl, final int statusCode, final boolean leftOrigin) {
        // Only GET and HEAD (any redirect status), PUT/PATCH/DELETE (301/302) or 307/308 (any method) get here; the
        // method never changes.
        final HttpMethod nextMethod = current.method();
        final boolean keepsBody = isBodyKept(statusCode, nextMethod);
        final HttpBody nextBody = keepsBody ? current.body() : HttpBody.absent();
        final HttpHeaders sent = leftOrigin ? crossOriginHeaders(current.headers(), keepsBody) : current.headers();
        final HttpHeaders nextHeaders = keepsBody ? sent : sent.without(CONTENT_TYPE, BODY_HEADERS);
        // The identity belongs to the credentials, which a cross-origin hop does not carry.
        final String credentialId = leftOrigin ? null : current.credentialId();
        return new HttpRequest(nextMethod, nextUrl, nextHeaders, nextBody, credentialId);
    }

    // The allowlist keeps credentials out. The media type and encoding of a body that goes along describe the entity,
    // not the sender, so they travel with it; without them the server cannot read the body.
    private HttpHeaders crossOriginHeaders(final HttpHeaders headers, final boolean keepsBody) {
        HttpHeaders kept = HttpHeaders.empty();
        for (final Map.Entry<String, String> entry : headers.asMap().entrySet()) {
            final String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (policy.crossOriginHeaderNames().contains(name)
                    || keepsBody && (name.equals(ENTITY_TYPE) || name.equals(ENTITY_ENCODING))) {
                kept = kept.with(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    private static boolean isBodyKept(final int statusCode, final HttpMethod method) {
        return statusCode == HttpStatus.TEMPORARY_REDIRECT.code()
                || statusCode == HttpStatus.PERMANENT_REDIRECT.code()
                || !method.isBodyless();
    }

    /**
     * Final response and the URL it came from. After a cross-origin hop the URL may carry a signed query meant for
     * another host, so {@link #uri()} then keeps only its origin and path.
     */
    record Exchange(HttpResponse response, ParsedUrl url, boolean isSanitized) {
        URI uri() {
            return exchangeUri(url, isSanitized);
        }
    }

    record StreamExchange(HttpStreamResponse response, ParsedUrl url, boolean isSanitized) {
        URI uri() {
            return exchangeUri(url, isSanitized);
        }
    }

    private record Followed<R>(R response, ParsedUrl url, boolean isSanitized) {}

    private record Hop(HttpRequest request, boolean leftOrigin) {}

    /** One kind of response (buffered or streamed) that {@link #follow} can walk through redirects. */
    private interface HopHandler<R> {
        R send(HttpRequest request) throws IOException;

        int statusCode(R response);

        /** The redirect response in buffered form; releases a streamed response. */
        HttpResponse redirectView(R response) throws IOException;
    }

    private record BufferedHops(HttpTransport transport) implements HopHandler<HttpResponse> {
        @Override
        public HttpResponse send(final HttpRequest request) throws IOException {
            return transport.execute(request);
        }

        @Override
        public int statusCode(final HttpResponse response) {
            return response.statusCode();
        }

        @Override
        public HttpResponse redirectView(final HttpResponse response) {
            return response;
        }
    }

    private record StreamedHops(HttpTransport transport) implements HopHandler<HttpStreamResponse> {
        @Override
        public HttpStreamResponse send(final HttpRequest request) throws IOException {
            return transport.stream(request);
        }

        @Override
        public int statusCode(final HttpStreamResponse response) {
            return response.statusCode();
        }

        @Override
        public HttpResponse redirectView(final HttpStreamResponse response) throws IOException {
            final HttpResponse buffered = response.bufferedResponse();
            response.close();
            return buffered;
        }
    }
}
