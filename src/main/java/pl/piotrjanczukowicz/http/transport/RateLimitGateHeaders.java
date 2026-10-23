package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import pl.piotrjanczukowicz.http.HttpHeaderNames;
import pl.piotrjanczukowicz.http.HttpHeaders;

/**
 * The header with which the retry layer marks a response that it returned from memory, without sending a request,
 * because an earlier response reported a rate limit that is still in force. Such a response repeats the earlier
 * rejection, so it says nothing new about the quota.
 */
public final class RateLimitGateHeaders {

    /** Value of {@link HttpHeaderNames#X_CLIENT_RATE_LIMIT_GATE}. */
    public static final String BLOCKED = "blocked";

    private RateLimitGateHeaders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Whether {@code headers} carry the marker.
     *
     * @param headers response headers
     * @return {@code true} when the response was replayed by the gate instead of sent
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    public static boolean isPresent(final HttpHeaders headers) {
        return requireNonNull(headers, "headers")
                .get(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE)
                .filter(BLOCKED::equalsIgnoreCase)
                .isPresent();
    }
}
