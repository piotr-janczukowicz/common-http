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
    void constructor_shouldReject_whenArgumentsAreInvalid() {
        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> HttpProxy.http(" ", 3128)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> HttpProxy.http(null, 3128)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> new HttpProxy(null, "h", 1, null, null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> new HttpProxy(HttpProxy.Type.HTTP, "h", 1, "user", null)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> new HttpProxy(HttpProxy.Type.HTTP, "h", 1, null, "secret")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> HttpProxy.http("h", 1).withCredentials(" ", "secret")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> HttpProxy.http("h", 1).withCredentials(null, "secret")))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> HttpProxy.http("h", 1).withCredentials("user", null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> HttpProxy.socks("h", 1).withCredentials("user", "secret")))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }
}
