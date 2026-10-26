package io.github.piotr_janczukowicz.http;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireBetween;

/** Commonly used HTTP statuses. Unknown codes stay as {@code int} on {@link HttpResponse}. */
public enum HttpStatus {
    OK(200),
    CREATED(201),
    ACCEPTED(202),
    NO_CONTENT(204),
    RESET_CONTENT(205),
    MULTI_STATUS(207),
    MOVED_PERMANENTLY(301),
    FOUND(302),
    SEE_OTHER(303),
    NOT_MODIFIED(304),
    TEMPORARY_REDIRECT(307),
    PERMANENT_REDIRECT(308),
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    CONFLICT(409),
    GONE(410),
    UNSUPPORTED_MEDIA_TYPE(415),
    UNPROCESSABLE_ENTITY(422),
    TOO_MANY_REQUESTS(429),
    UNAVAILABLE_FOR_LEGAL_REASONS(451),
    INTERNAL_SERVER_ERROR(500),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),
    GATEWAY_TIMEOUT(504);

    private static final int MIN_CODE = 100;
    private static final int MAX_CODE = 599;

    private final int code;

    HttpStatus(final int code) {
        this.code = code;
    }

    /**
     * Numeric HTTP status code.
     *
     * @return status code
     */
    public int code() {
        return code;
    }

    /**
     * Whether {@code statusCode} equals this status's code.
     *
     * @param statusCode HTTP status code
     * @return {@code true} if the codes match
     */
    public boolean hasCode(final int statusCode) {
        return this.code == statusCode;
    }

    /**
     * Whether {@code statusCode} is a status that {@link HttpResponse} accepts.
     *
     * @param statusCode status code read from a response
     * @return {@code true} for 100 through 599
     */
    public static boolean isValid(final int statusCode) {
        return statusCode >= MIN_CODE && statusCode <= MAX_CODE;
    }

    static int requireValid(final int statusCode) {
        return requireBetween(statusCode, MIN_CODE, MAX_CODE, "statusCode");
    }
}
