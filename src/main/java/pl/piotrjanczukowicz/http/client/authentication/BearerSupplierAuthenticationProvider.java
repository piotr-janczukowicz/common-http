package pl.piotrjanczukowicz.http.client.authentication;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;
import java.util.function.Supplier;

/** Bearer token from a supplier, read on each request; {@code toString} does not include secrets. */
final class BearerSupplierAuthenticationProvider implements AuthenticationProvider {

    private final Supplier<String> tokens;
    private final BearerAuthenticationCache cache = new BearerAuthenticationCache();

    BearerSupplierAuthenticationProvider(final Supplier<String> tokens) {
        this.tokens = requireNonNull(tokens, "tokens");
    }

    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(cache.forToken(tokens.get()));
    }

    @Override
    public String toString() {
        return "AuthenticationProviders.bearer(supplier)";
    }
}
