package pl.piotrjanczukowicz.http;

/** HTTP request method. */
public enum HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS;

    /**
     * Whether an empty body is sent when the caller omitted one (POST, PUT, PATCH).
     *
     * @return {@code true} if an empty body should be sent
     */
    public boolean isEmptyBodySentWhenAbsent() {
        return this == POST || this == PUT || this == PATCH;
    }
}
