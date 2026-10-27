package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One finished call, as seen by a {@link HttpCallObserver}.
 *
 * <p>It describes what reached the transport. A request that was rejected before that, for example because its URI is
 * outside the API origin or its credentials could not be obtained, is not reported.
 *
 * <p>The target never carries the query, user info or fragment, which often hold tokens. The response body is not
 * available.
 *
 * @param method HTTP method of the request
 * @param target {@code scheme://host[:port]/path} of the request, without query, user info or fragment; the URI the
 *     call started with, not the one it ended on after redirects
 * @param statusCode HTTP status of the final response, or {@code 0} when the call failed without one
 * @param responseHeaders headers of the final response; empty when the call failed without a response. Their
 *     {@code toString} hides credentials, but the values are as received
 * @param elapsed time from sending the request until the final response arrived, including retry waits and redirects. A
 *     buffered call includes reading the response body, which the transport does before it returns; for a streamed call
 *     it ends at the response headers
 * @param failure why the call failed, or {@code null} when it ended with a response. An error status is a response, not
 *     a failure, so a 404 has a {@code statusCode} and no {@code failure}. A call can have both: when a response
 *     arrived but the call was then refused, for example a redirect the policy does not allow or one past the hop
 *     limit, {@code statusCode} and {@code responseHeaders} describe that response and {@code failure} is the exception
 *     thrown to the caller
 */
public record HttpCallEvent(
        HttpMethod method,
        String target,
        int statusCode,
        HttpHeaders responseHeaders,
        Duration elapsed,
        @Nullable Throwable failure) {

    public HttpCallEvent {
        requireNonNull(method, "method");
        requireNonNull(target, "target");
        requireNonNull(responseHeaders, "responseHeaders");
        requireNonNull(elapsed, "elapsed");
    }

    /**
     * Whether the call ended with an exception for the caller: it failed without a response, or got one that was then
     * refused. An error status that the client maps to an exception after the response is not counted, because the
     * event is reported before that mapping.
     *
     * @return {@code true} when {@link #failure()} is set
     */
    public boolean isFailure() {
        return failure != null;
    }

    /**
     * Value of the first response header named {@code name}, case-insensitive; for example {@code X-Request-Id} or
     * {@code X-RateLimit-Remaining}.
     *
     * @param name header name
     * @return the value, or empty
     */
    public Optional<String> responseHeader(final String name) {
        return responseHeaders.get(name);
    }

    /**
     * Event for a call that got a response.
     *
     * @param method request method
     * @param target request target without query
     * @param statusCode status of the final response
     * @param responseHeaders headers of the final response
     * @param elapsed time until the response headers
     * @return the event
     */
    static HttpCallEvent response(
            final HttpMethod method,
            final String target,
            final int statusCode,
            final HttpHeaders responseHeaders,
            final Duration elapsed) {
        return new HttpCallEvent(method, target, statusCode, responseHeaders, elapsed, null);
    }

    /**
     * Event for a call that failed without a response.
     *
     * @param method request method
     * @param target request target without query
     * @param elapsed time until the failure
     * @param failure the failure
     * @return the event
     */
    static HttpCallEvent failed(
            final HttpMethod method, final String target, final Duration elapsed, final Throwable failure) {
        return new HttpCallEvent(method, target, 0, HttpHeaders.empty(), elapsed, requireNonNull(failure, "failure"));
    }
}
