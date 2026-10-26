package io.github.piotr_janczukowicz.http.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Turns a host name into addresses, replacing the system resolver for a transport.
 *
 * <p>Useful for an internal DNS, a fixed address for a host (for example an on-premise server), or preferring IPv4.
 * Called for every new connection, from any thread, so it must be thread-safe.
 *
 * <p>With a proxy configured (see {@link HttpNetworkConfiguration}) the resolver is asked for the proxy's host name,
 * not for the origin's: the proxy resolves the origin itself. A fixed address for {@code api.example.com} then has no
 * effect.
 *
 * <p>A resolver that cannot find a host throws {@link UnknownHostException}, which the transport reports as an
 * {@link java.io.IOException}. An unchecked exception from a resolver is a bug in the resolver: it is not wrapped and
 * is not retried, it propagates unchecked from {@code execute}.
 */
@FunctionalInterface
public interface HostResolver {

    /**
     * Addresses of {@code hostname}, in the order to try them.
     *
     * @param hostname host name to resolve
     * @return one or more addresses; a {@code null} or empty list is reported as {@link UnknownHostException}
     * @throws UnknownHostException if the host cannot be resolved
     */
    List<InetAddress> resolve(String hostname) throws UnknownHostException;
}
