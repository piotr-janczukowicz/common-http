package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpTransportsTest {

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
        final Throwable thrown = catchThrowable(() -> HttpTransports.okHttp(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<HttpTransports> constructor = HttpTransports.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
