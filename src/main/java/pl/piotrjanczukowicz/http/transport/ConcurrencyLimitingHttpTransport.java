package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ConcurrencyUtils.toInterruptedIo;
import static pl.piotrjanczukowicz.util.IoUtils.closeSuppressing;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requirePositive;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.NonRetryableIOException;

/**
 * {@link HttpTransport} decorator that lets at most {@code maxConcurrent} requests run at once on its delegate.
 *
 * <p>The OkHttp transport from {@code HttpTransports} sends requests synchronously on the caller's thread, so OkHttp's
 * dispatcher limits do not apply to it and the number of concurrent requests is the number of calling threads. With
 * many (virtual) threads that can exceed what a server accepts; some APIs cap concurrent requests per client. A caller
 * over the limit waits for a free slot; slots are handed out in arrival order.
 *
 * <p>The limit is per instance, so a transport shared by several clients, for example by a token source that fetches
 * its tokens through the same transport, shares one limit. {@link #execute(HttpRequest)} holds a slot until the
 * buffered response is returned. {@link #stream(HttpRequest)} holds it until the returned response (or its body) is
 * closed, so a streaming response that is never closed keeps its slot. Put this decorator below a retrying transport,
 * so that a pause between attempts does not occupy a slot.
 *
 * <p><b>Do not nest calls.</b> A streaming response (and so the handler of {@code HttpClient.stream(...)}) holds its
 * slot until it is closed. A call made through the same client or transport from inside such a handler, also indirectly
 * through a token source that shares this transport, needs a second slot; when the limit is exhausted it waits for one
 * that may only be freed by the call that is waiting. The wait is bounded by {@code maxSlotWait}, after which the call
 * fails with a {@link NonRetryableIOException} caused by an {@link InterruptedIOException}.
 *
 * <p>Waiting for a slot can be interrupted: the thread's interrupt flag is set again and a
 * {@link NonRetryableIOException} caused by an {@link InterruptedIOException} is thrown, which a retrying transport
 * does not retry.
 */
