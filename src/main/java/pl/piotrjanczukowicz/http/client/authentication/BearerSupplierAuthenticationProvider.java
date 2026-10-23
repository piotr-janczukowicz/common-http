package pl.piotrjanczukowicz.http.client.authentication;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Bearer token from a supplier, read on each request; {@code toString} does not include secrets. */
final class BearerSupplierAuthenticationProvider implements AuthenticationProvider {

    private final Supplier<String> tokens;
    // The last token and what it was made into: the identity of a bearer is a digest, too dear to redo per request.
    private final AtomicReference<@Nullable Authentication> last = new AtomicReference<>();

    BearerSupplierAuthenticationProvider(final Supplier<String> tokens) {
        this.tokens = requireNonNull(tokens, "tokens");
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
    public String toString() {
        return "AuthenticationProviders.bearer(supplier)";
    }
}
