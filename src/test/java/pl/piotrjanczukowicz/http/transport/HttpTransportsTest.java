package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpTransportsTest {

    private static final URI API_URI = URI.create("https://api.example.test/user");

    @Test
    void constructor_shouldUseConfiguration_whenConfigurationOnly() {
        final HttpTransportConfiguration configuration = HttpTransportConfiguration.builder()
                .responseTimeout(Duration.ofSeconds(5))
                .build();

        try (final HttpTransport transport = HttpTransports.okHttp(configuration)) {
            assertThat(transport).isNotNull();
        }
    }

    @Test
    void constructor_shouldUseDefaults_whenNoArgs() {
        try (final HttpTransport transport = HttpTransports.okHttp()) {
            assertThat(transport).isNotNull();
        }
    }

    @Test
    void constructor_shouldThrow_whenConfigurationNull() {
        final Throwable thrown = catchThrowable(() -> HttpTransports.okHttp((HttpTransportConfiguration) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    private static Response canned(final Request request, final int code, final byte[] body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("application/octet-stream")))
                .build();
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<HttpTransports> constructor = HttpTransports.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
