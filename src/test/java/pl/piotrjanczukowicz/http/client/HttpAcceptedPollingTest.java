package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class HttpAcceptedPollingTest {

    @Test
    void defaults_shouldWaitAboutEightSeconds_whenCalled() {
        final HttpAcceptedPolling polling = HttpAcceptedPolling.defaults();

        assertSoftly(softly -> {
            softly.assertThat(polling.maxAttempts()).isEqualTo(5);
            softly.assertThat(polling.delay()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void constructor_shouldAccept_whenSingleAttemptAndZeroDelay() {
        final HttpAcceptedPolling polling = new HttpAcceptedPolling(1, Duration.ZERO);

        assertThat(polling.maxAttempts()).isEqualTo(1);
    }

    @Test
    void constructor_shouldThrow_whenNoAttempts() {
        final Throwable thrown = catchThrowable(() -> new HttpAcceptedPolling(0, Duration.ZERO));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
    }

    @Test
    void constructor_shouldThrow_whenDelayNegative() {
        final var duration = Duration.ofSeconds(-1);

        final Throwable thrown = catchThrowable(() -> new HttpAcceptedPolling(2, duration));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delay");
    }

    @Test
    void constructor_shouldThrow_whenDelayNull() {
        final Throwable thrown = catchThrowable(() -> new HttpAcceptedPolling(2, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("delay");
    }
}
