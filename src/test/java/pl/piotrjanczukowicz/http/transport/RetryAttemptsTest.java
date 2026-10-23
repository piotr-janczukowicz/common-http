package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class RetryAttemptsTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructor_shouldStartEveryKindAtTheFirstAttempt_whenCreated() {
        final RetryAttempts attempts = new RetryAttempts();

        assertSoftly(softly -> {
            softly.assertThat(attempts.ioAttempt()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitAttempt()).isEqualTo(1);
            softly.assertThat(attempts.serverAttempt()).isEqualTo(1);
            softly.assertThat(attempts.finished()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitWaits()).isZero();
            softly.assertThat(attempts.lastFailure()).isNull();
        });
    }

    @Test
    void advance_shouldCountOnlyIoAttempt_whenReasonIsIo() {
        final RetryAttempts attempts = new RetryAttempts();

        attempts.advance(RetryPolicy.RetryWait.Reason.IO);

        assertSoftly(softly -> {
            softly.assertThat(attempts.ioAttempt()).isEqualTo(2);
            softly.assertThat(attempts.rateLimitAttempt()).isEqualTo(1);
            softly.assertThat(attempts.serverAttempt()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitWaits()).isZero();
            softly.assertThat(attempts.finished()).isEqualTo(2);
        });
    }

    @Test
    void advance_shouldCountAttemptAndWait_whenReasonIsRateLimit() {
        final RetryAttempts attempts = new RetryAttempts();

        attempts.advance(RetryPolicy.RetryWait.Reason.RATE_LIMIT);

        assertSoftly(softly -> {
            softly.assertThat(attempts.ioAttempt()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitAttempt()).isEqualTo(2);
            softly.assertThat(attempts.serverAttempt()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitWaits()).isEqualTo(1);
            softly.assertThat(attempts.finished()).isEqualTo(2);
        });
    }

    @Test
    void advance_shouldCountOnlyServerAttempt_whenReasonIsServerError() {
        final RetryAttempts attempts = new RetryAttempts();

        attempts.advance(RetryPolicy.RetryWait.Reason.SERVER_ERROR);

        assertSoftly(softly -> {
            softly.assertThat(attempts.ioAttempt()).isEqualTo(1);
            softly.assertThat(attempts.rateLimitAttempt()).isEqualTo(1);
            softly.assertThat(attempts.serverAttempt()).isEqualTo(2);
            softly.assertThat(attempts.rateLimitWaits()).isZero();
            softly.assertThat(attempts.finished()).isEqualTo(2);
        });
    }

    @Test
    void countRateLimitWait_shouldAddOneWait_whenCalled() {
        final RetryAttempts attempts = new RetryAttempts();

        attempts.countRateLimitWait();
        attempts.countRateLimitWait();

        assertThat(attempts.rateLimitWaits()).isEqualTo(2);
    }

    @Test
    void remember_shouldKeepLastFailure_whenCalledTwice() {
        final RetryAttempts attempts = new RetryAttempts();
        final IOException first = new IOException("first");
        final IOException second = new IOException("second");

        attempts.remember(first);
        attempts.remember(second);

        assertThat(attempts.lastFailure()).isSameAs(second);
    }

    @Test
    void hasWaitedFor_shouldBeFalse_whenNothingWasWaitedFor() {
        final RetryAttempts attempts = new RetryAttempts();

        final boolean hasWaited = attempts.hasWaitedFor(BASE);

        assertThat(hasWaited).isFalse();
    }

    @Test
    void waitedFor_shouldCoverEarlierAndEqualInstants_whenFirstWaitIsSet() {
        final RetryAttempts attempts = new RetryAttempts();

        attempts.waitedFor(BASE.plusSeconds(10));

        assertSoftly(softly -> {
            softly.assertThat(attempts.hasWaitedFor(BASE.plusSeconds(5))).isTrue();
            softly.assertThat(attempts.hasWaitedFor(BASE.plusSeconds(10))).isTrue();
            softly.assertThat(attempts.hasWaitedFor(BASE.plusSeconds(11))).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({"10, 20", "20, 10", "20, 20"})
    void waitedFor_shouldKeepLatestInstant_whenCalledTwice(final long firstSeconds, final long secondSeconds) {
        final RetryAttempts attempts = new RetryAttempts();
        attempts.waitedFor(BASE.plusSeconds(firstSeconds));

        attempts.waitedFor(BASE.plusSeconds(secondSeconds));

        assertSoftly(softly -> {
            softly.assertThat(attempts.hasWaitedFor(BASE.plusSeconds(20))).isTrue();
            softly.assertThat(attempts.hasWaitedFor(BASE.plusSeconds(21))).isFalse();
        });
    }
}
