package io.github.piotr_janczukowicz.http;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.IOException;
import org.junit.jupiter.api.Test;

@UnitTest
class NonRetryableIOExceptionTest {

    @Test
    void constructor_shouldKeepMessageAndCause_whenCauseGiven() {
        final IOException cause = new IOException("disk");

        final NonRetryableIOException exception = new NonRetryableIOException("source failed", cause);

        assertSoftly(softly -> {
            softly.assertThat(exception).isInstanceOf(IOException.class);
            softly.assertThat(exception).hasMessage("source failed").hasCause(cause);
        });
    }

    @Test
    void constructor_shouldHaveNoCause_whenOnlyMessageGiven() {
        final NonRetryableIOException exception = new NonRetryableIOException("transport closed");

        assertSoftly(softly -> {
            softly.assertThat(exception).hasMessage("transport closed");
            softly.assertThat(exception.getCause()).isNull();
        });
    }
}
