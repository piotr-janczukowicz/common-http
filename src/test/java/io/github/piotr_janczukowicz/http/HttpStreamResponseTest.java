package io.github.piotr_janczukowicz.http;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpStreamResponseTest {

    @Test
    void streaming_shouldExposeStatusHeadersAndHeader_whenBodyIsLive() throws Exception {
        final HttpHeaders headers = HttpHeaders.fromWire(Map.of(ETAG, List.of(" \"v1\" "), "X-Blank", List.of(" ")));

        try (final HttpStreamResponse response =
                HttpStreamResponse.streaming(200, headers, new ByteArrayInputStream(new byte[] {1}))) {
            assertSoftly(softly -> {
                softly.assertThat(response.headers()).isSameAs(headers);
                softly.assertThat(response.header(ETAG)).contains("\"v1\"");
                softly.assertThat(response.header("X-Blank")).isEmpty();
                softly.assertThat(response.header("X-Missing")).isEmpty();
                softly.assertThat(response.isStreaming()).isTrue();
            });
        }
    }

    @Test
    void bufferedResponse_shouldThrow_whenResponseIsStreaming() throws Exception {
        try (final HttpStreamResponse response =
                HttpStreamResponse.streaming(200, HttpHeaders.empty(), new ByteArrayInputStream(new byte[0]))) {
            final Throwable thrown = catchThrowable(response::bufferedResponse);

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
        }
    }
}
