package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpProxyTest {

    @Test
    void factories_shouldSetTypeHostAndPort() {
        assertSoftly(softly -> {
            softly.assertThat(HttpProxy.http("proxy.internal", 3128).type()).isEqualTo(HttpProxy.Type.HTTP);
            softly.assertThat(HttpProxy.socks("proxy.internal", 1080).type()).isEqualTo(HttpProxy.Type.SOCKS);
            softly.assertThat(HttpProxy.http("proxy.internal", 3128).hasCredentials())
                    .isFalse();
        });
    }

    @Test
    void withCredentials_shouldKeepProxyAndHideSecretInToString() {
        final HttpProxy proxy = HttpProxy.http("proxy.internal", 3128).withCredentials("octo", "s3cret");

        assertSoftly(softly -> {
            softly.assertThat(proxy.hasCredentials()).isTrue();
            softly.assertThat(proxy.username()).isEqualTo("octo");
            softly.assertThat(proxy.toString())
                    .contains("proxy.internal:3128")
                    .contains("credentials=***")
                    .doesNotContain("s3cret")
                    .doesNotContain("octo");
            softly.assertThat(HttpProxy.http("proxy.internal", 3128).toString()).doesNotContain("credentials");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65_536})
    void constructor_shouldReject_whenPortIsOutOfRange(final int port) {
        final Throwable thrown = catchThrowable(() -> HttpProxy.http("proxy.internal", port));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void http_shouldReject_whenHostIsBlank() {
        final String host = " ";

        final Throwable thrown = catchThrowable(() -> HttpProxy.http(host, 3128));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void http_shouldReject_whenHostIsNull() {
        final String host = null;

        final Throwable thrown = catchThrowable(() -> HttpProxy.http(host, 3128));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldReject_whenTypeIsNull() {
        final HttpProxy.Type type = null;

        final Throwable thrown = catchThrowable(() -> new HttpProxy(type, "h", 1, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldReject_whenUsernameIsSetWithoutPassword() {
        final String username = "user";

        final Throwable thrown = catchThrowable(() -> new HttpProxy(HttpProxy.Type.HTTP, "h", 1, username, null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldReject_whenPasswordIsSetWithoutUsername() {
        final String password = "secret";

        final Throwable thrown = catchThrowable(() -> new HttpProxy(HttpProxy.Type.HTTP, "h", 1, null, password));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withCredentials_shouldReject_whenUsernameIsBlank() {
        final HttpProxy proxy = HttpProxy.http("h", 1);
        final String username = " ";

        final Throwable thrown = catchThrowable(() -> proxy.withCredentials(username, "secret"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withCredentials_shouldReject_whenUsernameIsNull() {
        final HttpProxy proxy = HttpProxy.http("h", 1);
        final String username = null;

        final Throwable thrown = catchThrowable(() -> proxy.withCredentials(username, "secret"));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withCredentials_shouldReject_whenPasswordIsNull() {
        final HttpProxy proxy = HttpProxy.http("h", 1);
        final String password = null;

        final Throwable thrown = catchThrowable(() -> proxy.withCredentials("user", password));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withCredentials_shouldReject_whenProxyIsSocks() {
        final HttpProxy proxy = HttpProxy.socks("h", 1);

        final Throwable thrown = catchThrowable(() -> proxy.withCredentials("user", "secret"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }
}
