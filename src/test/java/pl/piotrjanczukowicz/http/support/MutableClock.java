package pl.piotrjanczukowicz.http.support;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(final Instant now) {
        this.now = requireNonNull(now, "now");
    }

    public void set(final Instant instant) {
        this.now = requireNonNull(instant, "instant");
    }

    public synchronized void advance(final Duration amount) {
        this.now = now.plus(requireNonNull(amount, "amount"));
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
