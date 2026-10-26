package io.github.piotr_janczukowicz.http.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class ParsedUrlTest {

    @Test
    void of_shouldExposeNormalisedParts() {
        final ParsedUrl url = ParsedUrl.of(URI.create("HTTPS://API.Example.Test/a/./b/../c?x=1"));

        assertSoftly(softly -> {
            softly.assertThat(url.scheme()).isEqualTo("https");
            softly.assertThat(url.host()).isEqualTo("api.example.test");
            softly.assertThat(url.port()).isEqualTo(443);
            softly.assertThat(url.encodedPath()).isEqualTo("/a/c");
            softly.assertThat(url.isHttps()).isTrue();
            softly.assertThat(url.toUri()).isEqualTo(URI.create("https://api.example.test/a/c?x=1"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:dev@example.com", "ftp://example.test/x", "/relative", "urn:isbn:1"})
    void parse_shouldReturnNull_whenUriIsNotAbsoluteHttp(final String uri) {
        assertThat(ParsedUrl.parse(URI.create(uri))).isNull();
    }

    @Test
    void of_shouldThrow_whenUriIsNotAbsoluteHttp() {
        final var uri = URI.create("mailto:dev@example.com");

        final Throwable thrown = catchThrowable(() -> ParsedUrl.of(uri));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uri");
    }

    @Test
    void resolveApi_shouldMatchUriOverload_whenBaseIsParsed() {
        final ParsedUrl base = ParsedUrl.of(URI.create("https://api.example.test/"));

        final ParsedUrl url = base.resolveApi("/users", Map.of("since", List.of("1")));

        assertThat(url.toUri())
                .isEqualTo(HttpUris.resolveApiPath(
                        URI.create("https://api.example.test/"), "/users", Map.of("since", List.of("1"))));
    }

    @Test
    void resolveApi_shouldThrowIllegalArgument_whenPathIsNotHttpReference() {
        final ParsedUrl base = ParsedUrl.of(URI.create("https://api.example.test/"));

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> base.resolveApi("mailto:x", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void parse_shouldThrow_whenUriIsNull() {
        final Throwable thrown = catchThrowable(() -> ParsedUrl.parse(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @Test
    void resolve_shouldResolveRelativeAndAbsoluteReferences() {
        final ParsedUrl base = ParsedUrl.of(URI.create("https://api.example.test/v1/users/alice"));

        assertSoftly(softly -> {
            softly.assertThat(base.resolve("../orgs").toUri())
                    .isEqualTo(URI.create("https://api.example.test/v1/orgs"));
            softly.assertThat(base.resolve("/x?y=1").toUri()).isEqualTo(URI.create("https://api.example.test/x?y=1"));
            softly.assertThat(base.resolve("https://other.test/z").host()).isEqualTo("other.test");
            softly.assertThat(base.resolve("mailto:a@b.c")).isNull();
            softly.assertThat(base.resolve("ftp://other.test/z")).isNull();
        });
    }

    @Test
    void resolve_shouldThrow_whenReferenceIsNull() {
        final ParsedUrl base = ParsedUrl.of(URI.create("https://api.example.test/"));

        final Throwable thrown = catchThrowable(() -> base.resolve(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("reference");
    }

    @Test
    void withQueryValues_shouldReturnSameUrl_whenQueryIsEmpty() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a"));

        assertThat(url.withQueryValues(Map.of())).isSameAs(url);
    }

    @Test
    void withQueryValues_shouldAppendAndEncodeValues() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a?keep=1"));

        final ParsedUrl withQuery = url.withQueryValues(Map.of("q", List.of("a b", "c|d", "{e}", "zażółć")));

        assertThat(withQuery.toUri().getRawQuery())
                .isEqualTo("keep=1&q=a%20b&q=c%7Cd&q=%7Be%7D&q=za%C5%BC%C3%B3%C5%82%C4%87");
    }

    @Test
    void withQueryValues_shouldThrow_whenQueryIsNull() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a"));

        final Throwable thrown = catchThrowable(() -> url.withQueryValues(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("query");
    }

    @Test
    void hasSameOrigin_shouldCompareSchemeHostAndEffectivePort() {
        final ParsedUrl base = ParsedUrl.of(URI.create("https://api.example.test/a"));

        assertSoftly(softly -> {
            softly.assertThat(base.hasSameOrigin(ParsedUrl.of(URI.create("https://API.example.test:443/b"))))
                    .isTrue();
            softly.assertThat(base.hasSameOrigin(ParsedUrl.of(URI.create("http://api.example.test/a"))))
                    .isFalse();
            softly.assertThat(base.hasSameOrigin(ParsedUrl.of(URI.create("https://api.example.test:8443/a"))))
                    .isFalse();
            softly.assertThat(base.hasSameOrigin(ParsedUrl.of(URI.create("https://other.test/a"))))
                    .isFalse();
        });
    }

    @Test
    void describeSafe_shouldDropQueryUserInfoAndDefaultPortAndBracketIpv6() {
        assertSoftly(softly -> {
            softly.assertThat(ParsedUrl.of(URI.create("https://user:pw@api.example.test:443/a/b?token=secret#frag"))
                            .describeSafe())
                    .isEqualTo("https://api.example.test/a/b");
            softly.assertThat(ParsedUrl.of(URI.create("http://api.example.test:8080/a"))
                            .describeSafe())
                    .isEqualTo("http://api.example.test:8080/a");
            softly.assertThat(ParsedUrl.of(URI.create("http://[::1]:3000/x")).describeSafe())
                    .isEqualTo("http://[::1]:3000/x");
        });
    }

    @Test
    void toString_shouldShowOnlyTheSafeForm() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a?token=secret"));

        assertThat(url.toString()).isEqualTo("https://api.example.test/a").doesNotContain("secret");
    }

    @Test
    void equalsAndHashCode_shouldFollowTheNormalisedUrl() {
        final ParsedUrl first = ParsedUrl.of(URI.create("https://API.example.test/a/./b"));
        final ParsedUrl same = ParsedUrl.of(URI.create("https://api.example.test/a/b"));
        final ParsedUrl other = ParsedUrl.of(URI.create("https://api.example.test/a/c"));

        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
            softly.assertThat(first).isNotEqualTo(null).isNotEqualTo("https://api.example.test/a/b");
        });
    }

    @Test
    void toOkHttpUrl_shouldExposeTheParsedUrl() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a?x=1"));

        assertThat(url.toOkHttpUrl()).hasToString("https://api.example.test/a?x=1");
    }

    @Test
    void equals_shouldBeTrue_whenComparedWithItself() {
        final ParsedUrl url = ParsedUrl.of(URI.create("https://api.example.test/a"));

        final boolean isEqual = url.equals(url);

        assertThat(isEqual).isTrue();
    }
}
