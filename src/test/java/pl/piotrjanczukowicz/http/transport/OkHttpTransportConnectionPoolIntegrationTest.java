package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.test.IntegrationTest;

@IntegrationTest
class OkHttpTransportConnectionPoolIntegrationTest {

    private static final int THREADS = 16;
    private static final int REQUESTS_PER_THREAD = 50;
    private static final int WAIT_SECONDS = 60;

    private final Set<Integer> remotePorts = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpRequest request;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newFixedThreadPool(THREADS * 2);
        server.setExecutor(serverExecutor);
        server.createContext("/ping", exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        request = new HttpRequest(
                HttpMethod.GET,
                URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                        + server.getAddress().getPort() + "/ping"),
                HttpHeaders.empty(),
                null);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void create_shouldOpenAtMostMaxIdleConnections_whenThreadsMatchTheLimit() throws Exception {
        final HttpTransportConfiguration configuration =
                HttpTransportConfiguration.builder().maxIdleConnections(THREADS).build();

        final int connections;
        try (HttpTransport transport = HttpTransports.okHttp(configuration)) {
            connections = hammer(transport);
        }

        assertThat(connections).isLessThanOrEqualTo(THREADS);
    }

    @Test
    void create_shouldOpenMoreConnections_whenPoolKeepsOnlyOneIdleConnection() throws Exception {
        final HttpTransportConfiguration roomy =
                HttpTransportConfiguration.builder().maxIdleConnections(THREADS).build();
        final HttpTransportConfiguration tight =
                HttpTransportConfiguration.builder().maxIdleConnections(1).build();

        final int roomyConnections;
        try (HttpTransport transport = HttpTransports.okHttp(roomy)) {
            roomyConnections = hammer(transport);
        }
        remotePorts.clear();
        final int tightConnections;
        try (HttpTransport transport = HttpTransports.okHttp(tight)) {
            tightConnections = hammer(transport);
        }

        assertThat(tightConnections).isGreaterThan(roomyConnections * 2);
    }

    private int hammer(final HttpTransport transport) throws Exception {
        final ExecutorService callers = Executors.newFixedThreadPool(THREADS);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                tasks.add(callers.submit(() -> {
                    start.await();
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        transport.execute(request);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> task : tasks) {
                task.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }
        return remotePorts.size();
    }
}
