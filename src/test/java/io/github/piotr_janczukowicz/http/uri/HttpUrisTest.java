package io.github.piotr_janczukowicz.http.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class HttpUrisTest {

    @Test
    void describeSafe_shouldKeepRawAuthority_whenHostIsNotParsed() {
        final String description = HttpUris.describeSafe(URI.create("https://u@my_host/x"));

        assertThat(description).isEqualTo("https://my_host/x");
    }

    @Test
    void describeSafe_shouldBeUnsupported_whenAuthorityHasOnlyUserInfo() {
        final String description = HttpUris.describeSafe(URI.create("https://u@/x"));

        assertThat(description).isEqualTo("<unsupported-uri>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:x@example.test", "ftp://example.test/", "/relative/path"})
    void withTrailingSlash_shouldThrowIllegalArgument_whenUriIsNotHttp(final String uri) {
        final var uriValue = URI.create(uri);

        final Throwable thrown = catchThrowable(() -> HttpUris.withTrailingSlash(uriValue, "baseUri"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUri must be an absolute HTTP(S) URL");
    }

    @Test
    void resolveApiPath_shouldThrowIllegalArgument_whenBaseIsNotHttp() {
        final var uri = URI.create("ftp://example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiPath(uri, "user", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseUri");
    }

    @Test
    void resolveReference_shouldThrowIllegalArgument_whenBaseIsNotHttp() {
        final var uri = URI.create("mailto:x@example.test");

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveReference(uri, "next"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:a@b.c", "ftp://host/x"})
    void resolveReference_shouldThrowIllegalArgument_whenReferenceIsNotHttp(final String reference) {
        final URI base = URI.create("https://api.example.test/repos/o/r/tarball/main");

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveReference(base, reference));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reference must resolve against the current request URI");
    }

    @Test
    void withTrailingSlash_shouldThrowNullPointer_whenUriNull() {
        final Throwable thrown = catchThrowable(() -> HttpUris.withTrailingSlash(null, "baseUri"));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("baseUri");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.test", "https://api.example.test/"})
    void withTrailingSlash_shouldEndWithSlash_whenCalled(final String uri) {
        final URI normalized = HttpUris.withTrailingSlash(URI.create(uri), "baseUri");

        assertThat(normalized).hasToString("https://api.example.test/");
    }

    @Test
    void resolve_shouldAppendQuery_whenRelativePathProvided() {
        final URI base = URI.create("https://api.example.test/");

        final URI uri = HttpUris.resolve(base, "users", Map.of("since", List.of("1")));

        assertThat(uri).hasToString("https://api.example.test/users?since=1");
    }

    @Test
    void resolve_shouldPercentEncodeQuery_whenValueHasReservedOrUnicode() {
        final URI base = URI.create("https://api.example.test/");

        final URI uri = HttpUris.resolve(
                base, "search", Map.of("q", List.of("a+b space", "café"), "labels[]", List.of("bug", "help")));

        assertSoftly(softly -> {
            softly.assertThat(uri.toString()).contains("q=a%2Bb%20space");
            softly.assertThat(uri.toString()).contains("q=caf%C3%A9");
            softly.assertThat(uri).hasParameter("labels[]", "bug");
            softly.assertThat(uri).hasParameter("labels[]", "help");
        });
    }

    @Test
    void resolve_shouldRepeatBracketArrayQuery_whenMultipleListValues() {
        final URI base = URI.create("https://api.example.test/");

        final URI uri = HttpUris.resolve(
                base, "orgs/example/personal-access-tokens", Map.of("owner[]", List.of("alice", "bob")));

        assertSoftly(softly -> {
            softly.assertThat(uri).hasParameter("owner[]", "alice");
            softly.assertThat(uri).hasParameter("owner[]", "bob");
        });
    }

    @Test
    void resolveReference_shouldResolveRelativeLocation_whenAgainstCurrentUri() {
        final URI resolved =
                HttpUris.resolveReference(URI.create("https://api.example.test/repos/o/r/tarball/main"), "../other");

        assertThat(resolved).hasToString("https://api.example.test/repos/o/r/other");
    }

    @Test
    void stripUriTemplate_shouldStripUriTemplate_whenUploadUrlHasNameLabelExpansion() {
        final String uploadUrl = "https://uploads.example.test/repos/o/r/releases/1/assets{?name,label}";

        final URI resolved = HttpUris.stripUriTemplate(uploadUrl);

        assertSoftly(softly -> {
            softly.assertThat(resolved.toString()).doesNotContain("{");
            softly.assertThat(resolved.getPath()).isEqualTo("/repos/o/r/releases/1/assets");
        });
    }

    @Test
    void stripUriTemplate_shouldThrow_whenUriStringNull() {
        final Throwable thrown = catchThrowable(() -> HttpUris.stripUriTemplate(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @Test
    void stripUriTemplate_shouldThrowIllegalArgument_whenUriNotHttp() {
        final Throwable thrown = catchThrowable(() -> HttpUris.stripUriTemplate("mailto:dev@example.com"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uri");
    }

    @Test
    void resolveApiPath_shouldStripLeadingSlash_whenPathHasOne() {
        final URI uri = HttpUris.resolveApiPath(URI.create("https://api.example.test/"), "/users", Map.of());

        assertThat(uri).hasToString("https://api.example.test/users");
    }

    @Test
    void resolveApiPath_shouldKeepRelative_whenPathHasNoLeadingSlash() {
        final URI uri = HttpUris.resolveApiPath(
                URI.create("https://api.example.test/"), "users", Map.of("since", List.of("1")));

        assertThat(uri).hasToString("https://api.example.test/users?since=1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "//example.com/other",
                "https://example.com/other",
                "mailto:someone@example.test",
                "users?since=1",
                "users#top",
                "../admin",
                "users/../../admin",
                "users/%2e%2E/admin",
                "users/./x",
                "\\\\h/x",
                "\\/h/x",
                " //h/x",
                "\t//h/x",
                "..\\..\\admin",
                "\\\\api.example.test:81/svc/v1/x"
            })
    void resolveApiPath_shouldReject_whenPathIsNotRelativeApiPath(final String path) {
        final var uri = URI.create("https://api.example.test/svc/v1/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiPath(uri, path, query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative API path");
    }

    @Test
    void resolveApiPath_shouldKeepDotsInSegment_whenSegmentIsNotDotSegment() {
        final URI uri =
                HttpUris.resolveApiPath(URI.create("https://api.example.test/"), "repos/a..b/.hidden", Map.of());

        assertThat(uri).hasToString("https://api.example.test/repos/a..b/.hidden");
    }

    @Test
    void resolveApiPath_shouldAppendQuery_whenQueryPresent() {
        final URI uri = HttpUris.resolveApiPath(
                URI.create("https://api.example.test/"), "/search", Map.of("q", List.of("ruby")));

        assertThat(uri).hasToString("https://api.example.test/search?q=ruby");
    }

    @Test
    void resolveApiPath_shouldKeepEncodedSlash_whenTemplateExpanded() {
        final String path = PathTemplate.of("/repos/{owner}/{repo}").expand("octo", "a/b");

        final URI uri = HttpUris.resolveApiPath(URI.create("https://api.example.test/"), path, Map.of());

        assertThat(uri).hasToString("https://api.example.test/repos/octo/a%2Fb");
    }

    @Test
    void resolveApiPath_shouldRepeatQuery_whenMultipleValues() {
        final URI uri = HttpUris.resolveApiPath(
                URI.create("https://api.example.test/"), "/topics", Map.of("names", List.of("a", "b")));

        assertThat(uri).hasToString("https://api.example.test/topics?names=a&names=b");
    }

    @Test
    void resolveApiHttpUrl_shouldStripLeadingSlashAndAppendQuery_whenPathAndQueryGiven() {
        final HttpUrl base = HttpUrl.get("https://api.example.test/");

        final HttpUrl url = HttpUris.resolveApiHttpUrl(base, "/search", Map.of("q", List.of("a b", "c")));

        assertThat(url).hasToString("https://api.example.test/search?q=a%20b&q=c");
    }

    @Test
    void resolveApiHttpUrl_shouldThrow_whenBasePathHasNoTrailingSlash() {
        final HttpUrl base = HttpUrl.get("https://api.example.test/api/repos");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiHttpUrl(base, "-evil", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must end with /");
    }

    @Test
    void resolveApiHttpUrl_shouldKeepBasePath_whenBaseHasPathPrefix() {
        final HttpUrl base = HttpUrl.get("https://other.example.test/svc/v1/");

        final HttpUrl url = HttpUris.resolveApiHttpUrl(base, "/user", Map.of());

        assertThat(url).hasToString("https://other.example.test/svc/v1/user");
    }

    @Test
    void resolveApiHttpUrl_shouldReject_whenPathIsProtocolRelative() {
        final HttpUrl base = HttpUrl.get("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiHttpUrl(base, "//example.com/other", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative API path");
    }

    @Test
    void resolveApiHttpUrl_shouldThrowIllegalArgument_whenPathIsNotHttpReference() {
        final HttpUrl base = HttpUrl.get("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiHttpUrl(base, "mailto:x@example.test", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveApiHttpUrl_shouldThrowNullPointer_whenPathNull() {
        final HttpUrl base = HttpUrl.get("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiHttpUrl(base, null, query));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("path");
    }

    @Test
    void urisConstructor_shouldThrow_whenInstantiatedViaReflection() throws Exception {
        final Constructor<HttpUris> constructor = HttpUris.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copyQueryValues_shouldCopyLists_whenCalled() {
        final Map<String, List<String>> original = new LinkedHashMap<>();
        original.put("labels[]", new ArrayList<>(List.of("a")));

        final Map<String, List<String>> copy = HttpUris.copyQueryValues(original);
        original.get("labels[]").add("b");

        assertThat(copy.get("labels[]")).containsExactly("a");
    }

    @Test
    void copyQueryValues_shouldReturnImmutableValueLists_whenCalled() {
        final Map<String, List<String>> copy = HttpUris.copyQueryValues(Map.of("q", List.of("a")));

        final var get = copy.get("q");

        final Throwable thrown = catchThrowable(() -> get.add("b"));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copyQueryValues_shouldThrow_whenQueryNull() {
        final Throwable thrown = catchThrowable(() -> HttpUris.copyQueryValues(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("query");
    }

    @ParameterizedTest
    @CsvSource({
        "https://user:pw@api.example.test/a/b?token=secret#frag, https://api.example.test/a/b",
        "http://localhost:8080/x, http://localhost:8080/x",
        "https://api.example.test, https://api.example.test",
        "https://api.example.test/a%20b?x=1, https://api.example.test/a%20b",
        "http://my_host.internal/x?token=secret, http://my_host.internal/x",
        "http://user:pw@my_host.internal:8080/x#frag, http://my_host.internal:8080/x"
    })
    void describeSafe_shouldDropUserInfoQueryAndFragment_whenUriIsAbsolute(final String uri, final String expected) {
        assertThat(HttpUris.describeSafe(URI.create(uri))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"/relative?token=secret", "mailto:someone@example.test"})
    void describeSafe_shouldHideEverything_whenUriHasNoHost(final String uri) {
        assertThat(HttpUris.describeSafe(URI.create(uri))).isEqualTo("<unsupported-uri>");
    }

    @Test
    void describeSafe_shouldThrow_whenUriNull() {
        final Throwable thrown = catchThrowable(() -> HttpUris.describeSafe(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:x", "ftp://h/x", "javascript:alert(1)"})
    void resolveApiPath_shouldThrowIllegalArgument_whenPathIsNotHttpReference(final String path) {
        final URI base = URI.create("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiPath(base, path, query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void resolve_shouldThrowIllegalArgument_whenRelativePathIsNotHttpReference() {
        final var uri = URI.create("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolve(uri, "ftp://h/x", query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void requireAbsolute_shouldReturnUri_whenAbsolute() {
        final URI uri = URI.create("https://api.example.test/repos");

        final URI result = HttpUris.requireAbsolute(uri);

        assertThat(result).isSameAs(uri);
    }

    @Test
    void requireAbsolute_shouldThrow_whenRelative() {
        final URI uri = URI.create("/repos?token=secret");

        final Throwable thrown = catchThrowable(() -> HttpUris.requireAbsolute(uri));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("uri must be absolute: " + HttpUris.describeSafe(uri));
    }

    @Test
    void requireAbsolute_shouldThrow_whenNull() {
        final URI uri = null;

        final Throwable thrown = catchThrowable(() -> HttpUris.requireAbsolute(uri));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("uri must not be null");
    }

    @Test
    void rawPathOrEmpty_shouldReturnRawPath_whenPresent() {
        final URI uri = URI.create("https://api.example.test/a%20b/c");

        final String path = HttpUris.rawPathOrEmpty(uri);

        assertThat(path).isEqualTo("/a%20b/c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:dev@example.com", "https://api.example.test"})
    void rawPathOrEmpty_shouldReturnEmpty_whenUriHasNoPath(final String value) {
        final URI uri = URI.create(value);

        final String path = HttpUris.rawPathOrEmpty(uri);

        assertThat(path).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " http://api.example.test/x",
                " https://other.example.test/x",
                " https://api.example.test:8443/x",
                " https://api.example.test/../x"
            })
    void resolveApiHttpUrl_shouldReject_whenLeadingSpaceHidesAnotherOrigin(final String path) {
        final HttpUrl base = HttpUrl.get("https://api.example.test/");

        final Map<String, List<String>> query = Map.of();

        final Throwable thrown = catchThrowable(() -> HttpUris.resolveApiHttpUrl(base, path, query));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative API path");
    }
}
