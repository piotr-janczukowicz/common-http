package io.github.piotr_janczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpErrorBodiesTest {

    @Test
    void truncated_shouldKeepShortTextAndCutLongText_whenTextLengthVaries() {
        final String longText = "x".repeat(2000);

        assertSoftly(softly -> {
            softly.assertThat(HttpErrorBodies.truncated("short")).isEqualTo("short");
            softly.assertThat(HttpErrorBodies.truncated(longText))
                    .hasSize(1024 + "... [truncated]".length())
                    .endsWith("... [truncated]");
        });
    }

    @Test
    void cappedBody_shouldReturnNull_whenBodyNullAndCapLongBody() {
        final String longBody = "y".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS + 5);

        assertSoftly(softly -> {
            softly.assertThat(HttpErrorBodies.cappedBody(null)).isNull();
            softly.assertThat(HttpErrorBodies.cappedBody(longBody)).hasSize(HttpErrorBodies.MAX_RAW_BODY_CHARS);
        });
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<HttpErrorBodies> constructor = HttpErrorBodies.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
