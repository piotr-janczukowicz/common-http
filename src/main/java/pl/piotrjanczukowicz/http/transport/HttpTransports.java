package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

/**
 * Factory for the default {@link HttpTransport}: a transport on OkHttp that owns a new client built from a
 * {@link HttpTransportConfiguration}.
 */
public final class HttpTransports {

    private static final String CONFIGURATION = "configuration";

    private HttpTransports() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * OkHttp transport that owns a new client with {@link HttpTransportConfiguration#defaults()}.
     *
     * @return transport
     */
    public static HttpTransport okHttp() {
        return okHttp(HttpTransportConfiguration.defaults());
    }

    /**
     * OkHttp transport that owns a new client configured from {@code configuration}.
     *
     * @param configuration timeouts, network and connection pool
     * @return transport
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static HttpTransport okHttp(final HttpTransportConfiguration configuration) {
        return new OkHttpTransport(requireNonNull(configuration, CONFIGURATION));
    }
}
