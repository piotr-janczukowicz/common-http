package pl.piotrjanczukowicz.http.client;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.http.HttpHeaderNames;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.client.HttpCall.AbsoluteUri;
import pl.piotrjanczukowicz.http.client.HttpCall.Path;
import pl.piotrjanczukowicz.http.client.RedirectExecutor.Exchange;
import pl.piotrjanczukowicz.http.client.RedirectExecutor.StreamExchange;
import pl.piotrjanczukowicz.http.client.authentication.Authentication;
import pl.piotrjanczukowicz.http.client.authentication.AuthenticationProvider;
import pl.piotrjanczukowicz.http.client.authentication.AuthenticationProviders;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.RateLimitGateHeaders;
import pl.piotrjanczukowicz.http.uri.HttpUris;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;

/**
 * HTTP client for a REST API over an injected {@link HttpTransport}.
 *
 * <p>Header precedence, lowest to highest: configured default headers, auth headers, overlay headers on the call.
 * Default headers never replace an auth header of the same name (for example {@code Authorization}); an overlay header
 * set explicitly on one call does, which is the only way to send a different credential for a single request.
 *
 * <p>HTTP 2xx become {@link HttpResponse}. Unsuccessful statuses become the exception from {@link HttpErrorMapper}.
 * Transport failures are {@link IOException}.
 *
 * <p>Thread safety: a client can be shared between threads when its transport, its {@link AuthenticationProvider} and
 * its observers are safe for concurrent use; the client keeps no per-call state of its own.
 *
 * <p>The client does not build its transport: retries and other decorators are composed around the transport by the
 * caller. The client owns it from then on, and {@link #close()} closes it. Subclasses add API-specific policies through
 * the constructors and do not override the request methods.
 */
