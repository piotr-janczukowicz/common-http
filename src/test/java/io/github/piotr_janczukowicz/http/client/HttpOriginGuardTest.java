package io.github.piotr_janczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class HttpOriginGuardTest {

    private static final URI BASE = URI.create("https://api.example.test/v1/");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.example.test/v1/users",
                "https://API.example.test:443/v1/users?x=1",
                "https://api.example.test/v1/",
            })
    void requireApiTarget_shouldAcceptParsedUrl_whenOnOriginUnderBase(final String uri) {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final ParsedUrl url = ParsedUrl.of(URI.create(uri));

        assertThat(guard.requireApiTarget(url)).isSameAs(url);
        assertThat(guard.requireTarget(HttpMethod.GET, url)).isSameAs(url);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.example.test/admin",
                "https://api.example.test/v1",
                "https://api.example.test/v1evil/x",
                "https://api.example.test/v1/a/../../admin",
                "https://api.example.test/v1/%2e%2e/admin",
                "http://api.example.test/v1/users",
                "https://api.example.test:8443/v1/users",
                "https://other.test/v1/users",
            })
    void requireApiTarget_shouldRejectParsedUrl_whenOffOriginOrOutsideBase(final String uri) {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final ParsedUrl url = ParsedUrl.of(URI.create(uri));

        final Throwable thrown = catchThrowable(() -> guard.requireApiTarget(url));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireApiTarget_shouldNotLeakQueryOrUserInfo_whenParsedUrlIsRejected() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final ParsedUrl url = ParsedUrl.of(URI.create("https://user:pw@other.test/x?token=secret"));

        final Throwable thrown = catchThrowable(() -> guard.requireApiTarget(url));

        assertThat(thrown.getMessage()).doesNotContain("secret").doesNotContain("pw");
    }

    @Test
    void requireApiTarget_shouldThrowNullPointer_whenParsedUrlIsNull() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final ParsedUrl url = null;

        final Throwable thrown = catchThrowable(() -> guard.requireApiTarget(url));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("url");
    }

    @Test
    void requireTarget_shouldThrowNullPointer_whenParsedUrlIsNull() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final ParsedUrl url = null;

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, url));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("url");
    }

    @Test
    void requireApiTarget_shouldReject_whenIpv6HostIsAnotherAddress() {
        final HttpOriginGuard guard = new HttpOriginGuard(URI.create("http://[::1]:3000/api/"));
        final URI uri = URI.create("http://[::2]:3000/api/x");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, parsed));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }

    @Test
    void requireTarget_shouldReturnParsedUrl_whenUriAndParsedFormAreUnderBase() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create("https://api.example.test/v1/users?x=1");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        assertThat(guard.requireTarget(HttpMethod.GET, uri, parsed)).isSameAs(parsed);
    }

    @Test
    void requireTarget_shouldThrowViolation_whenParsedFormIsNull() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create("ftp://api.example.test/v1/x");

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, null));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "mailto:dev@example.com",
                "https://api.example.test/v1/a/../users",
                "https://api.example.test/admin"
            })
    void requireTarget_shouldThrowViolation_whenUriCannotBeServed(final String target) {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create(target);
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, parsed));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }

    @Test
    void requireTarget_shouldThrowNullPointer_whenMethodIsNull() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create("https://api.example.test/v1/users");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(null, uri, parsed));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("method");
    }

    @Test
    void requireTarget_shouldThrowNullPointer_whenUriIsNull() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @Test
    void requireTarget_shouldAcceptAnyMethod_whenUriIsUnderBase() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create("https://api.example.test/v1/users");

        assertThat(guard.requireTarget(HttpMethod.POST, uri, ParsedUrl.parse(uri))
                        .toUri())
                .isEqualTo(uri);
    }

    @Test
    void requireApiTarget_shouldNotLeakUserInfoOrQuery_whenRejected() {
        final HttpOriginGuard guard = new HttpOriginGuard(BASE);
        final URI uri = URI.create("https://alice:s3cret@api.example.test/admin?token=abc123#frag");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, parsed));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://api.example.test/admin")
                .hasMessageNotContainingAny("alice", "s3cret", "token", "abc123", "frag");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../x",
                "../../x",
                "%2e%2e/x",
                "%2E%2E/x",
                "v3/../../x",
                "//evil.test/x",
                "//alice:s3cret@evil.test/x",
                "//alice:s3cret@host.example.test:8443/svc/v1/x"
            })
    void requireApiTarget_shouldRejectResolvedPath_whenPathTargetEscapesApiBase(final String path) {
        final URI base = URI.create("https://host.example.test/svc/v1/");
        final HttpOriginGuard guard = new HttpOriginGuard(base);
        final URI resolved = base.resolve(path);
        final ParsedUrl parsed = ParsedUrl.parse(resolved);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, resolved, parsed));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API base URI")
                .hasMessageNotContaining("s3cret");
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.example.test/v1/, https://api.example.test/v1/users",
        "https://api.example.test/v1/, https://api.example.test/v1/users/..foo/.bar/a.b",
        "http://[0:0:0:0:0:0:0:1]:3000/api/, http://[::1]:3000/api/x",
        "http://[::1]:3000/api/, http://[::1]:3000/api/x",
        "https://host.example.test/svc/v1, https://host.example.test/svc/v1/user"
    })
    void requireTarget_shouldReturnUri_whenTargetIsUnderBase(final String base, final String target) {
        final HttpOriginGuard guard = new HttpOriginGuard(URI.create(base));
        final URI uri = URI.create(target);

        final URI allowed = allow(guard, uri);

        assertThat(allowed).isEqualTo(uri);
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.example.test/v1/, https://api.example.test/admin",
        "https://host.example.test/svc/v1, https://host.example.test/svc/v1evil/x",
        "https://api.example.test/v1/, https://api.example.test/v1/../admin",
        "https://api.example.test/v1/, https://api.example.test/v1/%2e%2e/admin",
        "https://api.example.test/v1/, https://api.example.test/v1/%2E%2e/admin",
        "https://api.example.test/v1/, https://api.example.test/v1/./users",
        "https://api.example.test/v1/, https://api.example.test/v1/users/.."
    })
    void requireTarget_shouldThrow_whenTargetEscapesBase(final String base, final String target) {
        final HttpOriginGuard guard = new HttpOriginGuard(URI.create(base));
        final URI uri = URI.create(target);
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    private static URI allow(final HttpOriginGuard guard, final URI uri) {
        return guard.requireTarget(HttpMethod.GET, uri, ParsedUrl.parse(uri)).toUri();
    }

    @Test
    void constructor_shouldThrowNullPointer_whenBaseHostIsNotAscii() {
        final URI base = URI.create("https://bücher.example/api/");

        final Throwable thrown = catchThrowable(() -> new HttpOriginGuard(base));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("host");
    }

    @Test
    void constructor_shouldThrow_whenBaseSchemeIsNotHttp() {
        final URI base = URI.create("ftp://api.example.test/");

        final Throwable thrown = catchThrowable(() -> new HttpOriginGuard(base));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http or https");
    }
}
