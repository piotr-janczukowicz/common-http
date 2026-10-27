package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.InetAddress;
import java.security.cert.CertificateException;
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

        final Map<String, Set<String>> certificatePins = config.certificatePins();

        final Set<String> pins = Set.of(PIN_B);

        final Throwable thrown = catchThrowable(() -> certificatePins.put("b.example", pins));

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
    void certificatePin_shouldReject_whenHostIsBlank() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final String host = " ";

        final Throwable thrown = catchThrowable(() -> builder.certificatePin(host, PIN_A));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void certificatePin_shouldReject_whenPinsAreMissing() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.certificatePin("a.example"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void certificatePin_shouldReject_whenPinArrayIsNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final String[] pins = null;

        final Throwable thrown = catchThrowable(() -> builder.certificatePin("a.example", pins));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void certificatePin_shouldReject_whenPinIsNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final String pin = null;

        final Throwable thrown = catchThrowable(() -> builder.certificatePin("a.example", pin));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldReject_whenPinHostIsBlank() {
        final Map<String, Set<String>> pins = Map.of(" ", Set.of(PIN_A));

        final Throwable thrown = catchThrowable(() -> new HttpNetworkConfiguration(null, null, pins, null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldReject_whenPinSetIsEmpty() {
        final Map<String, Set<String>> pins = Map.of("a", Set.of());

        final Throwable thrown = catchThrowable(() -> new HttpNetworkConfiguration(null, null, pins, null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldReject_whenPinsAreNull() {
        final Map<String, Set<String>> pins = null;

        final Throwable thrown = catchThrowable(() -> new HttpNetworkConfiguration(null, null, pins, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_shouldReject_whenProxyIsNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final HttpProxy proxy = null;

        final Throwable thrown = catchThrowable(() -> builder.proxy(proxy));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_shouldReject_whenTlsIsNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final HttpTls tls = null;

        final Throwable thrown = catchThrowable(() -> builder.tls(tls));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_shouldReject_whenHostResolverIsNull() {
        final HttpNetworkConfiguration.Builder builder = HttpNetworkConfiguration.builder();
        final HostResolver resolver = null;

        final Throwable thrown = catchThrowable(() -> builder.hostResolver(resolver));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void httpTls_shouldReject_whenContextIsNull() {
        final SSLContext context = null;
        final X509TrustManager trust = new TrustNothing();

        final Throwable thrown = catchThrowable(() -> HttpTls.of(context, trust));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void httpTls_shouldReject_whenTrustManagerIsNull() throws Exception {
        final SSLContext context = SSLContext.getDefault();
        final X509TrustManager trust = null;

        final Throwable thrown = catchThrowable(() -> HttpTls.of(context, trust));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void transportConfiguration_shouldDefaultToNoNetworkSettings_whenNetworkIsUnset() {
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
        });
    }

    @Test
    void transportConfiguration_shouldReject_whenNetworkIsNull() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().network(null);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    private static final class TrustNothing implements X509TrustManager {
        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            throw new CertificateException("trusts nothing");
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            throw new CertificateException("trusts nothing");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
