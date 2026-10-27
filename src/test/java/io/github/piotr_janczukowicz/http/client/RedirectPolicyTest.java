package io.github.piotr_janczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class RedirectPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "Proxy-Authorization", "COOKIE"})
    void constructor_shouldThrow_whenCrossOriginHeaderCarriesCredentials(final String name) {
        final Set<HttpMethod> methods = Set.of(HttpMethod.GET);
        final Set<String> names = Set.of("Accept", name);

        final Throwable thrown = catchThrowable(() -> new RedirectPolicy(5, methods, names));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("crossOriginHeaderNames must not contain " + name.toLowerCase(Locale.ROOT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accept", "User-Agent", "X-Api-Version"})
    void constructor_shouldKeepHeaderNameLowerCase_whenItIsNotACredential(final String name) {
        final RedirectPolicy policy = new RedirectPolicy(5, Set.of(HttpMethod.GET), Set.of(name));

        assertThat(policy.crossOriginHeaderNames()).containsExactly(name.toLowerCase(Locale.ROOT));
    }
}
