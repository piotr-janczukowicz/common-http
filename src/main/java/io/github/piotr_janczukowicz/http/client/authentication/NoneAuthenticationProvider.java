package io.github.piotr_janczukowicz.http.client.authentication;

import java.util.Optional;

/** No authentication; see {@link AuthenticationProviders#none()}. */
enum NoneAuthenticationProvider implements AuthenticationProvider {
    INSTANCE;

    @Override
    public Optional<Authentication> authentication() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "AuthenticationProviders.none";
    }
}
