package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The TLS socket factory of a transport together with the trust manager that it was built with: how sockets are created
 * and which certificates are trusted. A value that {@link HttpNetworkConfiguration} holds; it has no settings of its
 * own.
 *
 * <p>Use it for a private certificate authority or a corporate TLS-inspecting proxy. Certificate and host name
 * verification stay on; this only changes what is trusted. For pinning one server key, see
 * {@link HttpNetworkConfiguration.Builder#certificatePin(String, String...)}.
 *
 * @param socketFactory creates the TLS sockets
 * @param trustManager decides which certificates are trusted; the same one that {@code socketFactory} was built with
 */
public record HttpTls(SSLSocketFactory socketFactory, X509TrustManager trustManager) {

    public HttpTls {
        requireNonNull(socketFactory, "socketFactory");
        requireNonNull(trustManager, "trustManager");
    }

    /**
     * The socket factory of an initialized context paired with its trust manager.
     *
     * @param context context whose socket factory is used, initialized with {@code trustManager}
     * @param trustManager trust manager the context was initialized with
     * @return TLS socket factory and trust manager
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpTls of(final SSLContext context, final X509TrustManager trustManager) {
        return new HttpTls(requireNonNull(context, "context").getSocketFactory(), trustManager);
    }
}
