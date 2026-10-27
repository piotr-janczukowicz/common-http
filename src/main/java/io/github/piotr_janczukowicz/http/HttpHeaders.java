package io.github.piotr_janczukowicz.http;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.COOKIE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.SET_COOKIE;
import static io.github.piotr_janczukowicz.util.StringUtils.isBlank;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Immutable HTTP headers as a single value per name. Lookup by name is case-insensitive, and a name keeps the casing it
 * was first given.
 *
 * <p>Headers made by {@link #of(String...)}, {@link #of(Map)} and {@link #with} are request headers: every entry has
 * passed {@link HttpHeadersValidator#requireValidRequestHeader}, and a value is stored without leading or trailing
 * whitespace. Headers made by {@link #fromWire} are what a server sent: they are not checked and are kept as received.
 * Merging response headers into request headers checks their entries against the request rules, so request headers stay
 * valid.
 *
 * <p>Every operation that changes the headers returns a new instance and leaves this one as it is.
 */
public final class HttpHeaders {

    private static final String NAME = "name";
    private static final String HEADERS_ARGUMENT = "headers";

    private static final HttpHeaders EMPTY_REQUEST = new HttpHeaders(Map.of(), Profile.REQUEST);
    private static final HttpHeaders EMPTY_RESPONSE = new HttpHeaders(Map.of(), Profile.RESPONSE);

    private final Map<String, String> headers;
    private final Profile profile;

    private HttpHeaders(final Map<String, String> headers, final Profile profile) {
        this.headers = headers;
        this.profile = profile;
    }

    /**
     * Request headers without any entry.
     *
     * @return the shared empty headers
     */
    public static HttpHeaders empty() {
        return EMPTY_REQUEST;
    }

    /**
     * Headers from alternating name/value pairs. A later pair replaces an earlier one with the same name
     * (case-insensitive).
     *
     * @param namesAndValues name, value, name, value, …
     * @return request headers
     * @throws NullPointerException if the array, a name or a value is {@code null}
     * @throws IllegalArgumentException if the length is odd, or a name or value breaks the request header rules
     */
    public static HttpHeaders of(final String... namesAndValues) {
        requireNonNull(namesAndValues, "namesAndValues");
        if ((namesAndValues.length & 1) != 0) {
            throw new IllegalArgumentException("Expected alternating name/value pairs");
        }

        final Map<String, String> values = caseInsensitiveMap();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            putValid(values, Profile.REQUEST, namesAndValues[i], namesAndValues[i + 1]);
        }
        return freeze(values, Profile.REQUEST);
    }

    /**
     * Headers from a map. {@code null} becomes empty.
     *
     * @param headers name to value
     * @return request headers
     * @throws NullPointerException if a name or a value is {@code null}
     * @throws IllegalArgumentException if a name or value breaks the request header rules
     */
    public static HttpHeaders of(final @Nullable Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return EMPTY_REQUEST;
        }

        final Map<String, String> values = caseInsensitiveMap();
        headers.forEach((name, value) -> putValid(values, Profile.REQUEST, name, value));
        return freeze(values, Profile.REQUEST);
    }

    /**
     * Response headers from the wire. Never fails on what a server sent: it skips null or blank names, HTTP/2
     * pseudo-headers (names starting with {@code :}), and null or empty value lists, and keeps every other name and
     * value as received, including request-forbidden names such as {@code Content-Length}.
     *
     * <p>A name with several values keeps them joined with {@code ", "}, in order. That is lossless for list-valued
     * headers but not for {@code Set-Cookie}, whose values cannot be split again; this single-value model offers no
     * separate accessor for them. A name that repeats in another letter case keeps its values joined the same way, in
     * the map's iteration order, under the name first seen.
     *
     * @param headers name to values; null names, null lists, and null list elements are skipped; {@code null} becomes
     *     empty
     * @return response headers
     */
    public static HttpHeaders fromWire(
            final @Nullable Map<@Nullable String, @Nullable List<@Nullable String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return EMPTY_RESPONSE;
        }

        final Map<String, String> values = caseInsensitiveMap();
        headers.forEach((name, headerValues) -> {
            if (isBlank(name) || name.startsWith(":") || headerValues == null) {
                return;
            }
            final List<String> present =
                    headerValues.stream().filter(Objects::nonNull).toList();
            if (!present.isEmpty()) {
                values.merge(name, String.join(", ", present), (first, second) -> first + ", " + second);
            }
        });
        return freeze(values, Profile.RESPONSE);
    }

    /**
     * Unmodifiable name → value map. Names keep the casing of the first put; lookup stays case-insensitive.
     *
     * @return headers map
     */
    public Map<String, String> asMap() {
        return headers;
    }

    /**
     * Whether there are no headers.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return headers.isEmpty();
    }

    /**
     * Value for {@code name}, if present.
     *
     * @param name header name
     * @return value, or empty
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<String> get(final String name) {
        requireNonNull(name, NAME);
        return Optional.ofNullable(headers.get(name));
    }

    /**
     * These headers with {@code name} set to {@code value}, replacing an entry with the same name (case-insensitive).
     * The new entry follows the rules of these headers: request headers check it against the request rules.
     *
     * @param name header name
     * @param value header value
     * @return new headers
     * @throws NullPointerException if {@code name} or {@code value} is {@code null}
     * @throws IllegalArgumentException if the name or value breaks the rules of these headers
     */
    public HttpHeaders with(final String name, final String value) {
        final Map<String, String> values = copyOfHeaders();
        putValid(values, profile, name, value);
        return freeze(values, profile);
    }

    /**
     * These headers with the entries of every given layer on top, copied once. A later layer replaces an entry of an
     * earlier one, or of these headers, with the same name (case-insensitive), and the name keeps the casing it had
     * first. Entries of response headers that are put on request headers are checked against the request rules.
     *
     * @param first lowest layer to put on top of these headers
     * @param more further layers, each on top of the one before
     * @return merged headers with the rules of these headers, or these headers when every layer is empty
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if these are request headers and an entry of a response layer breaks the request
     *     rules
     */
    public HttpHeaders merge(final HttpHeaders first, final HttpHeaders... more) {
        requireNonNull(first, HEADERS_ARGUMENT);
        requireNonNull(more, "more");
        for (final HttpHeaders layer : more) {
            requireNonNull(layer, HEADERS_ARGUMENT);
        }
        if (first.isEmpty() && Arrays.stream(more).allMatch(HttpHeaders::isEmpty)) {
            return this;
        }

        final Map<String, String> merged = copyOfHeaders();
        putLayer(merged, first);
        for (final HttpHeaders layer : more) {
            putLayer(merged, layer);
        }
        return freeze(merged, profile);
    }

    /**
     * These headers without every listed name (case-insensitive), copied once.
     *
     * @param first header name to drop
     * @param more further names to drop
     * @return headers without those names, or these headers when none of them is present
     * @throws NullPointerException if a name is {@code null}
     */
    public HttpHeaders without(final String first, final String... more) {
        requireNonNull(first, NAME);
        requireNonNull(more, "names");
        for (final String name : more) {
            requireNonNull(name, NAME);
        }
        if (headers.isEmpty()) {
            return this;
        }

        final Map<String, String> kept = copyOfHeaders();
        kept.remove(first);
        for (final String name : more) {
            kept.remove(name);
        }
        return kept.size() == headers.size() ? this : freeze(kept, profile);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final HttpHeaders that) || headers.size() != that.headers.size()) {
            return false;
        }
        for (final Map.Entry<String, String> entry : headers.entrySet()) {
            if (!that.get(entry.getKey()).equals(Optional.of(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (final Map.Entry<String, String> entry : headers.entrySet()) {
            hash += Objects.hash(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return hash;
    }

    @Override
    public String toString() {
        final Map<String, String> printed = new LinkedHashMap<>();
        headers.forEach((name, value) -> printed.put(name, Redaction.of(name, value)));
        return "HttpHeaders" + printed;
    }

    private Map<String, String> copyOfHeaders() {
        final Map<String, String> copy = caseInsensitiveMap();
        copy.putAll(headers);
        return copy;
    }

    private void putLayer(final Map<String, String> target, final HttpHeaders layer) {
        if (profile.trusts(layer.profile)) {
            target.putAll(layer.headers);
            return;
        }
        layer.headers.forEach((name, value) -> putValid(target, profile, name, value));
    }

    private static void putValid(
            final Map<String, String> target, final Profile profile, final String name, final String value) {
        requireNonNull(name, NAME);
        requireNonNull(value, "value");
        profile.requireValid(name, value);
        target.put(name, value.trim());
    }

    private static Map<String, String> caseInsensitiveMap() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static HttpHeaders freeze(final Map<String, String> headers, final Profile profile) {
        if (headers.isEmpty()) {
            return profile == Profile.REQUEST ? EMPTY_REQUEST : EMPTY_RESPONSE;
        }
        return new HttpHeaders(Collections.unmodifiableMap(headers), profile);
    }

    /** Hides the value of a header that carries a credential, for {@link #toString()}. */
    private static final class Redaction {
        private static final String REDACTED = "***";

        // Lower-cased, because the lookup uses the lower-cased name.
        private static final Set<String> HEADER_NAMES = Stream.of(
                        AUTHORIZATION, PROXY_AUTHORIZATION, COOKIE, SET_COOKIE)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        // A header the library does not know, such as X-Api-Token on a proxied call, is hidden by what its name says.
        private static final List<String> NAME_PARTS =
                List.of("token", "secret", "password", "credential", "key", "auth", "signature", "session", "cookie");

        private Redaction() {
            throw new UnsupportedOperationException("Utility class");
        }

        static String of(final String name, final String value) {
            final String lowerCaseName = name.toLowerCase(Locale.ROOT);
            final boolean isHidden =
                    HEADER_NAMES.contains(lowerCaseName) || NAME_PARTS.stream().anyMatch(lowerCaseName::contains);
            return isHidden ? REDACTED : value;
        }
    }

    /** The rules that every entry of an instance has passed. */
    private enum Profile {
        REQUEST(HttpHeadersValidator::requireValidRequestHeader),
        RESPONSE((name, value) -> {});

        private final BiConsumer<String, String> rule;

        Profile(final BiConsumer<String, String> rule) {
            this.rule = rule;
        }

        void requireValid(final String name, final String value) {
            rule.accept(name, value);
        }

        // Every entry that passed the request rules also passes the response rules, but not the other way round.
        boolean trusts(final Profile layer) {
            return this == RESPONSE || layer == REQUEST;
        }
    }
}
