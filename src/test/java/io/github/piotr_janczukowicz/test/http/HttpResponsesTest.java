package io.github.piotr_janczukowicz.test.http;

import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpResponsesTest {

    @Test
    void response_shouldHaveNoBody_whenBodyIsNull() {
        final HttpResponse response = response(304, null, "ETag", "\"v1\"");

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(304);
            softly.assertThat(response.body().isAbsent()).isTrue();
            softly.assertThat(response.headers().get("ETag")).contains("\"v1\"");
        });
    }

    @Test
    void response_shouldEncodeBodyAsUtf8_whenBodyGiven() {
        final HttpResponse response = response(200, "zażółć");

        assertThat(response.requireBodyBytes()).isEqualTo("zażółć".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ok_shouldReturnOkWithBody_whenBodyGiven() {
        final HttpResponse response = HttpResponses.ok("{}");

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(200);
            softly.assertThat(response.requireBodyBytes()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void response_shouldAppendValues_whenHeaderNameRepeated() {
        final HttpResponse response = response(200, null, "Set-Cookie", "a=1", "Set-Cookie", "b=2");

        assertThat(response.headers().get("Set-Cookie")).contains("a=1, b=2");
    }

    @Test
    void response_shouldThrow_whenNamesAndValuesHaveOddLength() {
        final Throwable thrown = catchThrowable(() -> response(200, null, "ETag"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namesAndValues");
    }
}
