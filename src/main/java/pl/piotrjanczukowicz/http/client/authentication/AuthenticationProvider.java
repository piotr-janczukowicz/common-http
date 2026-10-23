package pl.piotrjanczukowicz.http.client.authentication;

import java.util.Optional;

/**
 * Source of the {@link Authentication} of each request, sent as the {@code Authorization} header. A default header of
 * the same name never replaces it; only an overlay header on a call does. It is evaluated on each request, so a
 * credential can rotate without a new HTTP client.
 *
 * <p>The usual schemes are built with {@link AuthenticationProviders}: {@link AuthenticationProviders#bearer(String)}
 * and {@link AuthenticationProviders#bearer(java.util.function.Supplier)} for tokens,
 * {@link AuthenticationProviders#basic(String, String)} for a user and password or a client ID and secret, and
 * {@link AuthenticationProviders#none()} for no credentials. A provider whose credential changes over time and must be
 * renewed after a 401 is built with {@link AuthenticationProviders#bearer(java.util.function.Supplier,
 * java.util.function.Predicate)}.
 *
 * <p>Implementations must be thread-safe, because one provider can serve concurrent calls, and
 * {@link #authentication()} must return the credential that is current at that call.
 */
@FunctionalInterface
public interface AuthenticationProvider {

    /**
     * Authentication for one request.
     *
     * @return the authentication, or empty to send no {@code Authorization} header
     * @throws java.io.UncheckedIOException if obtaining credentials fails on I/O; the client rethrows the cause as the
     *     checked {@link java.io.IOException} from the call
     */
    Optional<Authentication> authentication();

    /**
     * Called when the server answered HTTP 401 to a request sent with {@code rejected}, so the provider can drop what
     * it cached. When it returns {@code true} the client asks {@link #authentication()} again and sends the request
     * once more; a 401 means nothing was processed, so this is safe for every method. A second 401 is the result. It is
     * not called when the 401 came from another origin after a redirect.
     *
     * <p>The client does not coordinate concurrent calls: if several requests are rejected at once, each one calls this
     * method. A provider that must renew only once, such as one that mints tokens, has to make that decision itself,
     * for example by comparing {@code rejected} with the credential it holds now.
     *
     * @param rejected the authentication the rejected request was sent with; its identity is the default one
     * @return {@code true} if {@link #authentication()} may now return a different credential; {@code false} by
     *     default, which fits fixed credentials
     */
    default boolean isRenewableAfterRejection(final Authentication rejected) {
        return false;
    }
}
