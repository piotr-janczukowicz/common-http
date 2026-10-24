package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class HttpComputedTest {

    @Test
    void ready_shouldExposeValue_whenDataArrived() {
        final HttpComputed<String> computed = new HttpComputed.Ready<>("data");

        assertSoftly(softly -> {
            softly.assertThat(computed.isReady()).isTrue();
            softly.assertThat(computed.toOptional()).contains("data");
        });
    }

    @Test
    void notReady_shouldBeEmpty_whenStillComputing() {
        final HttpComputed<String> computed = new HttpComputed.NotReady<>();

        assertSoftly(softly -> {
            softly.assertThat(computed.isReady()).isFalse();
            softly.assertThat(computed.toOptional()).isEmpty();
        });
    }

    @Test
    void noData_shouldBeEmpty_whenNothingToReport() {
        final HttpComputed<String> computed = new HttpComputed.NoData<>();

        assertSoftly(softly -> {
            softly.assertThat(computed.isReady()).isFalse();
            softly.assertThat(computed.toOptional()).isEmpty();
        });
    }

    @Test
    void ready_shouldThrow_whenValueNull() {
        final Throwable thrown = catchThrowable(() -> new HttpComputed.Ready<String>(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void states_shouldBeDistinguishable_whenSwitchedOn() {
        final HttpComputed<String> computed = new HttpComputed.NotReady<>();

        final String label =
                switch (computed) {
                    case final HttpComputed.Ready<String> ready -> "ready " + ready.value();
                    case final HttpComputed.NotReady<String> ignored -> "not ready";
                    case final HttpComputed.NoData<String> ignored -> "no data";
                };

        assertThat(label).isEqualTo("not ready");
    }
}
