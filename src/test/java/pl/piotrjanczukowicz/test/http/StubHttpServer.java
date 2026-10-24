package pl.piotrjanczukowicz.test.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LOCATION;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.RequestListener;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpStatus;

@NullUnmarked
public final class StubHttpServer implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback {

    private final String bindAddress;
    private final WireMockServer server;
    private final AtomicInteger sequences = new AtomicInteger();

    public StubHttpServer() {
        this("127.0.0.1");
    }

    public StubHttpServer(final String bindAddress) {
        this.bindAddress = requireNonNull(bindAddress, "bindAddress");
        this.server = new WireMockServer(wireMockConfig().dynamicPort().bindAddress(bindAddress));
    }

    public URI baseUri() {
        ensureStarted();
        return URI.create("http://" + bindAddress + ":" + server.port());
    }

    public URI uri(final String path) {
        return URI.create(baseUri() + normalize(path));
    }

    public static StubResponse status(final int status) {
        return new StubResponse().status(status);
    }

    public static StubResponse status(final HttpStatus status) {
        requireNonNull(status, "status");
        return status(status.code());
    }

    public static StubResponse connectionReset() {
        return new StubResponse().connectionReset();
    }

    public void stub(final HttpMethod method, final String path, final int status) {
        stub(method, path, status(status));
    }

    public void stub(final HttpMethod method, final String path, final StubResponse... responses) {
        requireNonNull(method, "method");
        requireNonNull(responses, "responses");

        if (responses.length == 0) {
            throw new IllegalArgumentException("responses must not be empty");
        }
        ensureStarted();

        if (responses.length == 1) {
            server.stubFor(requestFor(method, path).willReturn(responses[0].toResponse()));
            return;
        }

        final String scenario = "sequence-" + sequences.incrementAndGet();
        String fromState = Scenario.STARTED;
        for (int i = 0; i < responses.length; i++) {
            final String toState = String.valueOf(i + 1);
            server.stubFor(requestFor(method, path)
                    .inScenario(scenario)
                    .whenScenarioStateIs(fromState)
                    .willSetStateTo(toState)
                    .willReturn(responses[i].toResponse()));
            fromState = toState;
        }
    }

    public void stubRedirect(final String path, final String location, final int status) {
        requireNonNull(location, "location");
        stub(HttpMethod.GET, path, status(status).header(LOCATION, location));
    }

    public void verifyRequested(final HttpMethod method, final String path) {
        verifyRequested(method, path, 1);
    }

    public void verifyRequested(final HttpMethod method, final String path, final int times) {
        requireNonNull(method, "method");
        ensureStarted();
        server.verify(times, pattern(method, path));
    }

    public void awaitRequested(final HttpMethod method, final String path, final Duration timeout)
            throws InterruptedException {
        requireNonNull(method, "method");
        requireNonNull(timeout, "timeout");
        ensureStarted();
        final RequestPattern requestPattern = pattern(method, path).build();
        final CountDownLatch requested = new CountDownLatch(1);
        final RequestListener listener = (request, response) -> {
            if (requestPattern.match(request).isExactMatch()) {
                requested.countDown();
            }
        };
        // WireMock cannot remove a listener; this one only counts a latch down, so it stays harmless afterwards.
        server.addMockServiceRequestListener(listener);
        if (server.countRequestsMatching(requestPattern).getCount() == 0
                && !requested.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new IllegalStateException("no " + method + " " + path + " request within " + timeout);
        }
    }

    public void verifyHeader(final HttpMethod method, final String path, final String name, final String value) {
        requireNonNull(method, "method");
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureStarted();
        server.verify(pattern(method, path).withHeader(name, equalTo(value)));
    }

    public void verifyNoHeader(final HttpMethod method, final String path, final String name) {
        requireNonNull(method, "method");
        requireNonNull(name, "name");
        ensureStarted();
        server.verify(pattern(method, path).withHeader(name, absent()));
    }

    public void verifyQueryParam(final HttpMethod method, final String path, final String name, final String value) {
        requireNonNull(method, "method");
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureStarted();
        server.verify(pattern(method, path).withQueryParam(name, equalTo(value)));
    }

    public void verifyNoQueryParam(final HttpMethod method, final String path, final String name) {
        requireNonNull(method, "method");
        requireNonNull(name, "name");
        ensureStarted();
        server.verify(pattern(method, path).withQueryParam(name, absent()));
    }

    public void verifyAbsentBody(final HttpMethod method, final String path) {
        requireNonNull(method, "method");
        ensureStarted();
        server.verify(pattern(method, path).withRequestBody(absent()));
    }

    public void verifyBody(final HttpMethod method, final String path, final String body) {
        requireNonNull(method, "method");
        requireNonNull(body, "body");
        ensureStarted();
        server.verify(pattern(method, path).withRequestBody(equalTo(body)));
    }

    public void verifyBody(final HttpMethod method, final String path, final byte[] body) {
        requireNonNull(method, "method");
        requireNonNull(body, "body");
        ensureStarted();
        server.verify(pattern(method, path).withRequestBody(binaryEqualTo(body)));
    }

    @Override
    public void beforeAll(final @NonNull ExtensionContext context) {
        ensureStarted();
    }

    @Override
    public void beforeEach(final @NonNull ExtensionContext context) {
        ensureStarted();
        sequences.set(0);
        server.resetAll();
    }

    @Override
    public void afterAll(final @NonNull ExtensionContext context) {
        server.stop();
    }

    private void ensureStarted() {
        if (!server.isRunning()) {
            server.start();
        }
    }

    private static MappingBuilder requestFor(final HttpMethod method, final String path) {
        return request(method.name(), urlPathEqualTo(normalize(path)));
    }

    private static RequestPatternBuilder pattern(final HttpMethod method, final String path) {
        return requestedFor(method.name(), urlPathEqualTo(normalize(path)));
    }

    private static String normalize(final String path) {
        requireNonNull(path, "path");
        return path.startsWith("/") ? path : "/" + path;
    }

    public static final class StubResponse {

        private int status = HttpStatus.OK.code();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private HttpBody body = HttpBody.absent();
        private Duration delay;
        private Duration chunkedDribbleDelay;
        private int chunkCount;
        private boolean connectionReset;

        private StubResponse() {}

        public StubResponse status(final int status) {
            this.status = status;
            return this;
        }

        public StubResponse header(final String name, final String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            headers.put(name, value);
            return this;
        }

        public StubResponse body(final byte[] bytes) {
            this.body = HttpBody.of(bytes);
            return this;
        }

        public StubResponse delay(final Duration delay) {
            this.delay = requireNonNull(delay, "delay");
            return this;
        }

        public StubResponse chunkedDribbleDelay(final int chunks, final Duration total) {
            this.chunkCount = chunks;
            this.chunkedDribbleDelay = requireNonNull(total, "total");
            return this;
        }

        public StubResponse connectionReset() {
            this.connectionReset = true;
            return this;
        }

        private ResponseDefinitionBuilder toResponse() {
            if (connectionReset) {
                return aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
            }

            final ResponseDefinitionBuilder response = aResponse().withStatus(status);
            headers.forEach(response::withHeader);
            if (!body.isAbsent()) {
                response.withBody(body.bytes());
            }
            if (delay != null) {
                response.withFixedDelay((int) delay.toMillis());
            }
            if (chunkedDribbleDelay != null) {
                response.withChunkedDribbleDelay(chunkCount, (int) chunkedDribbleDelay.toMillis());
            }
            return response;
        }
    }
}
