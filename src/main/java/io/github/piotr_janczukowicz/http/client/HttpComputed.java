package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Optional;

/**
 * Outcome of asking for a result that the server computes in the background, such as a report, after
 * {@link HttpAcceptedPolling} has run out of tries.
 *
 * <p>The server answers HTTP 202 Accepted until the result is cached. The three cases tell apart what a bare empty
 * value cannot:
 *
 * <ul>
 *   <li>{@link Ready}: the data arrived;
 *   <li>{@link NotReady}: the server was still computing after the last try, so asking again later may succeed;
 *   <li>{@link NoData}: the server answered, and there is nothing to report (HTTP 204 or an empty body), so asking
 *       again will not help.
 * </ul>
 *
 * <pre>{@code
 * switch (caller.fetchWhenReady(call, Stats.class, HttpAcceptedPolling.defaults())) {
 *     case HttpComputed.Ready<Stats> ready -> use(ready.value());
 *     case HttpComputed.NotReady<Stats> notReady -> retryLater();
 *     case HttpComputed.NoData<Stats> noData -> showEmpty();
 * }
 * }</pre>
 *
 * @param <T> type of the computed result
 */
public sealed interface HttpComputed<T> {

    /**
     * Ready result, for callers that treat both "not ready" and "no data" as missing.
     *
     * @return the data, or empty for {@link NotReady} and {@link NoData}
     */
    default Optional<T> toOptional() {
        return this instanceof Ready<T>(final T value) ? Optional.of(value) : Optional.empty();
    }

    /**
     * Whether the data arrived.
     *
     * @return {@code true} for {@link Ready}
     */
    default boolean isReady() {
        return this instanceof Ready<T>;
    }

    /**
     * The data arrived.
     *
     * @param value the computed result
     * @param <T> result type
     */
    record Ready<T>(T value) implements HttpComputed<T> {

        public Ready {
            requireNonNull(value, "value");
        }
    }

    /**
     * The server was still computing after the last try (HTTP 202). Asking again later may succeed.
     *
     * @param <T> result type
     */
    record NotReady<T>() implements HttpComputed<T> {}

    /**
     * The server answered and has nothing to report (HTTP 204, an empty body or {@code {}}).
     *
     * @param <T> result type
     */
    record NoData<T>() implements HttpComputed<T> {}
}
