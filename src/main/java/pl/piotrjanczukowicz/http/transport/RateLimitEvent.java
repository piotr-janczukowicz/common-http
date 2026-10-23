package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtLeast;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNegative;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpMethod;

/**
 * A step of the retry layer that is caused by a rate limit: a wait, or the decision not to wait. Unlike the final
 * response a caller sees, these events also cover rejections that were retried and waits that never reached the server.
 *
 * @param type what happened
 * @param method method of the request
 * @param target scheme, host and path of the request, without query string
 * @param statusCode status of the rejecting response, or {@code 0} for an event of the coordination gate, which has no
 *     response
 * @param delay for {@link Type#WAIT} and {@link Type#GATE_WAIT} the pause that follows; for the others the wait that
 *     was required and not taken
 * @param attempt rate-limit attempts of this call that have finished, counting the first try
 * @param bucket the quota that is limited, or {@code null} when the limit applies to every request of the credential
 */
public record RateLimitEvent(
        Type type,
        HttpMethod method,
        String target,
        int statusCode,
        Duration delay,
        int attempt,
        @Nullable String bucket) {

    public RateLimitEvent {
        requireNonNull(type, "type");
        requireNonNull(method, "method");
        requireNonNull(target, "target");
        requireAtLeast(statusCode, 0, "statusCode");
        requireNonNegative(delay, "delay");
        requireAtLeast(attempt, 0, "attempt");
    }

    /** What the retry layer did about a rate limit. */
    public enum Type {
        /** A rate-limited response is waited out and the request is sent again. */
        WAIT,
        /** A request waits, before it is sent, for a limit that an earlier response set. */
        GATE_WAIT,
        /** The response is returned because the wait it asks for is longer than {@code maxRateLimitWait}. */
        GIVE_UP_WAIT_TOO_LONG,
        /** The response is returned because the attempts for rate limits are used up. */
        GIVE_UP_ATTEMPTS_EXHAUSTED,
        /** The response is returned because the wait would end after the total deadline. */
        GIVE_UP_DEADLINE,
        /** The request is not sent because a limit that an earlier response set is still in force. */
        GATE_FAIL_FAST
    }
}
