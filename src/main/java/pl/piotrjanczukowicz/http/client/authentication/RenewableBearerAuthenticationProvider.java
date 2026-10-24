package pl.piotrjanczukowicz.http.client.authentication;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Bearer token from a supplier, read on each request, whose rejected token may be renewed after a 401 as the predicate
 * decides; {@code toString} does not include secrets.
 */
final class RenewableBearerAuthenticationProvider implements AuthenticationProvider {

    private final Supplier<String> tokens;
    private final BearerAuthenticationCache cache = new BearerAuthenticationCache();
    private final Predicate<String> isRenewableAfterRejection;

    RenewableBearerAuthenticationProvider(
            final Supplier<String> tokens, final Predicate<String> isRenewableAfterRejection) {
        this.tokens = requireNonNull(tokens, "tokens");
        this.isRenewableAfterRejection = requireNonNull(isRenewableAfterRejection, "isRenewableAfterRejection");
    }

    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(cache.forToken(tokens.get()));
    }

    @Override
    public boolean isRenewableAfterRejection(final Authentication rejected) {
        return isRenewableAfterRejection.test(rejected.value());
    }

    @Override
    public String toString() {
        return "AuthenticationProviders.bearer(supplier, predicate)";
    }
}
