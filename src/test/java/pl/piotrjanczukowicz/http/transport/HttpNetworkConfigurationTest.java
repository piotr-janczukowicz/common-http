package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpNetworkConfigurationTest {

    private static final String PIN_A = pin((byte) 1);
    private static final String PIN_B = pin((byte) 2);

    private static String pin(final byte fill) {
        final byte[] hash = new byte[32];
        Arrays.fill(hash, fill);
        return "sha256/" + Base64.getEncoder().encodeToString(hash);
    }

    @Test
    void none_shouldHaveNothingSet() {
        final HttpNetworkConfiguration none = HttpNetworkConfiguration.none();

        assertSoftly(softly -> {
            softly.assertThat(none.proxy()).isNull();
            softly.assertThat(none.tls()).isNull();
            softly.assertThat(none.certificatePins()).isEmpty();
            softly.assertThat(none.hostResolver()).isNull();
            softly.assertThat(none.toString()).contains("tls=default").contains("hostResolver=system");
        });
    }

    @Test
    void builder_shouldCollectAllSettings() throws Exception {
        final HostResolver resolver = host -> List.of(InetAddress.getLoopbackAddress());
        final SSLContext context = SSLContext.getDefault();
        final X509TrustManager trust = new TrustNothing();

        final HttpNetworkConfiguration config = HttpNetworkConfiguration.builder()
                .proxy(HttpProxy.http("proxy.internal", 3128))
                .tls(HttpTls.of(context, trust))
                .certificatePin("api.example.test", PIN_A)
                .certificatePin("api.example.test", PIN_B)
                .certificatePin("*.example.test", PIN_A)
                .hostResolver(resolver)
                .build();

        assertSoftly(softly -> {
            softly.assertThat(config.proxy()).isEqualTo(HttpProxy.http("proxy.internal", 3128));
            softly.assertThat(config.tls().trustManager()).isSameAs(trust);
            softly.assertThat(config.certificatePins())
                    .containsEntry("api.example.test", Set.of(PIN_A, PIN_B))
                    .containsEntry("*.example.test", Set.of(PIN_A));
            softly.assertThat(config.hostResolver()).isSameAs(resolver);
            softly.assertThat(config.toString())
                    .contains("tls=custom")
                    .contains("hostResolver=custom")
                    .contains("api.example.test")
                    .doesNotContain(PIN_A);
        });
    }

    @Test
    void certificatePins_shouldBeUnmodifiable() {
        final HttpNetworkConfiguration config = HttpNetworkConfiguration.builder()
                .certificatePin("a.example", PIN_A)
                .build();

        final Throwable thrown = catchThrowable(() -> config.certificatePins().put("b.example", Set.of(PIN_B)));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAA", "sha256/not base64!", "sha256/AAAA"})
    void certificatePin_shouldReject_whenPinsAreInvalid(final String invalid) {
        final HttpNetworkConfiguration.Builder builder =
                HttpNetworkConfiguration.builder().certificatePin("a.example", invalid);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void certificatePin_shouldReject_whenArgumentsAreInvalid() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.certificatePin(" ", PIN_A)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.certificatePin("a.example")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.certificatePin("a.example", (String[]) null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.certificatePin("a.example", (String) null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void constructor_shouldReject_whenPinMapsAreInvalid() {
        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(
                            () -> new HttpNetworkConfiguration(null, null, Map.of(" ", Set.of(PIN_A)), null)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(
                            catchThrowable(() -> new HttpNetworkConfiguration(null, null, Map.of("a", Set.of()), null)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> new HttpNetworkConfiguration(null, null, null, null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void builder_shouldReject_whenSettingsAreNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.proxy(null))).isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.tls(null))).isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.hostResolver(null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void httpTls_shouldReject_whenArgumentsAreNull() throws Exception {
        final SSLContext context = SSLContext.getDefault();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> HttpTls.of(null, new TrustNothing())))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> HttpTls.of(context, null))).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void transportConfiguration_shouldDefaultToNoNetworkSettings_whenNetworkIsUnsetOrNull() {
        assertSoftly(softly -> {
            softly.assertThat(HttpTransportConfiguration.defaults().network())
                    .isEqualTo(HttpNetworkConfiguration.none());
            softly.assertThat(HttpTransportConfiguration.builder()
                            .network(HttpNetworkConfiguration.builder()
                                    .certificatePin("a.example", PIN_A)
                                    .build())
                            .build()
                            .network()
                            .certificatePins())
                    .containsKey("a.example");
            softly.assertThat(catchThrowable(() ->
                            HttpTransportConfiguration.builder().network(null).build()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    private static final class TrustNothing implements X509TrustManager {
        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
