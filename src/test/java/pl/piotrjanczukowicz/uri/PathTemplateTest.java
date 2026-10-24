package pl.piotrjanczukowicz.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class PathTemplateTest {

    @Test
    void expand_shouldReturnSlash_whenRootTemplate() {
        final PathTemplate root = PathTemplate.of("/");

        final String path = root.expand();

        assertSoftly(softly -> {
            softly.assertThat(path).isEqualTo("/");
            softly.assertThat(root).hasToString("/");
        });
    }

    @Test
    void expand_shouldFillHyphenatedName_whenSpecNameHasHyphen() {
        final PathTemplate template = PathTemplate.of("/enterprises/{enterprise}/teams/{enterprise-team}");

        final String path = template.expand("acme", "core");

        assertThat(path).isEqualTo("/enterprises/acme/teams/core");
    }

    @Test
    void expand_shouldReturnLiteral_whenParameterlessTemplate() {
        final PathTemplate rateLimit = PathTemplate.of("/rate_limit");

        final String path = rateLimit.expand();

        assertSoftly(softly -> {
            softly.assertThat(path).isEqualTo("/rate_limit");
            softly.assertThat(rateLimit).hasToString("/rate_limit");
        });
    }

    @Test
    void expand_shouldPercentEncodeSlash_whenSingleSegmentContainsSlash() {
        final PathTemplate template = PathTemplate.of("/repos/{owner}/{repo}");

        final String path = template.expand("a/b", "r");

        assertThat(path).isEqualTo("/repos/a%2Fb/r");
    }

    @Test
    void expand_shouldStringifyInteger_whenSingleSegmentPlaceholder() {
        final PathTemplate template = PathTemplate.of("/licenses/{license}");

        final String path = template.expand(42);

        assertThat(path).isEqualTo("/licenses/42");
    }

    @Test
    void expand_shouldStringifyLong_whenSingleSegmentPlaceholder() {
        final PathTemplate template = PathTemplate.of("/gists/{gist_id}");

        final String path = template.expand(7L);

        assertThat(path).isEqualTo("/gists/7");
    }

    @Test
    void expand_shouldEncodeLiteralSegments_whenTemplateLiteralHasSpecialCharacters() {
        final String path = PathTemplate.of("/a b/c%d/{x}").expand("v");

        assertThat(path).isEqualTo("/a%20b/c%25d/v");
    }

    @Test
    void expand_shouldKeepCompareDots_whenBaseheadHasSlashInHead() {
        final String path =
                PathTemplate.of("/repos/{owner}/{repo}/compare/{+basehead}").expand("o", "r", "main...feature/foo");

        assertThat(path).isEqualTo("/repos/o/r/compare/main...feature/foo");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "repos/{owner}",
                "//repos",
                "/repos/",
                "/repos/.",
                "/repos/..",
                "/repos/{}",
                "/repos/{+}",
                "/repos/a{b}",
                "/repos/{a}{b}",
                "/repos/{owner",
                "/repos/owner}",
                "/repos/{own{er}",
                "/repos/{Owner}",
                "/repos/{1name}",
                "/repos/{-foo}",
                "/repos/{owner}/{owner}"
            })
    void of_shouldThrow_whenTemplateTextInvalid(final String text) {
        final Throwable thrown = catchThrowable(() -> PathTemplate.of(text));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", ".."})
    void expand_shouldThrow_whenSingleSegmentIsDot(final String segment) {
        final PathTemplate template = PathTemplate.of("/users/{username}");

        final Throwable thrown = catchThrowable(() -> template.expand(segment));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path value for {username}")
                .hasMessageContaining("is not a valid path segment")
                .hasMessageNotContaining(segment);
    }

    @Test
    void expand_shouldThrow_whenTooFewValues() {
        final PathTemplate template = PathTemplate.of("/repos/{owner}/{repo}");

        final Throwable thrown = catchThrowable(() -> template.expand("o"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("template /repos/{owner}/{repo} expected 2 values, got 1");
    }

    @Test
    void expand_shouldThrow_whenTooManyValues() {
        final PathTemplate template = PathTemplate.of("/repos/{owner}/{repo}");

        final Throwable thrown = catchThrowable(() -> template.expand("o", "r", "x"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("template /repos/{owner}/{repo} expected 2 values, got 3");
    }

    @Test
    void expand_shouldThrow_whenRootGivenAValue() {
        final PathTemplate root = PathTemplate.of("/");

        final Throwable thrown = catchThrowable(() -> root.expand("x"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("template / expected 0 values, got 1");
    }

    @Test
    void of_shouldThrow_whenTextNull() {
        final Throwable thrown = catchThrowable(() -> PathTemplate.of(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("text");
    }

    @Test
    void expand_shouldThrow_whenValuesNull() {
        final PathTemplate template = PathTemplate.of("/users/{username}");

        final Throwable thrown = catchThrowable(() -> template.expand((Object[]) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("values");
    }

    @Test
    void equals_shouldUseTemplateText_whenCompared() {
        final PathTemplate left = PathTemplate.of("/repos/{owner}/{repo}");
        final PathTemplate same = PathTemplate.of("/repos/{owner}/{repo}");
        final PathTemplate other = PathTemplate.of("/rate_limit");

        assertSoftly(softly -> {
            softly.assertThat(left).isEqualTo(left);
            softly.assertThat(left).isEqualTo(same);
            softly.assertThat(left).hasSameHashCodeAs(same);
            softly.assertThat(left).isNotEqualTo(other);
            softly.assertThat(left).isNotEqualTo("/repos/{owner}/{repo}");
            softly.assertThat(left).isNotEqualTo(null);
        });
    }
}
