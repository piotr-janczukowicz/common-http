package pl.piotrjanczukowicz.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class PathSegmentsTest {

    @ParameterizedTest
    @MethodSource("goldenSegments")
    void append_shouldKeepGoldenOutput_whenSegmentIsEdgeCase(final String raw, final String expected) {
        assertThat(appended("value", false, raw)).isEqualTo("/" + expected);
    }

    @ParameterizedTest
    @MethodSource("encodingCorpus")
    void append_shouldMatchOkHttpPathSegmentEncoding_whenSingleSegment(final String value) {
        assertThat(appended("value", false, value)).isEqualTo("/" + okHttpSegment(value));
    }

    @ParameterizedTest
    @MethodSource("encodingCorpus")
    void append_shouldMatchOkHttpPathSegmentEncoding_whenMultiSegmentPieces(final String value) {
        final StringBuilder expected = new StringBuilder("/pre");
        for (final String piece : value.split("/", -1)) {
            expected.append('/').append(okHttpSegment(piece));
        }
        expected.append("/post");

        assertThat(appended("path", true, "pre/" + value + "/post")).isEqualTo(expected.toString());
    }

    @ParameterizedTest
    @MethodSource("goldenMultiSegments")
    void append_shouldKeepGoldenPath_whenMultiSegmentValueIsEdgeCase(final String value, final String expected) {
        assertThat(appended("path", true, value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"src/main/Foo.java, /src/main/Foo.java", "/src/Foo.java, /src/Foo.java", "src/, /src"})
    void append_shouldSplitAndTrim_whenMultiSegmentPlaceholder(final String value, final String expected) {
        assertThat(appended("path", true, value)).isEqualTo(expected);
    }

    @Test
    void append_shouldEncodeSpacesInPieces_whenMultiSegmentPlaceholder() {
        assertThat(appended("branch", true, "feat/my branch")).isEqualTo("/feat/my%20branch");
    }

    @Test
    void append_shouldPercentEncodeControlAndDelete_whenPresent() {
        assertThat(appended("value", false, "a\u0001b\u007fc")).isEqualTo("/a%01b%7Fc");
    }

    @Test
    void append_shouldAppendUnreservedValuesUnchangedAndStringifyNumbers() {
        final StringBuilder path = new StringBuilder();

        PathSegments.append(path, "x", false, "Az09-._~");
        PathSegments.append(path, "y", false, 42);
        PathSegments.append(path, "z", false, -7L);

        assertThat(path).hasToString("/Az09-._~/42/-7");
    }

    @Test
    void append_shouldKeepMinusSign_whenNumberIsNegative() {
        assertThat(appended("id", false, Long.MIN_VALUE)).isEqualTo("/" + Long.MIN_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", ".", ".."})
    void append_shouldThrow_whenSingleSegmentIsUnsafe(final String segment) {
        final Throwable thrown = catchThrowable(() -> appended("username", false, segment));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path value for {username}")
                .hasMessageContaining("is not a valid path segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "a/../../x", "a//b", "///"})
    void append_shouldThrow_whenMultiSegmentPieceInvalid(final String path) {
        final Throwable thrown = catchThrowable(() -> appended("path", true, path));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path value for {path}")
                .hasMessageContaining("is not a valid path segment")
                .hasMessageNotContaining(path);
    }

    @Test
    void append_shouldThrow_whenValueNull() {
        final Throwable thrown = catchThrowable(() -> appended("username", false, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("username must not be null");
    }

    @Test
    void append_shouldThrow_whenValueTypeUnsupported() {
        final var uri = URI.create("https://example.test");

        final Throwable thrown = catchThrowable(() -> appended("username", false, uri));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{username}")
                .hasMessageContaining("URI");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", ".", ".."})
    void isUnsafe_shouldBeTrue_whenBlankOrDotSegment(final String segment) {
        assertThat(PathSegments.isUnsafe(segment)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"...", ".hidden", "a b", "%2e"})
    void isUnsafe_shouldBeFalse_whenOrdinarySegment(final String segment) {
        assertThat(PathSegments.isUnsafe(segment)).isFalse();
    }

    @Test
    void encode_shouldPercentEncodeSlashAndPercent() {
        assertThat(PathSegments.encode("a/b%c d")).isEqualTo("a%2Fb%25c%20d");
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<PathSegments> constructor = PathSegments.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static String appended(final String name, final boolean multi, final Object value) {
        final StringBuilder path = new StringBuilder();
        PathSegments.append(path, name, multi, value);
        return path.toString();
    }

    private static String okHttpSegment(final String segment) {
        return HttpUrl.get("http://localhost/")
                .newBuilder()
                .addPathSegment(segment)
                .build()
                .encodedPathSegments()
                .getFirst();
    }

    private static Stream<Arguments> goldenMultiSegments() {
        return Stream.of(
                Arguments.of("a b/c d", "/a%20b/c%20d"),
                Arguments.of("/a/b/", "/a/b"),
                Arguments.of("x%2Fy/z", "/x%252Fy/z"),
                Arguments.of("a?b/c#d", "/a%3Fb/c%23d"),
                Arguments.of("café/€", "/caf%C3%A9/%E2%82%AC"),
                Arguments.of("...", "/..."),
                Arguments.of("main...feature/foo", "/main...feature/foo"));
    }

    private static Stream<String> encodingCorpus() {
        return Stream.of(
                "plain",
                "Mixed-Case_1.2~3",
                "a b",
                "a+b",
                "100%",
                "%2e",
                "%2F",
                "a%20b",
                "x?y#z",
                "q=1&r=2",
                "semi;colon",
                "colon:name",
                "at@sign",
                "bang!",
                "quote\"s'",
                "paren(s)",
                "star*",
                "back\\slash",
                "pipe|bar",
                "curly{brace}",
                "caret^tick`",
                "angle<br>",
                "zażółć",
                "日本語",
                "emoji😀",
                "tab\there",
                "...",
                ".hidden",
                "a.b",
                "trailing.",
                "~tilde",
                "-dash",
                "_under",
                "0",
                "007");
    }

    private static Stream<Arguments> goldenSegments() {
        return Stream.of(
                Arguments.of("...", "..."),
                Arguments.of("%2e", "%252e"),
                Arguments.of("%2E%2e", "%252E%252e"),
                Arguments.of("/", "%2F"),
                Arguments.of("a/b", "a%2Fb"),
                Arguments.of("\\", "%5C"),
                Arguments.of("%", "%25"),
                Arguments.of("+", "+"),
                Arguments.of(";", ";"),
                Arguments.of(":", ":"),
                Arguments.of("@", "@"),
                Arguments.of("=", "="),
                Arguments.of("&", "&"),
                Arguments.of("'", "'"),
                Arguments.of("[]", "[]"),
                Arguments.of("a b", "a%20b"),
                Arguments.of("?", "%3F"),
                Arguments.of("#", "%23"),
                Arguments.of("\"", "%22"),
                Arguments.of("<>^`{}|", "%3C%3E%5E%60%7B%7D%7C"),
                Arguments.of("é", "%C3%A9"),
                Arguments.of("€", "%E2%82%AC"),
                Arguments.of("😀", "%F0%9F%98%80"),
                Arguments.of("-._~", "-._~"),
                Arguments.of("AZaz09", "AZaz09"),
                Arguments.of("a\tb", "a%09b"),
                Arguments.of("a\nb", "a%0Ab"),
                Arguments.of("a\u0001b", "a%01b"));
    }
}
