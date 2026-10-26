package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.net.URI;

/**
 * Settings for an HTTP API client: base URI and default headers.
 *
 * <p>Authentication, the transport (timeouts, proxy, TLS, retries), and the JSON codec are not part of the
 * configuration: the client is built over them.
 *
 * @param baseUri API origin, with a trailing slash on the path
 * @param defaultHeaders headers sent on every request, before the overlay headers of a call; an auth header of the same
 *     name wins
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "HttpHeaders is immutable")
public record HttpClientConfiguration(URI baseUri, HttpHeaders defaultHeaders) {

    private static final String BASE_URI = "baseUri";

    public HttpClientConfiguration {
        baseUri = HttpUris.withTrailingSlash(requireNonNull(baseUri, BASE_URI), BASE_URI);
        requireNonNull(defaultHeaders, "defaultHeaders");
    }

    /**
     * Configuration without default headers.
     *
     * @param baseUri API origin, with a trailing slash on the path
     * @return configuration
     */
    public static HttpClientConfiguration of(final URI baseUri) {
        return new HttpClientConfiguration(baseUri, HttpHeaders.empty());
    }

    /**
     * Builder with empty default headers.
     *
     * @param baseUri API origin, with a trailing slash on the path
     * @return a new builder
     * @throws NullPointerException if {@code baseUri} is {@code null}
     */
    public static Builder builder(final URI baseUri) {
        return new Builder(baseUri);
    }

    /**
     * The base URI as a parsed URL: the target that API paths are resolved against. It is parsed on each call, so a
     * caller on a hot path keeps the result.
     *
     * @return parsed base URL
     */
    public ParsedUrl baseUrl() {
        return ParsedUrl.of(baseUri);
    }

    /** Builds an {@link HttpClientConfiguration}. */
    public static final class Builder {
        private final URI baseUri;
        private HttpHeaders defaultHeaders = HttpHeaders.empty();

        private Builder(final URI baseUri) {
            this.baseUri = requireNonNull(baseUri, BASE_URI);
        }

        /**
         * Sets default request headers.
         *
         * @param defaultHeaders headers sent on every request; an auth header of the same name wins
         * @return this builder
         * @throws NullPointerException if {@code defaultHeaders} is {@code null}
         */
        public Builder defaultHeaders(final HttpHeaders defaultHeaders) {
            this.defaultHeaders = requireNonNull(defaultHeaders, "defaultHeaders");
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return configuration
         */
        public HttpClientConfiguration build() {
            return new HttpClientConfiguration(baseUri, defaultHeaders);
        }
    }
}
