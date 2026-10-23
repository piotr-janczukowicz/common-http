package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ACCEPT;

import java.net.URI;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpClientConfigurationTest {

    private static final URI BASE = URI.create("https://api.example.test/v3");

    @Test
    void of_shouldHaveNoDefaultHeaders_whenOnlyBaseUriGiven() {
        final HttpClientConfiguration config = HttpClientConfiguration.of(BASE);

        assertSoftly(softly -> {
            softly.assertThat(config.baseUri()).isEqualTo(URI.create("https://api.example.test/v3/"));
            softly.assertThat(config.defaultHeaders()).isEqualTo(HttpHeaders.empty());
        });
    }

    @Test
    void build_shouldStoreFields_whenAllSet() {
        final HttpHeaders headers = HttpHeaders.of(ACCEPT, "application/json");

        final HttpClientConfiguration config =
                HttpClientConfiguration.builder(BASE).defaultHeaders(headers).build();

        assertSoftly(softly -> {
            softly.assertThat(config.baseUri()).isEqualTo(URI.create("https://api.example.test/v3/"));
            softly.assertThat(config.defaultHeaders()).isSameAs(headers);
        });
    }

    @Test
    void builder_shouldThrow_whenBaseUriNull() {
        final Throwable thrown = catchThrowable(() -> HttpClientConfiguration.builder(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("baseUri");
    }

    @Test
    void defaultHeaders_shouldThrow_whenNull() {
        final HttpClientConfiguration.Builder builder = HttpClientConfiguration.builder(BASE);

        final Throwable thrown = catchThrowable(() -> builder.defaultHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("defaultHeaders");
    }

    @Test
    void constructor_shouldThrow_whenBaseUriNull() {
        final Throwable thrown = catchThrowable(() -> new HttpClientConfiguration(null, HttpHeaders.empty()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("baseUri");
    }

    @Test
    void constructor_shouldThrow_whenDefaultHeadersNull() {
        final Throwable thrown = catchThrowable(() -> new HttpClientConfiguration(BASE, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("defaultHeaders");
    }
}
