package pl.piotrjanczukowicz.http.support;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StallAfterHeadersServer implements AutoCloseable {

    private final HttpServer server;
    private final Duration stall;
    private final CountDownLatch released = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public StallAfterHeadersServer(final Duration stall) throws IOException {
        this.stall = requireNonNull(stall, "stall");
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (exchange) {
                released.await(this.stall.toMillis(), TimeUnit.MILLISECONDS);
                try (final OutputStream body = exchange.getResponseBody()) {
                    body.write(new byte[] {1, 2});
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        this.server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall");
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
