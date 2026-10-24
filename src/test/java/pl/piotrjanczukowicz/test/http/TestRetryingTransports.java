package pl.piotrjanczukowicz.test.http;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.RetryConfiguration;
import pl.piotrjanczukowicz.http.transport.RetryingHttpTransport;
import pl.piotrjanczukowicz.testsupport.MutableClock;

public final class TestRetryingTransports {

    private TestRetryingTransports() {
        throw new UnsupportedOperationException("Utility class");
    }

    @FunctionalInterface
    public interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    public static RetryingHttpTransport create(
            final HttpTransport delegate,
            final RetryConfiguration configuration,
            final Pause pause,
            final Clock clock) {
        return new RetryingHttpTransport(delegate, configuration, pause::sleep, clock);
    }

    public static RetryingHttpTransport create(
            final HttpTransport delegate,
            final RetryConfiguration configuration,
            final Pause pause,
            final Clock clock,
            final RandomGenerator random) {
        return new RetryingHttpTransport(delegate, configuration, pause::sleep, clock, random);
    }

    public static Pause recordingAndAdvancing(final List<Duration> sleeps, final MutableClock clock) {
        return duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        };
    }

    public static int gateSize(final RetryingHttpTransport transport) {
        return transport.gateSize();
    }
}
