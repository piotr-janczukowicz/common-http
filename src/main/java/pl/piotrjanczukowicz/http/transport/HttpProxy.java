package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireBetween;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireBothOrNeither;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNotBlank;

import org.jspecify.annotations.Nullable;

/**
 * Proxy that a transport sends its requests through.
 *
 * <p>An HTTP proxy tunnels HTTPS with {@code CONNECT} and may require basic credentials, which are sent only after the
 * proxy asks for them with a 407 answer. A SOCKS proxy takes no credentials here. {@code toString} never prints the
 * password.
 *
 * @param type proxy protocol
 * @param host proxy host name or address
 * @param port proxy port, 1 to 65535
 * @param username basic credential for an HTTP proxy, or {@code null} for none
 * @param password password that goes with {@code username}, or {@code null} when there is no user name
 */
public record HttpProxy(
        Type type,
        String host,
        int port,
        @Nullable String username,
        @Nullable String password) {

    private static final int MAX_PORT = 65_535;

    /** Proxy protocol. */
    public enum Type {
        HTTP,
        SOCKS
    }

    public HttpProxy {
        requireNonNull(type, "type");
        requireNotBlank(host, "host");
        requireBetween(port, 1, MAX_PORT, "port must be between 1 and " + MAX_PORT + ", was " + port);
        requireBothOrNeither(username, password, "username and password");
        if (username != null) {
            requireNotBlank(username, "username");
            if (type == Type.SOCKS) {
                throw new IllegalArgumentException("credentials are supported for HTTP proxies only");
            }
        }
    }

    /**
     * HTTP proxy without credentials.
     *
     * @param host proxy host
     * @param port proxy port
     * @return proxy
     * @throws NullPointerException if {@code host} is {@code null}
     * @throws IllegalArgumentException if {@code host} is blank or {@code port} is out of range
     */
    public static HttpProxy http(final String host, final int port) {
        return new HttpProxy(Type.HTTP, host, port, null, null);
    }

    /**
     * SOCKS proxy.
     *
     * @param host proxy host
     * @param port proxy port
     * @return proxy
     * @throws NullPointerException if {@code host} is {@code null}
     * @throws IllegalArgumentException if {@code host} is blank or {@code port} is out of range
     */
    public static HttpProxy socks(final String host, final int port) {
        return new HttpProxy(Type.SOCKS, host, port, null, null);
    }

    /**
     * Copy that authenticates to an HTTP proxy with basic credentials.
     *
     * @param user user name
     * @param secret password
     * @return proxy with credentials
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code user} is blank, or this is a SOCKS proxy
     */
    public HttpProxy withCredentials(final String user, final String secret) {
        return new HttpProxy(type, host, port, requireNonNull(user, "user"), requireNonNull(secret, "secret"));
    }

    /**
     * Whether requests to this proxy carry credentials.
     *
     * @return {@code true} when a user name and password are set
     */
    public boolean hasCredentials() {
        return username != null;
    }

    @Override
    public String toString() {
        return "HttpProxy[" + type + " " + host + ":" + port + (hasCredentials() ? ", credentials=***" : "") + "]";
    }
}
