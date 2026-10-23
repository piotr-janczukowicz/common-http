package pl.piotrjanczukowicz.http.transport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Clock} that starts at the wall-clock time of its creation and then only follows {@link System#nanoTime()}. A
 * step of the system clock, for example by a time synchronisation or a resumed virtual machine, does not move it, so a
 * difference between two of its instants is real elapsed time. Use it to measure intervals; the instants themselves can
 * drift from the wall clock.
 */
final class MonotonicClock extends Clock {

    private final Instant start;
    private final long startNanos;

    MonotonicClock() {
        this(Instant.now(), System.nanoTime());
    }

    MonotonicClock(final Instant start, final long startNanos) {
        super();
        this.start = start;
        this.startNanos = startNanos;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return start.plusNanos(System.nanoTime() - startNanos);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return this == other
                || other instanceof final MonotonicClock that
                        && start.equals(that.start)
                        && startNanos == that.startNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, startNanos);
    }
}
