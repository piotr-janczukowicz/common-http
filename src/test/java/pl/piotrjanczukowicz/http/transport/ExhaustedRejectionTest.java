package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class ExhaustedRejectionTest {

    @Test
    void constructor_shouldThrow_whenInvoked() throws Exception {
        final Constructor<ExhaustedRejection> constructor = ExhaustedRejection.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void of_shouldKeepOnlyReplayableHeaders_whenResponseHasMixedHeaders() {
        final HttpResponse response = new HttpResponse(
                HttpStatus.TOO_MANY_REQUESTS.code(),
                HttpHeaders.fromWire(Map.of(
                        "Retry-After", List.of("30"),
                        "X-RateLimit-Remaining", List.of("0"),
                        "CONTENT-LENGTH", List.of("12"),
                        "Content-Type", List.of("application/json"),
                        "ETag", List.of("\"v1\""),
                        "Last-Modified", List.of("Mon, 01 Jan 2026 00:00:00 GMT"),
                        "Transfer-Encoding", List.of("chunked"),
                        "Date", List.of("Mon, 01 Jan 2026 00:00:00 GMT"))),
                HttpBody.of("{\"message\":\"slow down\"}".getBytes(StandardCharsets.UTF_8)));

        final HttpResponse rejection = ExhaustedRejection.of(response);

        assertSoftly(softly -> {
            softly.assertThat(rejection.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.code());
            softly.assertThat(rejection.body().isAbsent()).isTrue();
            softly.assertThat(rejection.headers().get("Retry-After")).contains("30");
            softly.assertThat(rejection.headers().get("X-RateLimit-Remaining")).contains("0");
            softly.assertThat(rejection.headers().get("CONTENT-LENGTH")).isEmpty();
            softly.assertThat(rejection.headers().get("Content-Type")).isEmpty();
            softly.assertThat(rejection.headers().get("ETag")).isEmpty();
            softly.assertThat(rejection.headers().get("Last-Modified")).isEmpty();
            softly.assertThat(rejection.headers().get("Transfer-Encoding")).isEmpty();
            softly.assertThat(rejection.headers().get("Date")).isEmpty();
        });
    }
}
