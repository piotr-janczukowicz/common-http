package io.github.piotr_janczukowicz.uri;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import okhttp3.HttpUrl;

/**
 * Validation and percent-encoding of the path segments that {@link PathTemplate} writes into a path.
 *
 * <p>Internal: used by {@link PathTemplate}.
 */
final class PathSegments {

    private static final HttpUrl SEGMENT_BASE = HttpUrl.get("http://localhost/");

    private PathSegments() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Appends {@code value} for the placeholder {@code name} to {@code path} as one segment, or as several for a
     * multi-segment placeholder, each preceded by {@code /}.
     */
    static void append(final StringBuilder path, final String name, final boolean multi, final Object value) {
        final Object present = requireNonNull(value, name);
        final String raw = stringify(name, present);
        if (multi) {
            final String trimmed = trimOuterSlashes(raw);
            requireSafePathSegment(trimmed, name);
            int start = 0;
            while (true) {
                final int slash = trimmed.indexOf('/', start);
                final String piece = slash < 0 ? trimmed.substring(start) : trimmed.substring(start, slash);
                requireSafePathSegment(piece, name);
                appendEncoded(path.append('/'), piece);
                if (slash < 0) {
                    break;
                }
                start = slash + 1;
            }
            return;
        }
        requireSafePathSegment(raw, name);
        appendEncoded(path.append('/'), raw);
    }

    /**
     * Percent-encodes {@code segment} as one path segment ({@code /} becomes {@code %2F}, {@code %} becomes
     * {@code %25}), using OkHttp's path-segment encoding.
     *
     * <p>{@link HttpUrl.Builder#addPathSegment} silently drops {@code .} and an empty segment and turns {@code ..} into
     * a parent step, so callers reject those three first. A value such as {@code %2e} is data, not a dot segment: its
     * {@code %} is encoded to {@code %25} and it stays one literal segment.
     */
    static String encode(final String segment) {
        return SEGMENT_BASE
                .newBuilder()
                .addPathSegment(segment)
                .build()
                .encodedPathSegments()
                .getFirst();
    }

    static boolean isUnsafe(final String segment) {
        return segment.isBlank() || ".".equals(segment) || "..".equals(segment);
    }

    private static String pathValueLabel(final String name) {
        return "path value for {" + name + "}";
    }

    /** Appends {@code segment} as is when it only has unreserved characters, otherwise percent-encoded. */
    private static void appendEncoded(final StringBuilder path, final String segment) {
        if (isUnreserved(segment)) {
            path.append(segment);
        } else {
            path.append(encode(segment));
        }
    }

    /** RFC 3986 unreserved characters: {@code A-Z a-z 0-9 - . _ ~}, which {@link #encode} leaves alone. */
    private static boolean isUnreserved(final String segment) {
        for (int i = 0; i < segment.length(); i++) {
            final char c = segment.charAt(i);
            final boolean isUnreservedChar = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~';
            if (!isUnreservedChar) {
                return false;
            }
        }
        return true;
    }

    private static String stringify(final String name, final Object value) {
        return switch (value) {
            case final String string -> string;
            case final Integer integer -> integer.toString();
            case final Long longValue -> longValue.toString();
            default ->
                throw new IllegalArgumentException(pathValueLabel(name) + " has unsupported type "
                        + value.getClass().getName());
        };
    }

    private static void requireSafePathSegment(final String segment, final String name) {
        if (isUnsafe(segment)) {
            throw new IllegalArgumentException(pathValueLabel(name) + " is not a valid path segment");
        }
    }

    private static String trimOuterSlashes(final String part) {
        int start = 0;
        int end = part.length();
        while (start < end && part.charAt(start) == '/') {
            start++;
        }
        while (end > start && part.charAt(end - 1) == '/') {
            end--;
        }
        return part.substring(start, end);
    }
}
