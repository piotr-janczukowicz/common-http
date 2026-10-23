package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpHeaderNames;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class RateLimitGateHeadersTest {

    @Test
    void isPresent_shouldBeTrue_whenMarkerHasTheBlockedValue() {
        assertSoftly(softly -> {
            softly.assertThat(RateLimitGateHeaders.isPresent(
                            HttpHeaders.of(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE, RateLimitGateHeaders.BLOCKED)))
                    .isTrue();
            softly.assertThat(RateLimitGateHeaders.isPresent(HttpHeaders.of("x-client-rate-limit-gate", "BLOCKED")))
                    .isTrue();
        });
    }

    @Test
    void isPresent_shouldBeFalse_whenMarkerIsMissingOrHasAnotherValue() {
        assertSoftly(softly -> {
            softly.assertThat(RateLimitGateHeaders.isPresent(HttpHeaders.empty()))
                    .isFalse();
            softly.assertThat(RateLimitGateHeaders.isPresent(
                            HttpHeaders.of(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE, "open")))
                    .isFalse();
            softly.assertThat(RateLimitGateHeaders.isPresent(HttpHeaders.of(RETRY_AFTER, "5")))
                    .isFalse();
        });
    }

    @Test
    void isPresent_shouldThrow_whenHeadersAreNull() {
        final Throwable thrown = catchThrowable(() -> RateLimitGateHeaders.isPresent(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<RateLimitGateHeaders> constructor = RateLimitGateHeaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
