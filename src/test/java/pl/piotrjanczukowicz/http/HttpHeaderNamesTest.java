package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class HttpHeaderNamesTest {

    private static final Map<String, String> EXPECTED_NAMES = Map.ofEntries(
            Map.entry("ACCEPT", "Accept"),
            Map.entry("ACCEPT_ENCODING", "Accept-Encoding"),
            Map.entry("AUTHORIZATION", "Authorization"),
            Map.entry("CACHE_CONTROL", "Cache-Control"),
            Map.entry("CONNECTION", "Connection"),
            Map.entry("CONTENT_ENCODING", "Content-Encoding"),
            Map.entry("CONTENT_LENGTH", "Content-Length"),
            Map.entry("CONTENT_TYPE", "Content-Type"),
            Map.entry("COOKIE", "Cookie"),
            Map.entry("DATE", "Date"),
            Map.entry("ETAG", "ETag"),
            Map.entry("HOST", "Host"),
            Map.entry("IF_MODIFIED_SINCE", "If-Modified-Since"),
            Map.entry("IF_NONE_MATCH", "If-None-Match"),
            Map.entry("LAST_MODIFIED", "Last-Modified"),
            Map.entry("LINK", "Link"),
            Map.entry("LOCATION", "Location"),
            Map.entry("PROXY_AUTHORIZATION", "Proxy-Authorization"),
            Map.entry("RANGE", "Range"),
            Map.entry("RETRY_AFTER", "Retry-After"),
            Map.entry("SET_COOKIE", "Set-Cookie"),
            Map.entry("TRANSFER_ENCODING", "Transfer-Encoding"),
            Map.entry("UPGRADE", "Upgrade"),
            Map.entry("USER_AGENT", "User-Agent"),
            Map.entry("VARY", "Vary"),
            Map.entry("X_CLIENT_RATE_LIMIT_GATE", "X-Client-Rate-Limit-Gate"));

    @Test
    void constructor_shouldThrowUnsupportedOperationException_whenInvoked() throws NoSuchMethodException {
        final Constructor<HttpHeaderNames> constructor = HttpHeaderNames.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class)
                .hasRootCauseMessage("Utility class");
    }

    @Test
    void constants_shouldCoverEveryPublicStringField_whenListed() {
        final List<String> declared = Arrays.stream(HttpHeaderNames.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(Field::getName)
                .toList();

        assertThat(declared).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES.keySet());
    }

    @ParameterizedTest
    @CsvSource({
        "ACCEPT, Accept",
        "ACCEPT_ENCODING, Accept-Encoding",
        "AUTHORIZATION, Authorization",
        "CACHE_CONTROL, Cache-Control",
        "CONNECTION, Connection",
        "CONTENT_ENCODING, Content-Encoding",
        "CONTENT_LENGTH, Content-Length",
        "CONTENT_TYPE, Content-Type",
        "COOKIE, Cookie",
        "DATE, Date",
        "ETAG, ETag",
        "HOST, Host",
        "IF_MODIFIED_SINCE, If-Modified-Since",
        "IF_NONE_MATCH, If-None-Match",
        "LAST_MODIFIED, Last-Modified",
        "LINK, Link",
        "LOCATION, Location",
        "PROXY_AUTHORIZATION, Proxy-Authorization",
        "RANGE, Range",
        "RETRY_AFTER, Retry-After",
        "SET_COOKIE, Set-Cookie",
        "TRANSFER_ENCODING, Transfer-Encoding",
        "UPGRADE, Upgrade",
        "USER_AGENT, User-Agent",
        "VARY, Vary",
        "X_CLIENT_RATE_LIMIT_GATE, X-Client-Rate-Limit-Gate"
    })
    void constant_shouldHoldCanonicalName_whenNamed(final String constantName, final String expectedName)
            throws ReflectiveOperationException {
        final String actual =
                (String) HttpHeaderNames.class.getField(constantName).get(null);

        assertThat(actual).isEqualTo(expectedName);
    }

    @ParameterizedTest
    @CsvSource({
        "ACCEPT",
        "ACCEPT_ENCODING",
        "AUTHORIZATION",
        "CACHE_CONTROL",
        "CONNECTION",
        "CONTENT_ENCODING",
        "CONTENT_TYPE",
        "COOKIE",
        "DATE",
        "ETAG",
        "HOST",
        "IF_MODIFIED_SINCE",
        "IF_NONE_MATCH",
        "LAST_MODIFIED",
        "LINK",
        "LOCATION",
        "PROXY_AUTHORIZATION",
        "RETRY_AFTER",
        "SET_COOKIE",
        "USER_AGENT",
        "X_CLIENT_RATE_LIMIT_GATE"
    })
    void constant_shouldBeAcceptedAndFoundCaseInsensitively_whenUsedAsHeaderName(final String constantName)
            throws ReflectiveOperationException {
        final String name =
                (String) HttpHeaderNames.class.getField(constantName).get(null);

        final HttpHeaders headers = HttpHeaders.of(name, "v");

        assertThat(headers.get(name.toLowerCase(Locale.ROOT))).contains("v");
        assertThat(headers.get(name.toUpperCase(Locale.ROOT))).contains("v");
    }

    @ParameterizedTest
    @CsvSource({"CONTENT_LENGTH", "TRANSFER_ENCODING", "UPGRADE"})
    void constant_shouldBeRejectedAsForbidden_whenUsedAsRequestHeaderName(final String constantName)
            throws ReflectiveOperationException {
        final String name =
                (String) HttpHeaderNames.class.getField(constantName).get(null);

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(name, "v"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
    }
}
