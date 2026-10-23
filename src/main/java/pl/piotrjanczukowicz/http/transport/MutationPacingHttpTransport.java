package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ConcurrencyUtils.sleepInterruptibly;
import static pl.piotrjanczukowicz.util.ConcurrencyUtils.toInterruptedIo;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonEmpty;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requirePositive;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;

/**
 * {@link HttpTransport} decorator that keeps at least {@code minInterval} between the starts of consecutive requests
 * whose method is in {@code methods}, for example to pause between writes to an API that throttles mutations.
 *
 * <p>A request starts when this decorator releases it to the delegate. That moment is recorded under a fair lock, so
 * the interval holds across threads that share this transport: a caller whose request is due too early waits, in
 * arrival order, and the lock is not held while the delegate runs, so a slow request does not delay the next start
 * beyond the interval. Requests with other methods (GET and HEAD by default) pass through at once and are not counted
 * as starts.
 *
 * <p>Put this decorator above a {@link ConcurrencyLimitingHttpTransport} and below a retrying transport. The pause then
 * happens before a request slot is taken, and retries are paced too. The pause is not a retry, so it does not use up
 * any retry budget.
 *
 * <p>The callers that wait for a start queue on the lock, so the pause of the last of N waiting callers is N intervals:
 * that queue is the pacing. Closing this transport makes every caller that has not started yet fail with a
 * {@link NonRetryableIOException}, so a close waits for at most the one pause that is already running.
 *
 * <p>The default clock is monotonic, so a step of the system clock does not shorten or stretch the pacing. If an
 * injected clock moves backwards, the wait is still at most one interval. The waiting caller's interrupt flag is set
 * again and an {@link InterruptedIOException} is thrown without sending the request.
 *
 * <p>Off by default: a transport is paced only when a caller wraps it in this decorator.
 */
public final class MutationPacingHttpTransport implements HttpTransport {

    /** Methods paced by default: POST, PATCH, PUT and DELETE. */
    public static final Set<HttpMethod> DEFAULT_MUTATION_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.DELETE);

    private static final String DELEGATE = "delegate";
    private static final String METHODS = "methods";
    private static final String INTERRUPTED_WAIT = "interrupted while waiting for the mutation interval";

    private final HttpTransport delegate;
    private final Duration minInterval;
    private final Set<HttpMethod> methods;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private @Nullable Instant lastStart;

    /**
     * Paces {@code methods} on {@code delegate} with real time.
     *
     * @param delegate transport that sends the requests; closed when this one is closed
     * @param minInterval least time between the starts of two paced requests, above zero
     * @param methods methods whose requests are paced, not empty; {@link #DEFAULT_MUTATION_METHODS} for the usual set
     * @throws NullPointerException if {@code delegate}, {@code minInterval} or {@code methods} is {@code null}, or
     *     {@code methods} contains {@code null}
     * @throws IllegalArgumentException if {@code minInterval} is not positive or {@code methods} is empty
     */
    public MutationPacingHttpTransport(
            final HttpTransport delegate, final Duration minInterval, final Set<HttpMethod> methods) {
        this(
                delegate,
                minInterval,
                methods,
                new MonotonicClock(),
                duration -> sleepInterruptibly(duration, INTERRUPTED_WAIT));
    }

    /**
     * Paces {@code methods} on {@code delegate}, reading time from {@code clock} and pausing with {@code sleeper}.
     * Package-private so that tests can run without real waits.
     *
     * @param delegate transport that sends the requests; closed when this one is closed
     * @param minInterval least time between the starts of two paced requests, above zero
     * @param methods methods whose requests are paced, not empty
     * @param clock source of the start times
     * @param sleeper pause used to wait for the next start
     */
    MutationPacingHttpTransport(
            final HttpTransport delegate,
            final Duration minInterval,
            final Set<HttpMethod> methods,
            final Clock clock,
            final Sleeper sleeper) {
        this.delegate = requireNonNull(delegate, DELEGATE);
        this.minInterval = requirePositive(minInterval, "minInterval");
        this.methods = Set.copyOf(requireNonEmpty(methods, METHODS));
        this.clock = requireNonNull(clock, "clock");
        this.sleeper = requireNonNull(sleeper, "sleeper");
    }

    /**
     * Waits until the next start is due when the request's method is paced, then sends it through the delegate.
     *
     * @param request request to send
     * @return the delegate's response
     * @throws IOException if the delegate fails, an {@link InterruptedIOException} if the wait was interrupted, or a
     *     {@link NonRetryableIOException} if this transport was closed before the request started
     */
    @Override
    public HttpResponse execute(final HttpRequest request) throws IOException {
        awaitStartIfPaced(request);
        return delegate.execute(request);
    }

    /**
     * Waits until the next start is due when the request's method is paced, then opens a stream through the delegate.
     *
     * @param request request to send
     * @return open response, to be closed by the caller
     * @throws IOException if the delegate fails, an {@link InterruptedIOException} if the wait was interrupted, or a
     *     {@link NonRetryableIOException} if this transport was closed before the request started
     */
    @Override
    public HttpStreamResponse stream(final HttpRequest request) throws IOException {
        awaitStartIfPaced(request);
        return delegate.stream(request);
    }

    /** Makes callers that have not started fail, and closes the delegate. */
    @Override
    public void close() {
        isClosed.set(true);
        delegate.close();
    }

    private void awaitStartIfPaced(final HttpRequest request) throws IOException {
        if (methods.contains(request.method())) {
            awaitStart();
        }
    }

    private void awaitStart() throws IOException {
        lockInterruptibly();
        try {
            throwIfClosed();
            final Duration wait = waitBeforeStart();
            if (!wait.isZero()) {
                sleeper.sleep(wait);
                throwIfClosed();
            }
            lastStart = clock.instant();
        } finally {
            lock.unlock();
        }
    }

    private void throwIfClosed() throws NonRetryableIOException {
        if (isClosed.get()) {
            throw new NonRetryableIOException("transport closed");
        }
    }

    private void lockInterruptibly() throws InterruptedIOException {
        try {
            lock.lockInterruptibly();
        } catch (final InterruptedException e) {
            throw toInterruptedIo(e, INTERRUPTED_WAIT);
        }
    }

    private Duration waitBeforeStart() {
        if (lastStart == null) {
            return Duration.ZERO;
        }
        final Duration untilAllowed = Duration.between(clock.instant(), lastStart.plus(minInterval));
        if (untilAllowed.isNegative()) {
            return Duration.ZERO;
        }
        // A clock that moved back must not stretch the wait beyond one interval.
        return untilAllowed.compareTo(minInterval) > 0 ? minInterval : untilAllowed;
    }

    /** Pause used to wait for the next start. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Pauses for {@code duration}.
         *
         * @param duration pause length, above zero
         * @throws InterruptedIOException if the pause was interrupted
         */
        void sleep(Duration duration) throws InterruptedIOException;
    }
}
