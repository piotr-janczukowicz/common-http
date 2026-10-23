package pl.piotrjanczukowicz.http.client.authentication;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Bearer token from a supplier, read on each request, whose rejected token may be renewed after a 401 as the predicate
 * decides; {@code toString} does not include secrets.
 */
final class RenewableBearerAuthenticationProvider implements AuthenticationProvider {

    private final Supplier<String> tokens;
    // The last token and what it was made into: the identity of a bearer is a digest, too dear to redo per request.
    private final AtomicReference<@Nullable Authentication> last = new AtomicReference<>();
    private final Predicate<String> isRenewableAfterRejection;

    RenewableBearerAuthenticationProvider(
            final Supplier<String> tokens, final Predicate<String> isRenewableAfterRejection) {
        this.tokens = requireNonNull(tokens, "tokens");
        this.isRenewableAfterRejection = requireNonNull(isRenewableAfterRejection, "isRenewableAfterRejection");
    }

    @Override
    public Optional<Authentication> authentication() {
        final String token = tokens.get();
        final Authentication cached = last.get();
        if (cached != null && cached.value().equals(token)) {
            return Optional.of(cached);
        }
        final Authentication created = Authentication.ofBearer(token);
        last.set(created);
        return Optional.of(created);
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
