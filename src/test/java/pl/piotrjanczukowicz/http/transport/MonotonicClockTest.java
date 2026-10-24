package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class MonotonicClockTest {

    @Test
    void instant_shouldNeverGoBack_whenReadRepeatedly() {
        final Clock clock = new MonotonicClock();
        Instant previous = clock.instant();
        boolean isOrdered = true;

        for (int read = 0; read < 1_000; read++) {
            final Instant current = clock.instant();
            isOrdered &= !current.isBefore(previous);
            previous = current;
        }

        assertThat(isOrdered).isTrue();
    }

    @Test
    void instant_shouldStartNearWallClock_whenCreated() {
        final Instant before = Instant.now();

        final Instant read = new MonotonicClock().instant();

        assertThat(Duration.between(before, read)).isBetween(Duration.ZERO, Duration.ofSeconds(5));
    }

    @Test
    void zone_shouldBeUtcAndKeepSameClock_whenZoneChanged() {
        final Clock clock = new MonotonicClock();

        final Clock other = clock.withZone(ZoneId.of("Europe/Warsaw"));

        assertSoftly(softly -> {
            softly.assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            softly.assertThat(other).isSameAs(clock);
        });
    }

    @Test
    void equals_shouldFollowStartInstantAndNanos_whenCompared() {
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final MonotonicClock clock = new MonotonicClock(start, 10L);

        assertSoftly(softly -> {
            softly.assertThat(clock).isEqualTo(clock).hasSameHashCodeAs(new MonotonicClock(start, 10L));
            softly.assertThat(clock).isEqualTo(new MonotonicClock(start, 10L));
            softly.assertThat(clock).isNotEqualTo(new MonotonicClock(start, 11L));
            softly.assertThat(clock).isNotEqualTo(new MonotonicClock(start.plusSeconds(1), 10L));
            softly.assertThat(clock).isNotEqualTo(null).isNotEqualTo("clock");
        });
    }
}
