package io.github.piotr_janczukowicz.test.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.testsupport.LoopbackHttpServer;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ScriptedHttpServer implements AutoCloseable {

    private final LoopbackHttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger disconnects = new AtomicInteger();
    private final AtomicReference<Throwable> scriptFailure = new AtomicReference<>();

    @FunctionalInterface
    public interface Script {

        void run(int call, HttpExchange exchange) throws Exception;
    }

    public ScriptedHttpServer(final Script script) throws IOException {
        this.server = LoopbackHttpServer.start("/", exchange -> {
            final int call = calls.incrementAndGet();
            try (exchange) {
                script.run(call, exchange);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final IOException clientGone) {
                disconnects.incrementAndGet();
            } catch (final Exception | AssertionError failure) {
                scriptFailure.compareAndSet(null, failure);
            }
        });
    }

    public URI uri() {
        return server.uri();
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
        server.close();
        final Throwable failure = scriptFailure.get();
        if (failure instanceof final AssertionError assertion) {
            throw assertion;
        }
        if (failure != null) {
            throw new IllegalStateException("A script of the server failed", failure);
        }
    }
}
