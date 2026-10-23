package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpStatusTest {

    @Test
    void hasCode_shouldCompareStatusCode_whenCalled() {
        final boolean matchesOk = HttpStatus.OK.hasCode(200);
        final boolean matchesCreated = HttpStatus.OK.hasCode(201);

        assertSoftly(softly -> {
            softly.assertThat(matchesOk).isTrue();
            softly.assertThat(matchesCreated).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 404, 599})
    void isValid_shouldReturnTrue_whenCodeInRange(final int statusCode) {
        final boolean valid = HttpStatus.isValid(statusCode);

        assertThat(valid).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600})
    void isValid_shouldReturnFalse_whenCodeOutOfRange(final int statusCode) {
        final boolean valid = HttpStatus.isValid(statusCode);

        assertThat(valid).isFalse();
    }

    @Test
    void requireValid_shouldReturnCode_whenCodeInRange() {
        final int code = HttpStatus.requireValid(200);

        assertThat(code).isEqualTo(200);
    }

    @Test
    void requireValid_shouldThrow_whenCodeOutOfRange() {
        final Throwable thrown = catchThrowable(() -> HttpStatus.requireValid(99));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("statusCode must be between 100 and 599");
    }
}
