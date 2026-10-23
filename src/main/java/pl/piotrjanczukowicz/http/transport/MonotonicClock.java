package pl.piotrjanczukowicz.http.transport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
        super();
        this.start = Instant.now();
        this.startNanos = System.nanoTime();
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
}
