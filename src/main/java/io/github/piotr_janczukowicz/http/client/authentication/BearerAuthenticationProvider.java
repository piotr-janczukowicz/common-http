package io.github.piotr_janczukowicz.http.client.authentication;

import java.util.Optional;

/** Fixed bearer token; {@code toString} does not include the secret. */
final class BearerAuthenticationProvider implements AuthenticationProvider {

    private final Authentication authentication;

    BearerAuthenticationProvider(final String token) {
        this.authentication = Authentication.ofBearer(token);
    }

    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(authentication);
    }

    @Override
    public String toString() {
        return "AuthenticationProviders.bearer";
    }
}
