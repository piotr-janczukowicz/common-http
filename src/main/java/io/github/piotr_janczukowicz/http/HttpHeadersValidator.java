package io.github.piotr_janczukowicz.http;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONNECTION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.UPGRADE;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rules for the name and value of a request header. It only checks: the input is never changed, and a violation is an
 * {@link IllegalArgumentException} that does not echo the value.
 *
 * <p>A request header is sent by this library, so the rules are strict: the name is an RFC 9110 token, the value is
 * visible ASCII, space or tab, and the headers that corrupt message framing or hand the connection over are refused.
 * Response headers are not checked: what a server sent is read as received.
 */
final class HttpHeadersValidator {

    private static final char MAX_VALUE_CHAR = '~';

    // Lower-cased, because requireSupportedHeader compares the lower-cased name.
    private static final Set<String> FRAMING_HEADER_NAMES = Stream.of(CONTENT_LENGTH, TRANSFER_ENCODING, UPGRADE)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private static final boolean[] TOKEN_CHARS = new boolean[128];

    static {
        for (final char ch : "!#$%&'*+-.^_`|~".toCharArray()) {
            TOKEN_CHARS[ch] = true;
        }
        for (int ch = '0'; ch <= '9'; ch++) {
            TOKEN_CHARS[ch] = true;
        }
        for (int ch = 'A'; ch <= 'Z'; ch++) {
            TOKEN_CHARS[ch] = true;
        }
        for (int ch = 'a'; ch <= 'z'; ch++) {
            TOKEN_CHARS[ch] = true;
        }
    }

    private HttpHeadersValidator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks a header that this library will send: name, then value, then the framing rules.
     *
     * @param name header name
     * @param value header value
     * @throws IllegalArgumentException if the name is not an RFC 9110 token, the value has a character other than
     *     visible ASCII, space or tab, the name is {@code Content-Length}, {@code Transfer-Encoding} or
     *     {@code Upgrade}, or a {@code Connection} value lists {@code upgrade}
     */
    static void requireValidRequestHeader(final String name, final String value) {
        requireName(name);
        requireValue(value);
        requireSupportedHeader(name, value);
    }

    private static void requireName(final String name) {
        if (name.isEmpty() || !isRfc9110Token(name)) {
            throw new IllegalArgumentException("header name is not an RFC 9110 token");
        }
    }

    private static void requireValue(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (ch > MAX_VALUE_CHAR || (ch < ' ' && ch != '\t')) {
                throw new IllegalArgumentException("header value contains a forbidden character");
            }
        }
    }

    private static void requireSupportedHeader(final String name, final String value) {
        final String lowerName = name.toLowerCase(Locale.ROOT);
        if (FRAMING_HEADER_NAMES.contains(lowerName)) {
            throw new IllegalArgumentException("header name is forbidden: " + name);
        }
        if (CONNECTION.equalsIgnoreCase(name) && isUpgradeListed(value)) {
            throw new IllegalArgumentException("header value is forbidden: Connection must not list upgrade");
        }
    }

    private static boolean isUpgradeListed(final String connectionValue) {
        for (final String token : connectionValue.split(",")) {
            if (UPGRADE.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRfc9110Token(final String name) {
        for (int i = 0; i < name.length(); i++) {
            final char ch = name.charAt(i);
            if (!(ch < TOKEN_CHARS.length && TOKEN_CHARS[ch])) {
                return false;
            }
        }
        return true;
    }
}
