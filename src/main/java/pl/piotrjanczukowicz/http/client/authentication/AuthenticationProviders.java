package pl.piotrjanczukowicz.http.client.authentication;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Factory for {@link AuthenticationProvider}, covering the usual schemes: {@link #bearer(String)} and
 * {@link #bearer(Supplier)} for tokens, {@link #basic(String, String)} for a user and password or a client ID and
 * secret, and {@link #none()} for no credentials.
 */
public final class AuthenticationProviders {

    private AuthenticationProviders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Fixed bearer token.
     *
     * @param token bearer token, not including the {@code Bearer } prefix
     * @return provider that always sends {@code Bearer <token>}; its {@code toString} does not show the token
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws IllegalArgumentException if {@code token} is blank
     */
    public static AuthenticationProvider bearer(final String token) {
        return new BearerAuthenticationProvider(token);
    }

    /**
     * Bearer token from {@code tokens} on each request. Use it for a token that changes over time, such as an OAuth
     * user token that the caller refreshes. A 401 is not retried, because the provider cannot tell whether the supplier
     * would now return another token.
     *
     * @param tokens token supplier, called on each request
     * @return provider that sends {@code Bearer} with the value {@code tokens} returns
     * @throws NullPointerException if {@code tokens} is {@code null}
     * @throws IllegalArgumentException at request time if {@code tokens} returns a blank value
     */
    public static AuthenticationProvider bearer(final Supplier<String> tokens) {
        return new BearerSupplierAuthenticationProvider(tokens);
    }

    /**
     * Bearer token from {@code tokens} on each request, which is renewed after a 401 when
     * {@code isRenewableAfterRejection} accepts the rejected token. Use it for a token that the caller refreshes after
     * it expires, such as an OAuth user token: the predicate receives the token value that the server rejected and
     * returns {@code true} once a different token is available from {@code tokens}. The client then sends the request
     * once more; a second 401 is the result. A predicate that returns {@code false} behaves like
     * {@link #bearer(Supplier)}.
     *
     * @param tokens token supplier, called on each request
     * @param isRenewableAfterRejection receives the rejected token value; {@code true} to send the request again with
     *     the token {@code tokens} returns now
     * @return provider that sends {@code Bearer} with the value {@code tokens} returns, and renews it after a 401 as
     *     {@code isRenewableAfterRejection} decides; its {@code toString} does not show the token
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException at request time if {@code tokens} returns a blank value
     */
    public static AuthenticationProvider bearer(
            final Supplier<String> tokens, final Predicate<String> isRenewableAfterRejection) {
        return new RenewableBearerAuthenticationProvider(tokens, isRenewableAfterRejection);
    }

    /**
     * Basic authentication with a fixed user and password, or client ID and client secret. The identity is
     * {@code basic:{user}}, which stays the same when the password rotates. A 401 is not retried.
     *
     * @param user username or client ID; it must not be blank or contain {@code :}
     * @param password password or client secret; it must not be blank
     * @return provider that sends {@code Basic} and the Base64 of {@code user:password}; its {@code toString} shows the
     *     user only
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code user} is blank or contains {@code :}, or {@code password} is blank;
     *     the message never contains the password
     */
    public static AuthenticationProvider basic(final String user, final String password) {
        return new BasicAuthenticationProvider(user, password);
    }

    /**
     * No authentication: requests carry no {@code Authorization} header, and they are keyed as anonymous by the
     * rate-limit gate and the response cache.
     *
     * @return provider that never authenticates
     */
    public static AuthenticationProvider none() {
        return NoneAuthenticationProvider.INSTANCE;
    }
}
