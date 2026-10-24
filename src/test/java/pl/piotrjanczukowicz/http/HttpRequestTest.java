package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class HttpRequestTest {

    private static final URI API_URI = URI.create("https://api.example.test/user");

    @Test
    void constructor_shouldCopyHeaders_whenHeadersMutated() {
        final Map<String, String> headers = new HashMap<>(Map.of("X-Test", "first"));

        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, headers, null);
        headers.put("Other", "added");

        assertThat(request.headers().asMap().keySet()).containsExactly("X-Test");
    }

    @Test
    void constructor_shouldUseEmptyHeaders_whenHeadersNull() {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, (HttpHeaders) null, null);

        assertThat(request.headers().isEmpty()).isTrue();
    }

    @Test
    void constructor_shouldNormalizeNullBody_whenBodyNull() {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);

        assertSoftly(softly -> {
            softly.assertThat(request.body()).isEqualTo(HttpBody.absent());
            softly.assertThat(request.body().isAbsent()).isTrue();
        });
    }

    @Test
    void constructor_shouldThrow_whenMethodIsNull() {
        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> new HttpRequest(null, API_URI, headers, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("method");
    }

    @Test
    void constructor_shouldThrow_whenUriIsNull() {
        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> new HttpRequest(HttpMethod.GET, (URI) null, headers, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @Test
    void mapBody_shouldInvokePresent_whenBodyPresent() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {7}));
        final AtomicBoolean absent = new AtomicBoolean();

        final int length = request.mapBody(bytes -> bytes.length, () -> {
            absent.set(true);
            return -1;
        });

        assertSoftly(softly -> {
            softly.assertThat(length).isEqualTo(1);
            softly.assertThat(absent).isFalse();
        });
    }

    @Test
    void mapBody_shouldInvokeAbsent_whenBodyAbsent() {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);
        final AtomicBoolean absent = new AtomicBoolean();

        final int value = request.mapBody(bytes -> bytes.length, () -> {
            absent.set(true);
            return 0;
        });

        assertSoftly(softly -> {
            softly.assertThat(value).isZero();
            softly.assertThat(absent).isTrue();
        });
    }

    @Test
    void constructor_shouldHaveNoCredentialId_whenFourArgumentConstructorIsUsed() {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);

        assertThat(request.credentialId()).isNull();
    }

    @Test
    void constructor_shouldKeepCredentialId_whenGiven() {
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET, ParsedUrl.of(API_URI), HttpHeaders.empty(), null, "app:1/installation:42");

        assertThat(request.credentialId()).isEqualTo("app:1/installation:42");
    }

    @Test
    void constructor_shouldThrow_whenCredentialIdIsBlank() {
        final var url = ParsedUrl.of(API_URI);
        final var headers = HttpHeaders.empty();

        final Throwable thrown = catchThrowable(() -> new HttpRequest(HttpMethod.GET, url, headers, null, " "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credentialId");
    }

    @Test
    void toString_shouldRedactAuthorization_whenPrinted() {
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET, API_URI, HttpHeaders.of(AUTHORIZATION, "Bearer super-secret-token"), null);

        assertThat(request.toString()).doesNotContain("super-secret-token");
    }

    @Test
    void effectiveBody_shouldBeEmptyEntity_whenPostWithoutBody() {
        final HttpRequest request = new HttpRequest(HttpMethod.POST, API_URI, HttpHeaders.empty(), null);

        assertThat(request.effectiveBody()).isEqualTo(HttpBody.zeroLength());
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"GET", "HEAD"})
    void constructor_shouldThrow_whenGetOrHeadHasBody(final HttpMethod method) {
        final var headers = HttpHeaders.empty();
        final var body = HttpBody.of(new byte[] {1});

        final Throwable thrown = catchThrowable(() -> new HttpRequest(method, API_URI, headers, body));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(method + " request must not have a body");
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void constructor_shouldAcceptBody_whenMethodAllowsOne(final HttpMethod method) {
        final HttpRequest request = new HttpRequest(method, API_URI, HttpHeaders.empty(), HttpBody.of(new byte[] {1}));

        assertThat(request.body().isAbsent()).isFalse();
    }

    @Test
    void encodedPath_shouldReturnPathWithoutQuery_whenUriHasQueryAndEncodedSegments() {
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET,
                URI.create("https://api.example.test/repos/o%20x/r?per_page=5"),
                HttpHeaders.empty(),
                null);

        assertThat(request.encodedPath()).isEqualTo("/repos/o%20x/r");
    }

    @Test
    void credentialKey_shouldUseIdentity_whenCredentialIdPresent() {
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET,
                ParsedUrl.of(API_URI),
                HttpHeaders.of(AUTHORIZATION, "Bearer abc"),
                null,
                "app:1/installation:2");

        final String key = request.credentialKey();

        assertThat(key).isEqualTo("id:app:1/installation:2");
    }

    @Test
    void credentialKey_shouldKeyDigestAuthorization_whenNoCredentialId() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.of(AUTHORIZATION, "abc"), null);

        final String key = request.credentialKey();

        assertThat(key).isEqualTo("auth:" + Digests.keyedSha256Hex("abc"));
    }

    @Test
    void credentialKey_shouldNotContainToken_whenAuthorizationDigested() {
        final HttpRequest request =
                new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.of(AUTHORIZATION, "Bearer tok_secret"), null);

        final String key = request.credentialKey();

        assertThat(key).doesNotContain("tok_secret").doesNotContain("Bearer");
    }

    @Test
    void credentialKey_shouldReturnAnonymous_whenNoCredentialIdOrAuthorization() {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, API_URI, HttpHeaders.empty(), null);

        final String key = request.credentialKey();

        assertThat(key).isEqualTo("anonymous");
    }
}
