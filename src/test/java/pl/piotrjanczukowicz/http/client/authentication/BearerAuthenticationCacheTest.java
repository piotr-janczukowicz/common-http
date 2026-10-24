package pl.piotrjanczukowicz.http.client.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class BearerAuthenticationCacheTest {

    @Test
    void forToken_shouldReturnSameInstance_whenTokenRepeats() {
        final BearerAuthenticationCache cache = new BearerAuthenticationCache();

        final Authentication first = cache.forToken("token-1");
        final Authentication second = cache.forToken("token-1");

        assertThat(second).isSameAs(first);
    }

    @Test
    void forToken_shouldReturnNewInstance_whenTokenChanges() {
        final BearerAuthenticationCache cache = new BearerAuthenticationCache();
        final Authentication first = cache.forToken("token-1");

        final Authentication second = cache.forToken("token-2");

        assertSoftly(softly -> {
            softly.assertThat(second).isNotSameAs(first);
            softly.assertThat(second.value()).isEqualTo("token-2");
        });
    }

    @Test
    void forToken_shouldThrow_whenTokenIsNull() {
        final BearerAuthenticationCache cache = new BearerAuthenticationCache();

        final Throwable thrown = catchThrowable(() -> cache.forToken(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("token");
    }
}
