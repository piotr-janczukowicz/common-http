package pl.piotrjanczukowicz.http.transport;

import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Mutable per-call retry counters, one per kind of failure; confined to the thread running one call. */
final class RetryAttempts {

    private int ioAttempt;
    private int rateLimitAttempt;
    private int serverAttempt;
    private @Nullable IOException lastFailure;
    private @Nullable Instant waitedUntil;
    private int rateLimitWaits;

    /** Starts a call whose every kind of attempt is the first one. */
    RetryAttempts() {
        ioAttempt = 1;
        rateLimitAttempt = 1;
        serverAttempt = 1;
    }

    int ioAttempt() {
        return ioAttempt;
    }

    int rateLimitAttempt() {
        return rateLimitAttempt;
    }

    int serverAttempt() {
        return serverAttempt;
    }

    int finished() {
        return ioAttempt + rateLimitAttempt + serverAttempt - 2;
    }

    @Nullable IOException lastFailure() {
        return lastFailure;
    }

    // Waits of this call for rate limits, its own and the gate's: they share one budget.
    int rateLimitWaits() {
        return rateLimitWaits;
    }

    void countRateLimitWait() {
        rateLimitWaits++;
    }

    // The call has paused at least until this instant, so a limit that ends by then no longer holds it back. It
    // keeps a call from waiting twice for the limit it has just waited out, whatever the clock does meanwhile.
    void waitedFor(final Instant until) {
        if (waitedUntil == null || until.isAfter(waitedUntil)) {
            waitedUntil = until;
        }
    }

    boolean hasWaitedFor(final Instant until) {
        return waitedUntil != null && !until.isAfter(waitedUntil);
    }

    void remember(final IOException failure) {
        lastFailure = failure;
    }

    void advance(final RetryPolicy.RetryWait.Reason reason) {
        switch (reason) {
            case IO -> {
                ioAttempt++;
            }
            case RATE_LIMIT -> {
                rateLimitAttempt++;
                countRateLimitWait();
            }
            case SERVER_ERROR -> {
                serverAttempt++;
            }
        }
    }
}
