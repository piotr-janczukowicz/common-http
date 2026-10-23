package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static pl.piotrjanczukowicz.http.HttpMethod.GET;
import static pl.piotrjanczukowicz.http.HttpStatus.OK;
import static pl.piotrjanczukowicz.http.support.StubHttpServer.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.support.StubHttpServer;
import pl.piotrjanczukowicz.test.IntegrationTest;

@IntegrationTest
class OkHttpTransportNetworkIntegrationTest {

    private static final String STORE_PASSWORD = "changeit";
    private static final String ALIAS = "test";

    private static final HostResolver IPV4_ONLY_RESOLVER =
            host -> List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));

    @RegisterExtension
    static final StubHttpServer http = new StubHttpServer();

    @TempDir
    static Path tempDir;

    private static WireMockServer https;
    private static KeyStore keyStore;

    @BeforeAll
    static void startHttpsServer() throws Exception {
        final Path store = tempDir.resolve("server.p12");
        generateKeystore(store);
        keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keyStore.load(in, STORE_PASSWORD.toCharArray());
        }
        https = new WireMockServer(WireMockConfiguration.wireMockConfig()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .bindAddress("127.0.0.1")
                .keystorePath(store.toString())
                .keystoreType("PKCS12")
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD));
        https.start();
        https.stubFor(WireMock.get(WireMock.urlPathEqualTo("/ping"))
                .willReturn(WireMock.ok("pong").withHeader("Content-Type", "text/plain")));
    }

    @AfterAll
    static void stopHttpsServer() {
        if (https != null) {
            https.stop();
        }
    }

    @Test
    void execute_shouldUseHostResolver_whenConfigured() throws Exception {
        http.stub(GET, "/ping", status(OK).body("pong".getBytes(StandardCharsets.UTF_8)));
        final List<String> asked = new CopyOnWriteArrayList<>();
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .hostResolver(host -> {
                    asked.add(host);
                    return List.of(InetAddress.getLoopbackAddress());
                })
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final HttpResponse response = transport.execute(
                    request("http://fixed-address.invalid:" + http.baseUri().getPort() + "/ping"));

            assertThat(response.statusCode()).isEqualTo(OK.code());
        }

        assertThat(asked).containsExactly("fixed-address.invalid");
        http.verifyRequested(GET, "/ping");
    }

    @Test
    void execute_shouldFailWithUnknownHost_whenHostResolverReturnsNull() throws Exception {
        final HttpTransportConfiguration configuration = configuration(
                HttpNetworkConfiguration.builder().hostResolver(host -> null).build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final var request = request("http://null-answer.invalid/ping");

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown)
                    .isInstanceOf(UnknownHostException.class)
                    .hasMessageContaining("returned no addresses for null-answer.invalid");
        }
    }

    @Test
    void execute_shouldFailWithUnknownHost_whenHostResolverReturnsNoAddresses() throws Exception {
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .hostResolver(host -> List.of())
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final var request = request("http://empty-answer.invalid/ping");

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(UnknownHostException.class).hasMessageContaining("empty-answer.invalid");
        }
    }

    @Test
    void execute_shouldTryNextAddress_whenPostCannotConnectToFirstAddress() throws Exception {
        http.stub(HttpMethod.POST, "/ping", status(OK));
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .hostResolver(host -> List.of(
                        InetAddress.getByAddress(new byte[] {127, 0, 0, 2}),
                        InetAddress.getByAddress(new byte[] {127, 0, 0, 1})))
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final HttpResponse response = transport.execute(new HttpRequest(
                    HttpMethod.POST,
                    URI.create("http://failover.invalid:" + http.baseUri().getPort() + "/ping"),
                    HttpHeaders.empty(),
                    HttpBody.of(new byte[] {1})));

            assertThat(response.statusCode()).isEqualTo(OK.code());
        }

        http.verifyRequested(HttpMethod.POST, "/ping", 1);
    }

    @Test
    void execute_shouldFail_whenHostResolverCannotResolve() {
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .hostResolver(host -> {
                    throw new UnknownHostException(host);
                })
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final var request = request("http://nowhere.invalid:80/ping");

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void execute_shouldGoThroughHttpProxy_whenConfigured() throws Exception {
        http.stub(GET, "/ping", status(OK).body("pong".getBytes(StandardCharsets.UTF_8)));
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .proxy(HttpProxy.http("127.0.0.1", http.baseUri().getPort()))
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final HttpResponse response = transport.execute(request("http://upstream.invalid/ping"));

            assertThat(response.statusCode()).isEqualTo(OK.code());
        }

        http.verifyRequested(GET, "/ping");
    }

    @Test
    void execute_shouldAnswerProxyChallengeWithCredentials_whenProxyRequiresThem() throws Exception {
        http.stub(
                GET,
                "/ping",
                status(407).header("Proxy-Authenticate", "Basic realm=\"proxy\""),
                status(OK).body("pong".getBytes(StandardCharsets.UTF_8)));
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .proxy(HttpProxy.http("127.0.0.1", http.baseUri().getPort()).withCredentials("octo", "s3cret"))
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final HttpResponse response = transport.execute(request("http://upstream.invalid/ping"));

            assertThat(response.statusCode()).isEqualTo(OK.code());
        }

        http.verifyRequested(GET, "/ping", 2);
        http.verifyHeader(
                GET,
                "/ping",
                "Proxy-Authorization",
                "Basic " + Base64.getEncoder().encodeToString("octo:s3cret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void execute_shouldReturnProxyAuthRequired_whenCredentialsAreRejected() throws Exception {
        http.stub(GET, "/ping", status(407).header("Proxy-Authenticate", "Basic realm=\"proxy\""));
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .proxy(HttpProxy.http("127.0.0.1", http.baseUri().getPort()).withCredentials("octo", "wrong"))
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final HttpResponse response = transport.execute(request("http://upstream.invalid/ping"));

            assertThat(response.statusCode()).isEqualTo(407);
        }

        http.verifyRequested(GET, "/ping", 2);
    }

    @Test
    void execute_shouldFailToConnect_whenSocksProxyIsUnreachable() throws Exception {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        final HttpTransportConfiguration configuration = configuration(HttpNetworkConfiguration.builder()
                .proxy(HttpProxy.socks("127.0.0.1", closedPort))
                .build());

        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            final var request = request("http://upstream.invalid/ping");

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IOException.class);
        }
    }

    @Test
    void execute_shouldPropagateUncheckedException_whenHostResolverThrowsIt() {
        final HostResolver brokenResolver = host -> {
            throw new IllegalStateException("resolver is broken");
        };
        try (HttpTransport transport = HttpTransports.okHttp(configuration(
                HttpNetworkConfiguration.builder().hostResolver(brokenResolver).build()))) {
            final var request = request("http://upstream.invalid/ping");

            final Throwable thrown = catchThrowable(() -> transport.execute(request));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("resolver is broken");
        }
    }

    @Test
    void execute_shouldFailHandshake_whenServerCertificateIsNotTrusted() {
        try (HttpTransport transport = HttpTransports.okHttp(configuration(HttpNetworkConfiguration.builder()
                .hostResolver(IPV4_ONLY_RESOLVER)
                .build()))) {
            final var httpsRequest = httpsRequest();

            final Throwable thrown = catchThrowable(() -> transport.execute(httpsRequest));

            assertThat(thrown).isInstanceOf(SSLException.class);
        }
    }

    @Test
    void execute_shouldSucceed_whenCustomTrustAcceptsServerCertificate() throws Exception {
        try (HttpTransport transport =
                HttpTransports.okHttp(configuration(trusting().build()))) {
            final HttpResponse response = transport.execute(httpsRequest());

            assertThat(response.statusCode()).isEqualTo(OK.code());
        }
    }

    @Test
    void execute_shouldSucceed_whenPinMatchesServerKey() throws Exception {
        final HttpNetworkConfiguration network =
                trusting().certificatePin("localhost", serverPin(), backupPin()).build();

        try (HttpTransport transport = HttpTransports.okHttp(configuration(network))) {
            assertThat(transport.execute(httpsRequest()).statusCode()).isEqualTo(OK.code());
        }
    }

    @Test
    void execute_shouldFail_whenPinDoesNotMatchServerKey() throws Exception {
        final HttpNetworkConfiguration network =
                trusting().certificatePin("localhost", backupPin()).build();

        try (HttpTransport transport = HttpTransports.okHttp(configuration(network))) {
            final var httpsRequest = httpsRequest();

            final Throwable thrown = catchThrowable(() -> transport.execute(httpsRequest));

            assertThat(thrown).isInstanceOf(SSLException.class).hasMessageContaining("pinning failure");
        }
    }

    @Test
    void wrap_shouldApplyNetworkSettings_whenConfigurationSetsThem() throws Exception {
        try (HttpTransport transport =
                HttpTransports.okHttp(configuration(trusting().build()))) {
            assertThat(transport.execute(httpsRequest()).statusCode()).isEqualTo(OK.code());
        }
    }

    private static HttpNetworkConfiguration.Builder trusting() throws Exception {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        final X509TrustManager trustManager = (X509TrustManager) factory.getTrustManagers()[0];
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] {trustManager}, null);
        return HttpNetworkConfiguration.builder()
                .tls(HttpTls.of(context, trustManager))
                .hostResolver(IPV4_ONLY_RESOLVER);
    }

    private static String serverPin() throws Exception {
        final Certificate certificate = keyStore.getCertificate(ALIAS);
        final byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getPublicKey().getEncoded());
        return "sha256/" + Base64.getEncoder().encodeToString(hash);
    }

    private static String backupPin() {
        return "sha256/" + Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static HttpTransportConfiguration configuration(final HttpNetworkConfiguration network) {
        return HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .network(network)
                .build();
    }

    private static HttpRequest request(final String uri) {
        return new HttpRequest(GET, URI.create(uri), HttpHeaders.empty(), null);
    }

    private static HttpRequest httpsRequest() {
        return request("https://localhost:" + https.httpsPort() + "/ping");
    }

    private static void generateKeystore(final Path store) throws Exception {
        final String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        final Process process = new ProcessBuilder(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        ALIAS,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        STORE_PASSWORD,
                        "-keypass",
                        STORE_PASSWORD)
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(Files.exists(store)).isTrue();
    }
}