public class HttpClient implements HttpClientExecutor, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);
    private static final String CALL = "call";
    private static final String THROWS_RUNTIME_EXCEPTION = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION";

    private final HttpTransport transport;
    private final HttpClientConfiguration clientConfiguration;
    private final ParsedUrl baseUrl;
    private final HttpOriginGuard httpOriginGuard;
    private final RedirectExecutor redirectExecutor;
    private final HttpErrorMapper errorMapper;
    private final RedirectPolicy redirectPolicy;
    private final AuthenticationProvider authenticationProvider;
    private final Consumer<HttpHeaders> responseHeadersObserver;
    private final @Nullable HttpCallObserver callObserver;

    /**
     * Client from {@code builder}.
     *
     * @param builder collected settings
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    protected HttpClient(final Builder builder) {
        this(requireNonNull(builder, "builder").settings());
    }

    private HttpClient(final Settings settings) {
        this.transport = settings.transport();
        this.clientConfiguration = settings.configuration();
        this.baseUrl = clientConfiguration.baseUrl();
        this.httpOriginGuard = settings.originGuard();
        this.redirectPolicy = settings.redirectPolicy();
        this.errorMapper = settings.errors();
        this.authenticationProvider = settings.auth();
        this.responseHeadersObserver = settings.responseHeadersObserver();
        this.callObserver = settings.callObserver();
        this.redirectExecutor = new RedirectExecutor(transport, this.redirectPolicy, this.errorMapper, this.baseUrl);
    }

    /**
     * Builder over {@code transport}. Retrying and any other transport decorators are applied to {@code transport}
     * before it is passed in.
     *
     * @param transport transport the client sends requests through
     * @param configuration client configuration
     * @return builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Builder builder(final HttpTransport transport, final HttpClientConfiguration configuration) {
        return new Builder(transport, configuration);
    }

    /** Closes the transport; every later call fails with an {@link IOException}. Idempotent. */
    @Override
    public void close() {
        transport.close();
    }

    @Override
    @SuppressFBWarnings(
            value = THROWS_RUNTIME_EXCEPTION,
            justification = "unsuccessful HTTP status is mapped to an unchecked API exception")
    public final HttpResponse execute(
            final HttpCall call, final Set<Integer> expectedStatuses, final boolean followRedirects)
            throws IOException {
        requireNonNull(call, CALL);
        requireNonNull(expectedStatuses, "expectedStatuses")
                .forEach(status -> requireNonNull(status, "expectedStatuses element"));
        final Set<Integer> allowed = Set.copyOf(expectedStatuses);
        final Exchange exchange = exchange(call, followRedirects);
        if (exchange.response().isSuccessful()
                || allowed.contains(exchange.response().statusCode())) {
            return exchange.response();
        }
        throw errorMapper.map(call.method(), exchange.response(), exchange.uri());
    }

    @Override
    @SuppressFBWarnings(
            value = THROWS_RUNTIME_EXCEPTION,
            justification = "unsuccessful HTTP status is mapped to an unchecked API exception")
    public final <T> T stream(final HttpCall call, final HttpStreamHandler<T> handler) throws IOException {
        requireNonNull(call, CALL);
        requireNonNull(handler, "handler");
        final HttpRequest sent = httpRequest(call);
        final long started = System.nanoTime();
        final StreamExchange exchange;
        try {
            exchange = streamRenewingAuth(call, sent);
        } catch (final IOException | RuntimeException failure) {
            observeFailure(sent, started, failure);
            throw failure;
        }
        final AtomicReference<T> handled = new AtomicReference<>();
        final AtomicBoolean isHandled = new AtomicBoolean();
        try (HttpStreamResponse response = exchange.response()) {
            observeResponse(sent, started, response.statusCode(), response.headers());
            if (isObserved(call, exchange.isSanitized(), response.headers())) {
                responseHeadersObserver.accept(response.headers());
            }
            if (!response.isSuccessful()) {
                throw errorMapper.map(call.method(), response.bufferedResponse(), exchange.uri());
            }
            handled.set(handler.handle(response));
            isHandled.set(true);
            return handled.get();
        } catch (final IOException failure) {
            // The handler has returned its result, so a failing close of the finished body does not undo it.
            if (!isHandled.get()) {
                throw failure;
            }
            LOG.atDebug()
                    .setMessage("{} {}: closing the response body failed with {}")
                    .addArgument(sent.method())
                    .addArgument(sent.url()::describeSafe)
                    .addArgument(failure.getClass().getName())
                    .log();
            return handled.get();
        }
    }

    @Override
    public final URI baseUri() {
        return clientConfiguration.baseUri();
    }

    /**
     * Sends {@code call} without following redirects and returns the {@code Location} from a 301/302/303/307/308.
     *
     * @param call call to send
     * @return redirect target
     * @throws IOException if the transport fails
     * @throws IllegalStateException if the response is successful (2xx, for example 200) and so is not a redirect
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     */
    @Override
    public final URI redirectLocation(final HttpCall call) throws IOException {
        requireNonNull(call, CALL);
        final Exchange exchange = exchange(call, false);
        return redirectOf(call, exchange)
                .orElseThrow(() -> new IllegalStateException("expected a redirect but got HTTP "
                        + exchange.response().statusCode() + " for " + call.method() + " "
                        + HttpUris.describeSafe(exchange.uri())));
    }

    /**
     * Sends {@code call} without following redirects and returns the {@code Location} from a 301/302/303/307/308, or
     * empty when the response is successful (2xx) and so is not a redirect.
     *
     * @param call call to send
     * @return redirect target, or empty for a 2xx response
     * @throws IOException if the transport fails
     * @throws RuntimeException if the response is an error status, or {@code Location} is missing, malformed, or uses a
     *     non-http(s) scheme
     */
    @Override
    public final Optional<URI> redirectLocationIfAny(final HttpCall call) throws IOException {
        requireNonNull(call, CALL);
        return redirectOf(call, exchange(call, false));
    }

    @SuppressFBWarnings(
            value = THROWS_RUNTIME_EXCEPTION,
            justification = "redirect failures are mapped to an unchecked API exception")
    private Optional<URI> redirectOf(final HttpCall call, final Exchange exchange) {
        if (RedirectExecutor.isRedirectStatus(exchange.response().statusCode())) {
            return Optional.of(redirectExecutor.requireRedirectLocation(exchange, call.method()));
        }
        if (exchange.response().isSuccessful()) {
            return Optional.empty();
        }
        throw errorMapper.map(call.method(), exchange.response(), exchange.uri());
    }

    private Exchange exchange(final HttpCall call, final boolean follow) throws IOException {
        final HttpRequest sent = httpRequest(call);
        final long started = System.nanoTime();
        final Exchange exchange;
        try {
            exchange = executeRenewingAuth(call, sent, follow);
        } catch (final IOException | RuntimeException failure) {
            observeFailure(sent, started, failure);
            throw failure;
        }
        observeResponse(
                sent,
                started,
                exchange.response().statusCode(),
                exchange.response().headers());
        // A response that the rate-limit gate replayed carries the headers of an earlier response, not new quota.
        if (isObserved(call, exchange.isSanitized(), exchange.response().headers())) {
            responseHeadersObserver.accept(exchange.response().headers());
        }
        return exchange;
    }

    // A 401 means nothing was processed, so a request the auth asks to renew is sent again, once, with new credentials.
    private Exchange executeRenewingAuth(final HttpCall call, final HttpRequest sent, final boolean follow)
            throws IOException {
        final Exchange first = redirectExecutor.execute(sent, redirectPolicy.maxHops(), follow);
        if (isFinal(call, first.response().statusCode(), first.isSanitized(), sent)) {
            return first;
        }
        logRenewal(sent);
        final HttpRequest renewed = renewedRequest(call, first.response(), first.uri());
        return redirectExecutor.execute(renewed, redirectPolicy.maxHops(), follow);
    }

    // The 401 is answered by renewing the credentials. When that fails, for example while a token fetch is in its
    // backoff, the failure is the cause the caller needs to see, and the rejection that led to it stays attached to it.
    private HttpRequest renewedRequest(final HttpCall call, final HttpResponse rejected, final URI uri)
            throws IOException {
        try {
            return httpRequest(call);
        } catch (final IOException | RuntimeException failure) {
            failure.addSuppressed(errorMapper.map(call.method(), rejected, uri));
            throw failure;
        }
    }

    @SuppressFBWarnings(
            value = THROWS_RUNTIME_EXCEPTION,
            justification = "rethrows the failure of the auth unchanged, after closing the response")
    private StreamExchange streamRenewingAuth(final HttpCall call, final HttpRequest sent) throws IOException {
        final StreamExchange first = redirectExecutor.stream(sent, redirectPolicy.maxHops(), true);
        final boolean isFinal;
        try {
            isFinal = isFinal(call, first.response().statusCode(), first.isSanitized(), sent);
        } catch (final RuntimeException failure) {
            try {
                first.response().close();
            } catch (final IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        if (isFinal) {
            return first;
        }
        final HttpResponse rejected = first.response().bufferedResponse();
        first.response().close();
        logRenewal(sent);
        final HttpRequest renewed = renewedRequest(call, rejected, first.uri());
        return redirectExecutor.stream(renewed, redirectPolicy.maxHops(), true);
    }

    // The 401 of another origin, after a redirect, says nothing about our credentials: they were not sent there.
    private boolean isFinal(
            final HttpCall call, final int statusCode, final boolean isSanitized, final HttpRequest sent) {
        return statusCode != HttpStatus.UNAUTHORIZED.code() || isSanitized || !isRenewable(call, sent);
    }

    // Credentials that the call set itself are not the provider's to renew: it would send the same ones again.
    private boolean isRenewable(final HttpCall call, final HttpRequest sent) {
        return !hasOwnAuthorization(call)
                && sent.headers()
                        .get(HttpHeaderNames.AUTHORIZATION)
                        .flatMap(Authentication::parse)
                        .map(authenticationProvider::isRenewableAfterRejection)
                        .orElse(false);
    }

    private static void logRenewal(final HttpRequest sent) {
        LOG.atDebug()
                .setMessage("{} {} answered 401; credentials renewed, sending it once more")
                .addArgument(sent.method())
                .addArgument(sent.url()::describeSafe)
                .log();
    }

    // Monotonic: System.nanoTime() never goes backwards, unlike the wall clock.
    private static Duration elapsedSince(final long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static void logResponse(final HttpRequest sent, final long started, final int statusCode) {
        LOG.atDebug()
                .setMessage("{} {} -> HTTP {} in {} ms")
                .addArgument(sent.method())
                .addArgument(sent.url()::describeSafe)
                .addArgument(statusCode)
                .addArgument(() -> elapsedSince(started).toMillis())
                .log();
    }

    // Only the class of the failure is logged: its message can quote the request.
    private static void logFailure(final HttpRequest sent, final long started, final Throwable failure) {
        LOG.atDebug()
                .setMessage("{} {} failed with {} after {} ms")
                .addArgument(sent.method())
                .addArgument(sent.url()::describeSafe)
                .addArgument(failure.getClass().getName())
                .addArgument(() -> elapsedSince(started).toMillis())
                .log();
    }

    private void observeResponse(
            final HttpRequest sent, final long started, final int statusCode, final HttpHeaders headers) {
        logResponse(sent, started, statusCode);
        if (callObserver != null) {
            notifyObserver(HttpCallEvent.response(
                    sent.method(), sent.url().describeSafe(), statusCode, headers, elapsedSince(started)));
        }
    }

    // A failure that carries a response, such as a redirect the policy refused, keeps that response's status and
    // headers.
    private void observeFailure(final HttpRequest sent, final long started, final Throwable failure) {
        logFailure(sent, started, failure);
        if (callObserver == null) {
            return;
        }
        final String target = sent.url().describeSafe();
        final Duration elapsed = elapsedSince(started);
        if (failure instanceof final HttpException refused) {
            notifyObserver(new HttpCallEvent(
                    sent.method(), target, refused.statusCode(), refused.headers(), elapsed, failure));
        } else {
            notifyObserver(HttpCallEvent.failed(sent.method(), target, elapsed, failure));
        }
    }

    // An observer is for looking, so what it throws never reaches the caller of the request.
    @SuppressWarnings("java:S2259") // Only called when callObserver is set; requireNonNull never returns null.
    private void notifyObserver(final HttpCallEvent event) {
        try {
            requireNonNull(callObserver, "callObserver").onCall(event);
        } catch (final RuntimeException failure) {
            LOG.atWarn()
                    .setMessage("HTTP call observer failed with {}")
                    .addArgument(failure.getClass().getName())
                    .log();
        }
    }

    private HttpRequest httpRequest(final HttpCall call) throws IOException {
        final ParsedUrl url = resolveUrl(call);
        // A call that sets its own Authorization is sent for another principal than the client's. The client's
        // credentials are not asked for, because that could fetch a token or fail. The request also must not share the
        // identity of the client in the per-credential state of the transports.
        final Authentication authentication = hasOwnAuthorization(call) ? null : authentication();
        final HttpHeaders headers = buildHeaders(call.headers(), authentication);
        final String credentialId = authentication == null ? null : authentication.identity();
        return new HttpRequest(call.method(), url, headers, call.body(), credentialId);
    }

    private static boolean hasOwnAuthorization(final HttpCall call) {
        return call.headers().get(HttpHeaderNames.AUTHORIZATION).isPresent();
    }

    // A replayed response carries the headers of an earlier one, and a call with its own Authorization reports the
    // quota of another principal; neither is new information about the client's own.
    private static boolean isObserved(final HttpCall call, final boolean isSanitized, final HttpHeaders headers) {
        return !isSanitized && !hasOwnAuthorization(call) && !RateLimitGateHeaders.isPresent(headers);
    }

    private ParsedUrl resolveUrl(final HttpCall call) {
        return switch (call.target()) {
            case Path(final String path) ->
                httpOriginGuard.requireTarget(call.method(), baseUrl.resolveApi(path, call.query()));
            case AbsoluteUri(final URI uri) -> resolveAbsoluteUrl(call, uri);
        };
    }

    private ParsedUrl resolveAbsoluteUrl(final HttpCall call, final URI uri) {
        final Map<String, List<String>> query = call.query();
        final ParsedUrl parsed = ParsedUrl.parse(uri);
        if (parsed == null && !query.isEmpty()) {
            throw new IllegalArgumentException("uri must be an absolute HTTP(S) URL");
        }
        return httpOriginGuard.requireTarget(call.method(), uri, parsed).withQueryValues(query);
    }

    // Lowest to highest: the client's default headers, authentication, then the headers of the call.
    private HttpHeaders buildHeaders(final HttpHeaders callHeaders, final @Nullable Authentication authentication) {
        final HttpHeaders authenticationHeaders = authentication == null
                ? HttpHeaders.empty()
                : HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, authentication.headerValue());
        return clientConfiguration.defaultHeaders().merge(authenticationHeaders, callHeaders);
    }

    /**
     * Authentication, with a transport failure while obtaining credentials surfaced as the checked {@link IOException};
     * {@code null} when there is none.
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // the cause is rethrown as is; the unchecked wrapper only carries it
    private @Nullable Authentication authentication() throws IOException {
        try {
            return authenticationProvider.authentication().orElse(null);
        } catch (final UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** Client factory with the transport and configuration fixed and optional policies and observers. */
    public static final class Builder {
        private final HttpTransport transport;
        private final HttpClientConfiguration configuration;
        private @Nullable HttpOriginGuard originGuard;
        private RedirectPolicy redirectPolicy = RedirectPolicy.defaults();
        private HttpErrorMapper errors = HttpException::from;
        private AuthenticationProvider auth = AuthenticationProviders.none();
        private Consumer<HttpHeaders> responseHeadersObserver = headers -> {};
        private @Nullable HttpCallObserver callObserver;

        private Builder(final HttpTransport transport, final HttpClientConfiguration configuration) {
            this.transport = requireNonNull(transport, "transport");
            this.configuration = requireNonNull(configuration, "configuration");
        }

        /**
         * Origin policy for resolved request URIs.
         *
         * @param originGuard decides which resolved request URIs may be sent; default accepts only the base origin
         * @return this builder
         */
        public Builder originGuard(final HttpOriginGuard originGuard) {
            this.originGuard = requireNonNull(originGuard, "originGuard");
            return this;
        }

        /**
         * Redirect following policy.
         *
         * @param redirectPolicy hop limit and the cross-origin rules; default {@link RedirectPolicy#defaults()}
         * @return this builder
         */
        public Builder redirectPolicy(final RedirectPolicy redirectPolicy) {
            this.redirectPolicy = requireNonNull(redirectPolicy, "redirectPolicy");
            return this;
        }

        /**
         * Maps unsuccessful responses.
         *
         * @param errors turns an unsuccessful response into the exception the caller receives
         * @return this builder
         */
        public Builder errorMapper(final HttpErrorMapper errors) {
            this.errors = requireNonNull(errors, "errors");
            return this;
        }

        /**
         * Authentication sent as the {@code Authorization} header over the default headers, so a default header cannot
         * replace it.
         *
         * @param auth source of the authentication, read on every request
         * @return this builder
         */
        public Builder auth(final AuthenticationProvider auth) {
            this.auth = requireNonNull(auth, "auth");
            return this;
        }

        /**
         * Observes the headers of the final response of every call, before the status is judged, so it also sees error
         * responses. A final response that came from another origin after a redirect, such as a download host, is not
         * observed: its headers are not the API's to report. Nor is the response to a call that sets its own
         * {@code Authorization} header, which belongs to another principal. Runs on the calling thread and must be
         * quick and thread-safe; an exception it throws propagates to the caller of the request.
         *
         * @param responseHeadersObserver called with the headers of each final response
         * @return this builder
         */
        public Builder responseHeadersObserver(final Consumer<HttpHeaders> responseHeadersObserver) {
            this.responseHeadersObserver = requireNonNull(responseHeadersObserver, "responseHeadersObserver");
            return this;
        }

        /**
         * Reports every finished call, with its method, target, status, response headers and duration, to
         * {@code callObserver}; see {@link HttpCallObserver}. None by default.
         *
         * @param callObserver receives one event per call that reached the transport
         * @return this builder
         * @throws NullPointerException if {@code callObserver} is {@code null}
         */
        public Builder callObserver(final HttpCallObserver callObserver) {
            this.callObserver = requireNonNull(callObserver, "callObserver");
            return this;
        }

        /**
         * Builds the client.
         *
         * @return client
         */
        public HttpClient build() {
            return new HttpClient(this);
        }

        private Settings settings() {
            final HttpOriginGuard guard =
                    originGuard == null ? new HttpOriginGuard(configuration.baseUri()) : originGuard;
            return new Settings(
                    transport,
                    configuration,
                    guard,
                    redirectPolicy,
                    errors,
                    auth,
                    responseHeadersObserver,
                    callObserver);
        }
    }

    private record Settings(
            HttpTransport transport,
            HttpClientConfiguration configuration,
            HttpOriginGuard originGuard,
            RedirectPolicy redirectPolicy,
            HttpErrorMapper errors,
            AuthenticationProvider auth,
            Consumer<HttpHeaders> responseHeadersObserver,
            @Nullable HttpCallObserver callObserver) {}
}
