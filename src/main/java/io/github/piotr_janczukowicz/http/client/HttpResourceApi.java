package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.net.URI;

/**
 * Shared helpers for REST resource APIs: the {@link HttpCall} for each verb of a path template. Sending and decoding is
 * done by an {@link AbstractHttpCaller}, and JSON encoding by {@link AbstractHttpCaller#json(HttpCall, Object)}.
 */
public abstract class HttpResourceApi {

    /** For subclasses. */
    protected HttpResourceApi() {
        // Nothing to initialise: calls are created per request.
    }

    /**
     * GET the expanded {@code path}.
     *
     * @param path API path template
     * @param values placeholder values in order
     * @return call
     * @throws NullPointerException if {@code path} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails
     */
    protected HttpCall get(final PathTemplate path, final Object... values) {
        return HttpCall.get(path.expand(values));
    }

    /**
     * POST the expanded {@code path}.
     *
     * @param path API path template
     * @param values placeholder values in order
     * @return call
     * @throws NullPointerException if {@code path} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails
     */
    protected HttpCall post(final PathTemplate path, final Object... values) {
        return HttpCall.post(path.expand(values));
    }

    /**
     * POST {@code uri}.
     *
     * @param uri absolute URI
     * @return call
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    protected HttpCall post(final URI uri) {
        return HttpCall.of(HttpMethod.POST, requireNonNull(uri, "uri"));
    }

    /**
     * PUT the expanded {@code path}.
     *
     * @param path API path template
     * @param values placeholder values in order
     * @return call
     * @throws NullPointerException if {@code path} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails
     */
    protected HttpCall put(final PathTemplate path, final Object... values) {
        return HttpCall.put(path.expand(values));
    }

    /**
     * PATCH the expanded {@code path}.
     *
     * @param path API path template
     * @param values placeholder values in order
     * @return call
     * @throws NullPointerException if {@code path} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails
     */
    protected HttpCall patch(final PathTemplate path, final Object... values) {
        return HttpCall.patch(path.expand(values));
    }

    /**
     * DELETE the expanded {@code path}.
     *
     * @param path API path template
     * @param values placeholder values in order
     * @return call
     * @throws NullPointerException if {@code path} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails
     */
    protected HttpCall delete(final PathTemplate path, final Object... values) {
        return HttpCall.delete(path.expand(values));
    }
}
