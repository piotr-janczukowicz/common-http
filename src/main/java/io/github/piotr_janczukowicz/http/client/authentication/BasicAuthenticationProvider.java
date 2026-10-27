package io.github.piotr_janczukowicz.http.client.authentication;

import java.util.Optional;

/** Basic authentication with a fixed user and password; {@code toString} shows the user only. */
final class BasicAuthenticationProvider implements AuthenticationProvider {

    private final String user;
    private final Authentication authentication;

    BasicAuthenticationProvider(final String user, final String password) {
        this.authentication = Authentication.ofBasic(user, password).withIdentity("basic:" + user);
        this.user = user;
    }

    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(authentication);
    }

    @Override
    public String toString() {
        return "AuthenticationProviders.basic[user=" + user + "]";
    }
}
