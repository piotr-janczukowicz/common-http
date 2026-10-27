package io.github.piotr_janczukowicz.http.client.authentication;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the last bearer token and the {@link Authentication} made from it: the identity of a bearer is a digest, too
 * dear to redo per request, and a supplier usually returns the same token many times in a row.
 */
final class BearerAuthenticationCache {

    private final AtomicReference<@Nullable Authentication> last;

    BearerAuthenticationCache() {
        this.last = new AtomicReference<>();
    }

    Authentication forToken(final String token) {
        requireNonNull(token, "token");
        final Authentication cached = last.get();
        if (cached != null && cached.value().equals(token)) {
            return cached;
        }
        final Authentication created = Authentication.ofBearer(token);
        last.set(created);
        return created;
    }
}
