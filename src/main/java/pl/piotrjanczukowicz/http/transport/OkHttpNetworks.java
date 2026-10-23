package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.CertificatePinner;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;

/** Applies an {@link HttpNetworkConfiguration} (proxy, TLS, pins, DNS) to an OkHttp client builder. */
final class OkHttpNetworks {

    private OkHttpNetworks() {
        throw new UnsupportedOperationException("Utility class");
    }

    // Only what the configuration sets is applied; whatever is left unset keeps the OkHttp default for the proxy, TLS,
    // pins and DNS.
    static void apply(final OkHttpClient.Builder builder, final HttpNetworkConfiguration network) {
        final HttpProxy proxy = network.proxy();
        if (proxy != null) {
            applyProxy(builder, proxy);
        }
        final HttpTls tls = network.tls();
        if (tls != null) {
            builder.sslSocketFactory(tls.socketFactory(), tls.trustManager());
        }
        if (!network.certificatePins().isEmpty()) {
            final CertificatePinner.Builder pins = new CertificatePinner.Builder();
            network.certificatePins().forEach((pattern, hashes) -> pins.add(pattern, hashes.toArray(String[]::new)));
            builder.certificatePinner(pins.build());
        }
        final HostResolver resolver = network.hostResolver();
        if (resolver != null) {
            builder.dns(hostname -> {
                final List<InetAddress> addresses = resolver.resolve(hostname);
                if (addresses == null || addresses.isEmpty()) {
                    throw new UnknownHostException("host resolver returned no addresses for " + hostname);
                }
                return addresses;
            });
        }
    }

    private static void applyProxy(final OkHttpClient.Builder builder, final HttpProxy proxy) {
        final Proxy.Type type = proxy.type() == HttpProxy.Type.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        builder.proxy(new Proxy(type, InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
        if (proxy.hasCredentials()) {
            final String credential = Credentials.basic(
                    requireNonNull(proxy.username(), "username"), requireNonNull(proxy.password(), "password"));
            builder.proxyAuthenticator((route, response) -> {
                // A second 407 means the credentials were rejected; answering again would loop.
                if (response.request().header(PROXY_AUTHORIZATION) != null) {
                    return null;
                }
                return response.request()
                        .newBuilder()
                        .header(PROXY_AUTHORIZATION, credential)
                        .build();
            });
        }
    }
}
