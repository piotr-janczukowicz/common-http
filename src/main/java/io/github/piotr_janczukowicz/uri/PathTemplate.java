package io.github.piotr_janczukowicz.uri;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI path template expanded into an encoded URI path.
 *
 * <p>Internal: used by the {@code *Api} classes.
 */
public final class PathTemplate {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final String ROOT_PATH = "/";
    // Room per placeholder for the value, beyond the width of the placeholder text it replaces.
    private static final int EXPANSION_HEADROOM = 16;

    private final String text;
    private final List<TemplatePart> parts;
    private final int placeholderCount;

    private PathTemplate(final String text, final List<TemplatePart> parts, final int placeholderCount) {
        this.text = text;
        this.parts = parts;
        this.placeholderCount = placeholderCount;
    }

    /**
     * Parsed template for {@code text}.
     *
     * <p>Grammar: {@code /} alone is allowed. Otherwise each segment is a literal or a whole-segment {@code {name}}
     * (one segment) or {@code {+name}} (multi-segment, OpenAPI {@code x-multi-segment}). Names match
     * {@code [a-z][a-z0-9_-]*}. Rejected: no leading {@code /}, empty segment, {@code .}/{@code ..} literal,
     * {@code {}}, partial-segment or unbalanced placeholder, duplicate name.
     *
     * @param text OpenAPI path, such as {@code /repos/{owner}/{repo}}
     * @return template
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalArgumentException if {@code text} is not a valid path template
     */
    public static PathTemplate of(final String text) {
        requireNonNull(text, "text");
        if (!text.startsWith(ROOT_PATH)) {
            throw invalid(text);
        }
        if (ROOT_PATH.equals(text)) {
            return new PathTemplate(text, List.of(), 0);
        }

        final String[] rawSegments = text.substring(1).split("/", -1);
        final List<TemplatePart> parts = new ArrayList<>(rawSegments.length);
        final Set<String> names = new HashSet<>();
        int placeholders = 0;
        for (final String raw : rawSegments) {
            if (raw.isEmpty()) {
                throw invalid(text);
            }
            final TemplatePart part = parseSegment(text, raw, names);
            parts.add(part);
            placeholders += switch (part) {
                case final Placeholder ignored -> 1;
                case final Literal ignored -> 0;
            };
        }
        return new PathTemplate(text, List.copyOf(parts), placeholders);
    }

    /**
     * Encoded path for {@code values} in placeholder order.
     *
     * <p>{@code {name}} percent-encodes {@code /} as {@code %2F}. {@code {+name}} trims outer {@code /} and splits on
     * {@code /}.
     *
     * <p>The {@code %2F} keeps a value such as {@code area/ui} in one path segment. It relies on the server not
     * decoding {@code %2F} before it normalizes the path: a front end that does would read {@code ..%2Fx} as
     * {@code ../x}. Put a proxy that decodes it in front of the API at your own risk.
     *
     * @param values one value per placeholder ({@link String}, {@link Integer}, or {@link Long})
     * @return encoded path such as {@code /repos/example/hello-world}
     * @throws NullPointerException if a value is {@code null}
     * @throws IllegalArgumentException if the value count, type, or segment is invalid (blank, {@code .}, {@code ..})
     */
    public String expand(final Object... values) {
        requireNonNull(values, "values");
        if (values.length != placeholderCount) {
            throw new IllegalArgumentException(
                    "template " + text + " expected " + placeholderCount + " values, got " + values.length);
        }

        final StringBuilder path = new StringBuilder(text.length() + EXPANSION_HEADROOM * placeholderCount);
        int valueIndex = 0;
        for (final TemplatePart part : parts) {
            switch (part) {
                case Literal(final String encoded) -> path.append('/').append(encoded);
                case Placeholder(final String name, final boolean multi) -> {
                    PathSegments.append(path, name, multi, values[valueIndex]);
                    valueIndex++;
                }
            }
        }
        return path.isEmpty() ? ROOT_PATH : path.toString();
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return text.equals(((PathTemplate) other).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    private static TemplatePart parseSegment(final String text, final String raw, final Set<String> names) {
        if (raw.indexOf('{') < 0 && raw.indexOf('}') < 0) {
            return parseLiteral(text, raw);
        }
        return parsePlaceholder(text, raw, names);
    }

    private static Literal parseLiteral(final String text, final String raw) {
        if (PathSegments.isUnsafe(raw)) {
            throw invalid(text);
        }
        return new Literal(PathSegments.encode(raw));
    }

    private static Placeholder parsePlaceholder(final String text, final String raw, final Set<String> names) {
        if (raw.charAt(0) != '{' || raw.charAt(raw.length() - 1) != '}' || raw.indexOf('{', 1) >= 0) {
            throw invalid(text);
        }
        final String interior = raw.substring(1, raw.length() - 1);
        final boolean multi = interior.startsWith("+");
        final String name = multi ? interior.substring(1) : interior;
        if (!NAME.matcher(name).matches() || !names.add(name)) {
            throw invalid(text);
        }
        return new Placeholder(name, multi);
    }

    private static IllegalArgumentException invalid(final String text) {
        return new IllegalArgumentException("invalid path template: " + text);
    }

    private sealed interface TemplatePart permits Literal, Placeholder {}

    private record Literal(String encoded) implements TemplatePart {}

    private record Placeholder(String name, boolean multi) implements TemplatePart {}
}