public final class ConcurrencyLimitingHttpTransport implements HttpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyLimitingHttpTransport.class);
    private static final String DELEGATE_ARGUMENT = "delegate";
    private static final String INTERRUPTED_WAIT = "interrupted while waiting for a free request slot";

    private final HttpTransport delegate;
    private final Semaphore slots;
    private final int maxConcurrent;
    private final Duration maxSlotWait;

    /**
     * Limits {@code delegate} to {@code maxConcurrent} concurrent requests.
     *
     * @param delegate transport to limit; closed when this one is closed
     * @param maxConcurrent most requests in flight at once, above 0
     * @param maxSlotWait longest wait for a free slot, above zero and at most {@link Integer#MAX_VALUE} milliseconds
     * @throws NullPointerException if {@code delegate} or {@code maxSlotWait} is {@code null}
     * @throws IllegalArgumentException if {@code maxConcurrent} or {@code maxSlotWait} is not positive, or
     *     {@code maxSlotWait} exceeds {@link Integer#MAX_VALUE} milliseconds
     */
    public ConcurrencyLimitingHttpTransport(
            final HttpTransport delegate, final int maxConcurrent, final Duration maxSlotWait) {
        this.delegate = requireNonNull(delegate, DELEGATE_ARGUMENT);
        this.maxConcurrent = requirePositive(maxConcurrent, "maxConcurrent");
        this.slots = new Semaphore(maxConcurrent, true);
        this.maxSlotWait = HttpTransportConfiguration.requireSlotWait(maxSlotWait);
    }

    /**
     * Limits {@code delegate} to {@code maxConcurrent} concurrent requests, waiting at most
     * {@link HttpTransportConfiguration#DEFAULT_MAX_SLOT_WAIT} for a slot.
     *
     * @param delegate transport to limit; closed when this one is closed
     * @param maxConcurrent most requests in flight at once, above 0
     * @throws NullPointerException if {@code delegate} is {@code null}
     * @throws IllegalArgumentException if {@code maxConcurrent} is not positive
     */
    public ConcurrencyLimitingHttpTransport(final HttpTransport delegate, final int maxConcurrent) {
        this(delegate, maxConcurrent, HttpTransportConfiguration.DEFAULT_MAX_SLOT_WAIT);
    }

    /**
     * Sends {@code request} through the delegate once a slot is free.
     *
     * @param request request to send
     * @return the delegate's response
     * @throws IOException if the delegate fails, or {@link NonRetryableIOException} if the wait for a slot was
     *     interrupted or exceeded {@code maxSlotWait}
     */
    @Override
    public HttpResponse execute(final HttpRequest request) throws IOException {
        acquire();
        try {
            return delegate.execute(request);
        } finally {
            slots.release();
        }
    }

    /**
     * Opens a stream through the delegate once a slot is free; the slot is released when the response is closed.
     *
     * @param request request to send
     * @return open response, to be closed by the caller
     * @throws IOException if the delegate fails, or {@link NonRetryableIOException} if the wait for a slot was
     *     interrupted or exceeded {@code maxSlotWait}
     */
    @Override
    @SuppressWarnings(
            "java:S1181") // An Error while wrapping must not leak the slot, so it is caught, cleaned up after, and
    // rethrown.
    public HttpStreamResponse stream(final HttpRequest request) throws IOException {
        acquire();
        final AtomicBoolean isReleased = new AtomicBoolean();
        final HttpStreamResponse opened = open(request, isReleased);
        try {
            return holdSlotWhileStreaming(opened, isReleased);
        } catch (final RuntimeException | Error wrapFailure) {
            closeSuppressing(opened, wrapFailure);
            release(isReleased);
            throw wrapFailure;
        }
    }

    /** Closes the delegate. */
    @Override
    public void close() {
        delegate.close();
    }

    private void acquire() throws IOException {
        if (slots.availablePermits() == 0) {
            LOG.atDebug()
                    .setMessage("All {} request slots are in use; waiting up to {} for one")
                    .addArgument(maxConcurrent)
                    .addArgument(maxSlotWait)
                    .log();
        }
        if (!tryAcquireSlot()) {
            throw new NonRetryableIOException(
                    "no free request slot within " + maxSlotWait + " (limit " + maxConcurrent
                            + "); a call made from inside a stream handler can wait for its own slot",
                    new InterruptedIOException("timed out waiting for a free request slot"));
        }
    }

    @SuppressWarnings("java:S2142") // toInterruptedIo restores the interrupt flag before the exception is built.
    private boolean tryAcquireSlot() throws NonRetryableIOException {
        try {
            return slots.tryAcquire(maxSlotWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            throw new NonRetryableIOException(INTERRUPTED_WAIT, toInterruptedIo(e, INTERRUPTED_WAIT));
        }
    }

    private HttpStreamResponse holdSlotWhileStreaming(final HttpStreamResponse opened, final AtomicBoolean isReleased) {
        // Only a live stream keeps the slot, until the caller closes it; a buffered response holds no connection.
        return opened.isStreaming() ? streamingWithSlot(opened, isReleased) : releaseSlotFor(opened, isReleased);
    }

    private HttpStreamResponse streamingWithSlot(final HttpStreamResponse opened, final AtomicBoolean isReleased) {
        return HttpStreamResponse.streaming(
                opened.statusCode(),
                opened.headers(),
                new SlotReleasingInputStream(opened.body(), () -> release(isReleased)));
    }

    private HttpStreamResponse releaseSlotFor(final HttpStreamResponse buffered, final AtomicBoolean isReleased) {
        release(isReleased);
        return buffered;
    }

    private HttpStreamResponse open(final HttpRequest request, final AtomicBoolean isReleased) throws IOException {
        boolean isOpened = false;
        try {
            final HttpStreamResponse opened = delegate.stream(request);
            isOpened = true;
            return opened;
        } finally {
            if (!isOpened) {
                release(isReleased);
            }
        }
    }

    private void release(final AtomicBoolean isReleased) {
        if (isReleased.compareAndSet(false, true)) {
            slots.release();
        }
    }

    private static final class SlotReleasingInputStream extends FilterInputStream {

        private final Runnable onClose;

        SlotReleasingInputStream(final InputStream body, final Runnable onClose) {
            super(body);
            this.onClose = onClose;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            return in.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                onClose.run();
            }
        }
    }
}
