package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import io.github.piotr_janczukowicz.testsupport.LoopbackHttpServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OkHttpTransportConnectionPoolIntegrationTest {

    private static final int THREADS = 16;
    private static final int REQUESTS_PER_THREAD = 50;
    private static final int WAIT_SECONDS = 60;

    private final Set<Integer> remotePorts = ConcurrentHashMap.newKeySet();
    private final CyclicBarrier round = new CyclicBarrier(THREADS);
    private LoopbackHttpServer server;
    private HttpRequest request;

    @BeforeEach
    void startServer() throws IOException {
        server = LoopbackHttpServer.start("/ping", exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            awaitOtherCallers();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        request = new HttpRequest(HttpMethod.GET, server.uri(), HttpHeaders.empty(), null);
    }

    @AfterEach
    void stopServer() {
        server.close();
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
    void create_shouldOpenMoreConnectionsThanCallers_whenPoolKeepsOnlyOneIdleConnection() throws Exception {
        final HttpTransportConfiguration tight =
                HttpTransportConfiguration.builder().maxIdleConnections(1).build();

        final int tightConnections;
        try (HttpTransport transport = HttpTransports.okHttp(tight)) {
            tightConnections = hammer(transport);
        }

        assertThat(tightConnections).isGreaterThan(THREADS);
    }

    // Holds every request until all callers of the same round are in flight, so each round needs THREADS connections.
    private void awaitOtherCallers() throws IOException {
        try {
            round.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the other callers", e);
        } catch (final BrokenBarrierException | TimeoutException e) {
            throw new IOException("the other callers did not arrive", e);
        }
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
