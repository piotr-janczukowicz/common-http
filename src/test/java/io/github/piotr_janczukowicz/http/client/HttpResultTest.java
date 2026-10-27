package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpResultTest {

    @Test
    void value_shouldBeDecodedValue_whenBodyWasDecoded() {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), Map.of(), null);
        final HttpResult<String> result = new HttpResult<>(response, "decoded");

        assertThat(result.value()).contains("decoded");
    }

    @Test
    void value_shouldBeEmpty_whenNoBodyWasDecoded() {
        final HttpResult<Void> result = resultWith(Map.of());

        assertThat(result.value()).isEmpty();
    }

    @Test
    void isNotModified_shouldBeTrue_whenStatusIsNotModified() {
        final HttpResponse response = HttpResponses.of(HttpStatus.NOT_MODIFIED.code(), Map.of(), null);

        assertThat(new HttpResult<Void>(response, null).isNotModified()).isTrue();
    }

    @Test
    void etag_shouldBeTheEtagHeader_whenPresent() {
        final HttpResult<Void> result = resultWith(Map.of(ETAG, List.of("W/\"abc\"")));

        assertThat(result.etag()).contains("W/\"abc\"");
    }

    private static HttpResult<Void> resultWith(final Map<String, List<String>> headers) {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), headers, null);
        return new HttpResult<>(response, null);
    }
}
