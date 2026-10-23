package pl.piotrjanczukowicz.http;

/** Standard HTTP header names, in their canonical RFC casing. {@link HttpHeaders} lookup ignores case. */
public final class HttpHeaderNames {

    /** {@code Accept}. */
    public static final String ACCEPT = "Accept";

    /** {@code Accept-Encoding}. */
    public static final String ACCEPT_ENCODING = "Accept-Encoding";

    /** {@code Authorization}. */
    public static final String AUTHORIZATION = "Authorization";

    /** {@code Cache-Control}. */
    public static final String CACHE_CONTROL = "Cache-Control";

    /** {@code Connection}. */
    public static final String CONNECTION = "Connection";

    /** {@code Content-Encoding}. */
    public static final String CONTENT_ENCODING = "Content-Encoding";

    /** {@code Content-Length}. */
    public static final String CONTENT_LENGTH = "Content-Length";

    /** {@code Content-Type}. */
    public static final String CONTENT_TYPE = "Content-Type";

    /** {@code Cookie}. */
    public static final String COOKIE = "Cookie";

    /** {@code Date}. */
    public static final String DATE = "Date";

    /** {@code ETag}. */
    public static final String ETAG = "ETag";

    /** {@code Host}. */
    public static final String HOST = "Host";

    /** {@code If-Modified-Since}. */
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    /** {@code If-None-Match}. */
    public static final String IF_NONE_MATCH = "If-None-Match";

    /** {@code Last-Modified}. */
    public static final String LAST_MODIFIED = "Last-Modified";

    /** {@code Link}. */
    public static final String LINK = "Link";

    /** {@code Location}. */
    public static final String LOCATION = "Location";

    /** {@code Proxy-Authorization}. */
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";

    /** {@code Range}. */
    public static final String RANGE = "Range";

    /** {@code Retry-After}. */
    public static final String RETRY_AFTER = "Retry-After";

    /** {@code Set-Cookie}. */
    public static final String SET_COOKIE = "Set-Cookie";

    /** {@code Transfer-Encoding}. */
    public static final String TRANSFER_ENCODING = "Transfer-Encoding";

    /** {@code Upgrade}. */
    public static final String UPGRADE = "Upgrade";

    /** {@code Vary}. */
    public static final String VARY = "Vary";

    /** {@code User-Agent}. */
    public static final String USER_AGENT = "User-Agent";

    /** Marker the retry layer sets on a response replayed from memory, see {@code RateLimitGateHeaders}. */
    public static final String X_CLIENT_RATE_LIMIT_GATE = "X-Client-Rate-Limit-Gate";

    private HttpHeaderNames() {
        throw new UnsupportedOperationException("Utility class");
    }
}
