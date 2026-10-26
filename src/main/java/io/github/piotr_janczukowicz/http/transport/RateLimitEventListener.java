package io.github.piotr_janczukowicz.http.transport;

/**
 * Receives the {@link RateLimitEvent events} of the retry layer, for metrics or logging.
 *
 * <p>Called on the thread that makes the request, so an implementation must be thread-safe and quick. An exception
 * thrown by it is logged and otherwise ignored: it never fails the call.
 */
@FunctionalInterface
public interface RateLimitEventListener {

    /**
     * Called for each event.
     *
     * @param event what happened
     */
    void onRateLimitEvent(RateLimitEvent event);
}
