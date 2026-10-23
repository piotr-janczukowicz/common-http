package pl.piotrjanczukowicz.http.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LargeContentLengthServer implements AutoCloseable {

    private static final long ANNOUNCED_LENGTH = 10L * 1024 * 1024;

    private final HttpServer server;
    private final CountDownLatch released = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public LargeContentLengthServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, ANNOUNCED_LENGTH);
            try (exchange) {
                released.await();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        this.server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/large");
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
