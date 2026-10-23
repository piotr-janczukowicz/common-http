package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class ResponseTooLargeExceptionTest {

    @Test
    void constructor_shouldExposeLimitAndMessage_whenCreated() {
        final ResponseTooLargeException exception = new ResponseTooLargeException(1024);

        assertSoftly(softly -> {
            softly.assertThat(exception).isInstanceOf(IOException.class);
            softly.assertThat(exception.maxBytes()).isEqualTo(1024);
            softly.assertThat(exception).hasMessage("Response body exceeds the limit of 1024 bytes");
        });
    }
}
