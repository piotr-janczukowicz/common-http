package pl.piotrjanczukowicz.http.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;

public final class ScriptedHttpServer implements AutoCloseable {

    @FunctionalInterface
    public interface Script {

        void run(int call, HttpExchange exchange) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger disconnects = new AtomicInteger();
    private final AtomicReference<Throwable> scriptFailure = new AtomicReference<>();

    public ScriptedHttpServer(final Script script) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", exchange -> {
            final int call = calls.incrementAndGet();
            try {
                script.run(call, exchange);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final IOException clientGone) {
                disconnects.incrementAndGet();
            } catch (final Exception | AssertionError failure) {
                scriptFailure.compareAndSet(null, failure);
            } finally {
                exchange.close();
            }
        });
        this.server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public HttpRequest request() {
        return new HttpRequest(HttpMethod.GET, uri(), HttpHeaders.empty(), null);
    }

    public int calls() {
        return calls.get();
    }

    public int disconnects() {
        return disconnects.get();
    }

    /**
     * Stops the server, then rethrows the first failure of a script, such as an {@link AssertionError}, so that it
     * fails the test instead of being lost on the server thread. An {@link IOException} is not a failure: the client
     * may close a scripted stream on purpose.
     */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        final Throwable failure = scriptFailure.get();
        if (failure instanceof final AssertionError assertion) {
            throw assertion;
        }
        if (failure != null) {
            throw new IllegalStateException("A script of the server failed", failure);
        }
    }
}
