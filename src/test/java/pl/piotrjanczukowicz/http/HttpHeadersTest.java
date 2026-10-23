package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpHeadersTest {

    private static final String NOT_A_TOKEN = "header name is not an RFC 9110 token";
    private static final String BAD_VALUE = "header value contains a forbidden character";

    @Test
    void of_shouldReturnHeaders_whenPairsValid() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "value", "Accept", "application/json");

        assertSoftly(softly -> {
            softly.assertThat(headers.get("X-Test")).contains("value");
            softly.assertThat(headers.get("Accept")).contains("application/json");
        });
    }

    @Test
    void of_shouldThrow_whenPairsLengthOdd() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of("X-Test"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected alternating name/value pairs");
    }

    @Test
    void of_shouldThrow_whenArrayNull() {
        final String[] namesAndValues = null;

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(namesAndValues));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("namesAndValues");
    }

    @Test
    void of_shouldThrow_whenNameNull() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(null, "value"));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void of_shouldThrow_whenValueNull() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of("X-Test", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void of_shouldReplaceValue_whenSameNameDiffersByCase() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "first", "x-test", "second");

        assertSoftly(softly -> {
            softly.assertThat(headers.asMap()).hasSize(1);
            softly.assertThat(headers.get("X-TEST")).contains("second");
        });
    }

    @Test
    void of_shouldTrimValue_whenValueHasSurroundingWhitespace() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "  value \t");

        assertThat(headers.get("X-Test")).contains("value");
    }

    @Test
    void of_shouldThrow_whenNameNotToken() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of("X Test", "value"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(NOT_A_TOKEN);
    }

    @Test
    void of_shouldThrow_whenNameForbidden() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of("Content-Length", "1"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header name is forbidden: Content-Length");
    }

    @Test
    void of_shouldThrow_whenValueHasControlCharacter() {
        final Throwable thrown = catchThrowable(() -> HttpHeaders.of("X-Test", "a\rb"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_VALUE);
    }

    @Test
    void of_shouldReturnEmpty_whenMapNull() {
        final Map<String, String> headers = null;

        final HttpHeaders result = HttpHeaders.of(headers);

        assertThat(result).isSameAs(HttpHeaders.empty());
    }

    @Test
    void of_shouldReturnEmpty_whenMapEmpty() {
        final HttpHeaders result = HttpHeaders.of(Map.of());

        assertThat(result).isSameAs(HttpHeaders.empty());
    }

    @Test
    void of_shouldReturnHeaders_whenMapValid() {
        final HttpHeaders result = HttpHeaders.of(Map.of("X-Test", "value"));

        assertThat(result.get("X-Test")).contains("value");
    }

    @Test
    void of_shouldThrow_whenMapNameNull() {
        final Map<String, String> headers = new HashMap<>();
        headers.put(null, "value");

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(headers));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void of_shouldThrow_whenMapValueNull() {
        final Map<String, String> headers = Collections.singletonMap("X-Test", null);

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(headers));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void of_shouldThrow_whenMapNameNotToken() {
        final Map<String, String> headers = Map.of("X Test", "value");

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(headers));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(NOT_A_TOKEN);
    }

    @Test
    void of_shouldThrow_whenMapNameForbidden() {
        final Map<String, String> headers = Map.of("Upgrade", "websocket");

        final Throwable thrown = catchThrowable(() -> HttpHeaders.of(headers));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("header name is forbidden: Upgrade");
    }

    @Test
    void empty_shouldReturnSharedInstance_whenCalled() {
        final HttpHeaders expected = HttpHeaders.of(Map.of());

        final HttpHeaders headers = HttpHeaders.empty();

        assertThat(headers).isSameAs(expected);
    }

    @Test
    void empty_shouldBeEmpty_whenCalled() {
        final HttpHeaders headers = HttpHeaders.empty();

        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void fromWire_shouldReturnEmpty_whenMapNull() {
        final Map<String, List<String>> headers = null;

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void fromWire_shouldReturnEmpty_whenMapEmpty() {
        final HttpHeaders result = HttpHeaders.fromWire(Map.of());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void fromWire_shouldSkipNullOrBlankNames_whenPresent() {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put(null, List.of("lost"));
        headers.put("  ", List.of("blank"));
        headers.put("X-Kept", List.of("kept"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.asMap()).containsOnlyKeys("X-Kept");
    }

    @Test
    void fromWire_shouldSkipPseudoHeaders_whenNameStartsWithColon() {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(":status", List.of("200"));
        headers.put("X-Kept", List.of("kept"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.asMap()).containsOnlyKeys("X-Kept");
    }

    @Test
    void fromWire_shouldSkipName_whenValueListNull() {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Null", null);
        headers.put("X-Kept", List.of("kept"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.asMap()).containsOnlyKeys("X-Kept");
    }

    @Test
    void fromWire_shouldSkipName_whenValueListEmpty() {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Empty", List.of());
        headers.put("X-Kept", List.of("kept"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.asMap()).containsOnlyKeys("X-Kept");
    }

    @Test
    void fromWire_shouldSkipNullElements_whenListHasNulls() {
        final Map<String, List<String>> headers = Map.of("X-Mixed", Arrays.asList(null, "kept"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.get("X-Mixed")).contains("kept");
    }

    @Test
    void fromWire_shouldKeepSingleValueAsReceived_whenValueHasWhitespaceAndNonAscii() {
        final Map<String, List<String>> headers = Map.of("X-Test", List.of(" padded é "));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.get("X-Test")).contains(" padded é ");
    }

    @Test
    void fromWire_shouldJoinValuesInOrder_whenSeveralValues() {
        final Map<String, List<String>> headers = Map.of("X-Test", List.of("one", "two"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.get("X-Test")).contains("one, two");
    }

    @Test
    void fromWire_shouldMergeCaseVariants_whenNameRepeatsInOtherCase() {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Test", List.of("one"));
        headers.put("x-test", List.of("two"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertSoftly(softly -> {
            softly.assertThat(result.asMap()).containsOnlyKeys("X-Test");
            softly.assertThat(result.get("X-Test")).contains("one, two");
        });
    }

    @Test
    void fromWire_shouldKeepForbiddenNames_whenResponseHeadersGiven() {
        final Map<String, List<String>> headers = Map.of(
                "Content-Length", List.of("5"),
                "Transfer-Encoding", List.of("chunked"),
                "Upgrade", List.of("websocket"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertSoftly(softly -> {
            softly.assertThat(result.get("Content-Length")).contains("5");
            softly.assertThat(result.get("Transfer-Encoding")).contains("chunked");
            softly.assertThat(result.get("Upgrade")).contains("websocket");
        });
    }

    @Test
    void fromWire_shouldNotThrow_whenValueHasControlCharacter() {
        final Map<String, List<String>> headers = Map.of("X-Test", List.of("a\r\nb\u0000"));

        final HttpHeaders result = HttpHeaders.fromWire(headers);

        assertThat(result.get("X-Test")).contains("a\r\nb\u0000");
    }

    @Test
    void get_shouldFindValue_whenNameDiffersByCase() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "value");

        final Optional<String> value = headers.get("x-test");

        assertThat(value).contains("value");
    }

    @Test
    void get_shouldBeEmpty_whenNameAbsent() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "value");

        final Optional<String> value = headers.get("Missing");

        assertThat(value).isEmpty();
    }

    @Test
    void get_shouldThrow_whenNameNull() {
        final HttpHeaders headers = HttpHeaders.of("X-Test", "value");

        final Throwable thrown = catchThrowable(() -> headers.get(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void asMap_shouldBeUnmodifiable_whenCalled() {
        final Map<String, String> map = HttpHeaders.of("X-Test", "value").asMap();

        final Throwable thrown = catchThrowable(() -> map.put("X-Other", "value"));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void asMap_shouldLookUpCaseInsensitively_whenKeyDiffersByCase() {
        final Map<String, String> map = HttpHeaders.of("X-Test", "value").asMap();

        final String value = map.get("x-test");

        assertThat(value).isEqualTo("value");
    }

    @Test
    void asMap_shouldKeepFirstCasing_whenNameRepeats() {
        final Map<String, String> map =
                HttpHeaders.of("X-Test", "a", "x-test", "b").asMap();

        assertThat(map.keySet()).containsExactly("X-Test");
    }

    @Test
    void with_shouldAddEntry_whenNameNew() {
        final HttpHeaders base = HttpHeaders.of("A", "1");

        final HttpHeaders result = base.with("B", "2");

        assertSoftly(softly -> {
            softly.assertThat(result.get("A")).contains("1");
            softly.assertThat(result.get("B")).contains("2");
            softly.assertThat(base.get("B")).isEmpty();
        });
    }

    @Test
    void with_shouldReplaceValueKeepingCasing_whenNameDiffersByCase() {
        final HttpHeaders base = HttpHeaders.of("X-Test", "a");

        final HttpHeaders result = base.with("x-test", "b");

        assertSoftly(softly -> {
            softly.assertThat(result.asMap()).containsOnlyKeys("X-Test");
            softly.assertThat(result.get("X-Test")).contains("b");
        });
    }

    @Test
    void with_shouldTrimValue_whenValuePadded() {
        final HttpHeaders result = HttpHeaders.of("A", "1").with("B", "  2 ");

        assertThat(result.get("B")).contains("2");
    }

    @Test
    void with_shouldThrow_whenRequestRuleBroken() {
        final HttpHeaders base = HttpHeaders.of("A", "1");

        final Throwable thrown = catchThrowable(() -> base.with("Content-Length", "1"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header name is forbidden: Content-Length");
    }

    @Test
    void with_shouldThrow_whenNameNull() {
        final HttpHeaders base = HttpHeaders.of("A", "1");

        final Throwable thrown = catchThrowable(() -> base.with(null, "value"));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void with_shouldThrow_whenValueNull() {
        final HttpHeaders base = HttpHeaders.of("A", "1");

        final Throwable thrown = catchThrowable(() -> base.with("B", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void with_shouldAcceptForbiddenName_whenResponseHeaders() {
        final HttpHeaders base = HttpHeaders.fromWire(Map.of("X-Kept", List.of("1")));

        final HttpHeaders result = base.with("Content-Length", "5");

        assertSoftly(softly -> {
            softly.assertThat(result.get("X-Kept")).contains("1");
            softly.assertThat(result.get("Content-Length")).contains("5");
        });
    }

    @Test
    void merge_shouldLetLaterLayerWin_whenSeveralLayers() {
        final HttpHeaders base = HttpHeaders.of("A", "1", "X-Shared", "base");
        final HttpHeaders first = HttpHeaders.of("X-Shared", "first", "B", "2");
        final HttpHeaders second = HttpHeaders.of("X-Shared", "second");

        final HttpHeaders merged = base.merge(first, second);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("A")).contains("1");
            softly.assertThat(merged.get("B")).contains("2");
            softly.assertThat(merged.get("X-Shared")).contains("second");
        });
    }

    @Test
    void merge_shouldKeepFirstCasing_whenNameDiffersByCase() {
        final HttpHeaders base = HttpHeaders.of("X-Test", "a");

        final HttpHeaders merged = base.merge(HttpHeaders.of("x-test", "b"));

        assertSoftly(softly -> {
            softly.assertThat(merged.asMap()).containsOnlyKeys("X-Test");
            softly.assertThat(merged.get("X-Test")).contains("b");
        });
    }

    @Test
    void merge_shouldReturnSameInstance_whenEveryLayerEmpty() {
        final HttpHeaders base = HttpHeaders.of("A", "1");

        final HttpHeaders merged = base.merge(HttpHeaders.empty(), HttpHeaders.fromWire(Map.of()));

        assertThat(merged).isSameAs(base);
    }

    @Test
    void merge_shouldLeaveOriginalUnchanged_whenLayerMerged() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders layer = HttpHeaders.of("B", "2");

        final HttpHeaders merged = base.merge(layer);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("B")).contains("2");
            softly.assertThat(base.get("B")).isEmpty();
        });
    }

    @Test
    void merge_shouldCheckAndCopyEntries_whenValidResponseHeadersMergedIntoRequestHeaders() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders response = HttpHeaders.fromWire(Map.of("X-Kept", List.of("2")));

        final HttpHeaders merged = base.merge(response);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("A")).contains("1");
            softly.assertThat(merged.get("X-Kept")).contains("2");
        });
    }

    @Test
    void merge_shouldKeepLaterLayers_whenFirstLayerEmpty() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders later = HttpHeaders.of("B", "2");

        final HttpHeaders merged = base.merge(HttpHeaders.empty(), later);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("A")).contains("1");
            softly.assertThat(merged.get("B")).contains("2");
        });
    }

    @Test
    void merge_shouldThrow_whenFirstLayerNull() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders first = null;

        final Throwable thrown = catchThrowable(() -> base.merge(first));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void merge_shouldThrow_whenLayersArrayNull() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders[] more = null;

        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> base.merge(headers, more));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("more");
    }

    @Test
    void merge_shouldThrow_whenLaterLayerNull() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders later = null;

        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> base.merge(headers, later));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void merge_shouldThrow_whenRequestHeadersGetForbiddenResponseEntry() {
        final HttpHeaders base = HttpHeaders.of("A", "1");
        final HttpHeaders response = HttpHeaders.fromWire(Map.of("Content-Length", List.of("5")));

        final Throwable thrown = catchThrowable(() -> base.merge(response));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header name is forbidden: Content-Length");
    }

    @Test
    void merge_shouldThrow_whenEmptyRequestHeadersGetForbiddenResponseEntry() {
        final HttpHeaders response = HttpHeaders.fromWire(Map.of("Content-Length", List.of("5")));

        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> headers.merge(response));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header name is forbidden: Content-Length");
    }

    @Test
    void merge_shouldCopyEntriesAsIs_whenResponseHeadersMergedIntoResponseHeaders() {
        final HttpHeaders base = HttpHeaders.fromWire(Map.of("X-Kept", List.of("1")));
        final HttpHeaders response = HttpHeaders.fromWire(Map.of("Content-Length", List.of("5")));

        final HttpHeaders merged = base.merge(response);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("X-Kept")).contains("1");
            softly.assertThat(merged.get("Content-Length")).contains("5");
        });
    }

    @Test
    void merge_shouldCopyEntries_whenRequestHeadersMergedIntoResponseHeaders() {
        final HttpHeaders base = HttpHeaders.fromWire(Map.of("X-Kept", List.of("1")));
        final HttpHeaders request = HttpHeaders.of("A", "1");

        final HttpHeaders merged = base.merge(request);

        assertSoftly(softly -> {
            softly.assertThat(merged.get("X-Kept")).contains("1");
            softly.assertThat(merged.get("A")).contains("1");
        });
    }

    @Test
    void without_shouldRemoveNames_whenSeveralGivenCaseInsensitively() {
        final HttpHeaders headers = HttpHeaders.of("A", "1", "B", "2", "C", "3");

        final HttpHeaders result = headers.without("a", "b");

        assertThat(result.asMap()).containsOnlyKeys("C");
    }

    @Test
    void without_shouldReturnSameInstance_whenNoNamePresent() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");

        final HttpHeaders result = headers.without("Missing");

        assertThat(result).isSameAs(headers);
    }

    @Test
    void without_shouldReturnSameInstance_whenEmpty() {
        final HttpHeaders headers = HttpHeaders.empty();

        final HttpHeaders result = headers.without("A");

        assertThat(result).isSameAs(headers);
    }

    @Test
    void without_shouldReturnEmpty_whenAllNamesRemoved() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");

        final HttpHeaders result = headers.without("A");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void without_shouldKeepRequestRules_whenAllNamesRemoved() {
        final HttpHeaders emptied = HttpHeaders.of("A", "1").without("A");

        final Throwable thrown = catchThrowable(() -> emptied.with("Content-Length", "1"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header name is forbidden: Content-Length");
    }

    @Test
    void without_shouldKeepResponseRules_whenAllNamesRemoved() {
        final HttpHeaders emptied =
                HttpHeaders.fromWire(Map.of("Content-Length", List.of("5"))).without("Content-Length");

        final HttpHeaders result = emptied.with("Content-Length", "5");

        assertThat(result.get("Content-Length")).contains("5");
    }

    @Test
    void without_shouldThrow_whenNameNull() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");

        final Throwable thrown = catchThrowable(() -> headers.without(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void without_shouldThrow_whenMoreArrayNull() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final String[] more = null;

        final Throwable thrown = catchThrowable(() -> headers.without("A", more));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("names");
    }

    @Test
    void without_shouldThrow_whenLaterNameNull() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");

        final Throwable thrown = catchThrowable(() -> headers.without("A", (String) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("name");
    }

    @Test
    void equals_shouldBeTrue_whenNamesDifferByCaseAndOrder() {
        final HttpHeaders headers = HttpHeaders.of("A", "1", "B", "2");
        final HttpHeaders other = HttpHeaders.of("b", "2", "a", "1");

        final boolean equal = headers.equals(other);

        assertThat(equal).isTrue();
    }

    @Test
    void equals_shouldBeTrue_whenSameInstance() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");

        final boolean equal = headers.equals(headers);

        assertThat(equal).isTrue();
    }

    @Test
    void equals_shouldIgnoreProfile_whenEntriesMatch() {
        final HttpHeaders request = HttpHeaders.of("A", "1");
        final HttpHeaders response = HttpHeaders.fromWire(Map.of("A", List.of("1")));

        final boolean equal = request.equals(response);

        assertThat(equal).isTrue();
    }

    @Test
    void equals_shouldBeFalse_whenValueDiffers() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final HttpHeaders other = HttpHeaders.of("A", "2");

        final boolean equal = headers.equals(other);

        assertThat(equal).isFalse();
    }

    @Test
    void equals_shouldBeFalse_whenNameMissingInOther() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final HttpHeaders other = HttpHeaders.of("B", "1");

        final boolean equal = headers.equals(other);

        assertThat(equal).isFalse();
    }

    @Test
    void equals_shouldBeFalse_whenSizeDiffers() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final HttpHeaders other = HttpHeaders.of("A", "1", "B", "2");

        final boolean equal = headers.equals(other);

        assertThat(equal).isFalse();
    }

    @Test
    void equals_shouldBeFalse_whenOtherType() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final Object other = "A";

        final boolean equal = headers.equals(other);

        assertThat(equal).isFalse();
    }

    @Test
    void equals_shouldBeFalse_whenNull() {
        final HttpHeaders headers = HttpHeaders.of("A", "1");
        final Object other = null;

        final boolean equal = headers.equals(other);

        assertThat(equal).isFalse();
    }

    @Test
    void hashCode_shouldMatch_whenNamesDifferByCase() {
        final HttpHeaders headers = HttpHeaders.of("A", "1", "B", "2");
        final HttpHeaders other = HttpHeaders.of("b", "2", "a", "1");

        final int hash = headers.hashCode();

        assertThat(hash).isEqualTo(other.hashCode());
    }

    @Test
    void toString_shouldRedactValues_whenNameSaysItIsASecret() {
        final HttpHeaders headers = HttpHeaders.of(
                "X-Hub-Signature-256", "sha256=abc",
                "X-Auth", "a-secret-value",
                "X-Session-Id", "s3ss10n",
                "X-Csrf-Cookie", "c00k13",
                "X-Request-Id", "r-1");

        final String text = headers.toString();

        assertSoftly(softly -> {
            softly.assertThat(text)
                    .contains(
                            "X-Hub-Signature-256=***",
                            "X-Auth=***",
                            "X-Session-Id=***",
                            "X-Csrf-Cookie=***",
                            "X-Request-Id=r-1");
            softly.assertThat(text).doesNotContain("sha256=abc", "a-secret-value", "s3ss10n", "c00k13");
        });
    }

    @Test
    void toString_shouldRedactSensitiveValues_whenPrinted() {
        final HttpHeaders headers = HttpHeaders.of(
                "Accept", "application/json",
                "Authorization", "Bearer tok_secret",
                "Cookie", "session=abc",
                "X-Api-Token", "t0k3n");

        final String text = headers.toString();

        assertSoftly(softly -> {
            softly.assertThat(text).startsWith("HttpHeaders");
            softly.assertThat(text).contains("Accept=application/json");
            softly.assertThat(text).contains("Authorization=***", "Cookie=***", "X-Api-Token=***");
            softly.assertThat(text).doesNotContain("tok_secret", "session=abc", "t0k3n");
        });
    }

    @Test
    void redactionConstructor_shouldThrow_whenInvoked() throws Exception {
        final Constructor<?> constructor =
                Class.forName("pl.piotrjanczukowicz.http.HttpHeaders$Redaction").getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
