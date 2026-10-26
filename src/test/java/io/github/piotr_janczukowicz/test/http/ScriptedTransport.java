package io.github.piotr_janczukowicz.test.http;

import static io.github.piotr_janczukowicz.util.ConcurrencyUtils.sleepInterruptibly;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNegative;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A scripted {@link HttpTransport} for tests. It answers {@code execute} and {@code stream} calls from a queue or a
 * function, records every request with its start time, and can delay or gate calls.
 *
 * <p>All state is thread-safe, and the configuration methods may be called while calls are running.
 */
public final class ScriptedTransport {

    private static final long GATE_TIMEOUT_SECONDS = 10;
    private static final int NO_GATE = Integer.MAX_VALUE;

    private final Function<HttpRequest, HttpResponse> responder;
    private final HttpTransport transport;
    private final Object lock = new Object();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<Long> startNanos = new ArrayList<>();
    private final List<Instant> startedAt = new ArrayList<>();
    private volatile HttpResponse defaultReply = new HttpResponse(200, HttpHeaders.empty(), HttpBody.zeroLength());
    private volatile Duration delay = Duration.ZERO;
    private volatile Clock clock = Clock.systemUTC();
    private volatile int gateFrom = NO_GATE;
    private volatile @Nullable CountDownLatch gate;
    private volatile boolean closed;

    private ScriptedTransport(final Function<HttpRequest, HttpResponse> responder) {
        this.responder = responder;
        this.transport = HttpTransportMocks.mock(
                invocation -> switch (invocation.getMethod().getName()) {
                    case "execute" -> execute(invocation.getArgument(0));
                    case "stream" -> HttpStreamResponse.buffered(execute(invocation.getArgument(0)));
                    case "close" -> {
                        closed = true;
                        yield null;
                    }
                    default -> null;
                });
    }

    /** Answers with the given replies in order and, once they run out, with the default reply. */
    public static ScriptedTransport replying(final HttpResponse... replies) {
        requireNonNull(replies, "replies");
        final Queue<HttpResponse> queue = new ConcurrentLinkedQueue<>(List.of(replies));
        final ScriptedTransport[] self = new ScriptedTransport[1];
        self[0] = new ScriptedTransport(request -> {
            final HttpResponse next = queue.poll();
            return next != null ? next : self[0].defaultReply;
        });
        return self[0];
    }

    public static ScriptedTransport responding(final Function<HttpRequest, HttpResponse> responder) {
        return new ScriptedTransport(requireNonNull(responder, "responder"));
    }

    public ScriptedTransport withDefault(final HttpResponse reply) {
        this.defaultReply = requireNonNull(reply, "reply");
        return this;
    }

    public ScriptedTransport withDelay(final Duration delay) {
        this.delay = requireNonNegative(delay, "delay");
        return this;
    }

    /** Reads {@link #startedAt()} from the given clock instead of the system clock. */
    public ScriptedTransport withClock(final Clock clock) {
        this.clock = requireNonNull(clock, "clock");
        return this;
    }

    /** Makes the calls numbered {@code callNumber} (one-based) and above wait for the gate, for at most ten seconds. */
    public ScriptedTransport gatedFromCall(final int callNumber, final CountDownLatch gate) {
        requireAtLeast(callNumber, 1, "callNumber");
        this.gate = requireNonNull(gate, "gate");
        this.gateFrom = callNumber;
        return this;
    }

    public HttpTransport transport() {
        return transport;
    }

    public int calls() {
        synchronized (lock) {
            return requests.size();
        }
    }

    /** Returns the request of the call with the given zero-based index. */
    public HttpRequest request(final int index) {
        synchronized (lock) {
            if (index < 0 || index >= requests.size()) {
                throw new IllegalArgumentException(
                        "index must be between 0 and " + (requests.size() - 1) + " but was " + index);
            }
            return requests.get(index);
        }
    }

    public List<HttpRequest> requests() {
        synchronized (lock) {
            return List.copyOf(requests);
        }
    }

    public List<Long> startNanos() {
        synchronized (lock) {
            return List.copyOf(startNanos);
        }
    }

    /** Returns the start time of every call as read from the configured clock. */
    public List<Instant> startedAt() {
        synchronized (lock) {
            return List.copyOf(startedAt);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Waits until the call with the given one-based number has started, and returns false on timeout. */
    public boolean awaitCall(final int callNumber, final Duration timeout) throws InterruptedException {
        requireAtLeast(callNumber, 1, "callNumber");
        final long deadline =
                System.nanoTime() + requireNonNegative(timeout, "timeout").toNanos();
        synchronized (lock) {
            while (requests.size() < callNumber) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return true;
        }
    }

    private HttpResponse execute(final HttpRequest request) throws IOException {
        final int number;
        synchronized (lock) {
            requests.add(request);
            startNanos.add(System.nanoTime());
            startedAt.add(clock.instant());
            number = requests.size();
            lock.notifyAll();
        }
        try {
            final CountDownLatch latch = gate;
            if (latch != null && number >= gateFrom) {
                latch.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        sleepInterruptibly(delay, "scripted transport interrupted");
        return responder.apply(request);
    }
}
