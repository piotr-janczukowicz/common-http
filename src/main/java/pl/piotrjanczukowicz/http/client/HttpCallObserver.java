package pl.piotrjanczukowicz.http.client;

/**
 * Receives one {@link HttpCallEvent} for every call the client sends, for metrics, tracing or audit logging.
 *
 * <p>The observer runs on the calling thread, once the final response has arrived (after retries and redirects) or the
 * call has failed, so it must be quick and thread-safe. An exception it throws is logged and dropped: observing a call
 * never changes its outcome.
 *
 * <p>A call is reported once. A 401 that the client answered by renewing the credentials and sending the call again is
 * part of the same call and is not reported on its own: the event describes the second attempt, so a renewal that is
 * rejected too shows up as that second 401.
 *
 * <p>A call that the retry layer answered from a rate limit it remembered, without sending a request, is reported like
 * any other, with the replayed status, even though nothing was sent. Its response carries the header
 * {@link pl.piotrjanczukowicz.http.HttpHeaderNames#X_CLIENT_RATE_LIMIT_GATE}.
 */
@FunctionalInterface
public interface HttpCallObserver {

    /**
     * Called when a call has finished.
     *
     * @param event what happened
     */
    void onCall(HttpCallEvent event);
}
