package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpHeadersValidatorTest {

    private static final String NOT_A_TOKEN = "header name is not an RFC 9110 token";
    private static final String BAD_VALUE = "header value contains a forbidden character";

    @Test
    void requireValidRequestHeader_shouldAccept_whenNameAndValueValid() {
        final Throwable thrown =
                catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("X-Test", "value"));

        assertThat(thrown).isNull();
    }

    @Test
    void requireValidRequestHeader_shouldThrow_whenNameEmpty() {
        final Throwable thrown = catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("", "value"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(NOT_A_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X Test", "X:Test", "X/Test", "[", "{", "X\u0080", "Xé"})
    void requireValidRequestHeader_shouldThrow_whenNameNotToken(final String name) {
        final Throwable thrown = catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader(name, "value"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(NOT_A_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "!", "#", "$", "%", "&", "'", "*", "+", "-", ".", "^", "_", "`", "|", "~", "0", "9", "A", "Z", "a", "z"
            })
    void requireValidRequestHeader_shouldAccept_whenNameUsesTokenCharacter(final String name) {
        final Throwable thrown = catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader(name, "value"));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\rb", "a\nb", "a\u0000b", "a\u007Fb", "aéb"})
    void requireValidRequestHeader_shouldThrow_whenValueHasForbiddenCharacter(final String value) {
        final Throwable thrown = catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("X-Test", value));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_VALUE);
    }

    @Test
    void requireValidRequestHeader_shouldAccept_whenValueHasTabOrSpace() {
        final Throwable thrown =
                catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("X-Test", "a\tb c"));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Content-Length",
                "content-length",
                "CONTENT-LENGTH",
                "Transfer-Encoding",
                "transfer-encoding",
                "Upgrade",
                "upgrade"
            })
    void requireValidRequestHeader_shouldThrow_whenNameIsFramingOrUpgrade(final String name) {
        final Throwable thrown = catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader(name, "value"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("header name is forbidden: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"upgrade", "UPGRADE", "keep-alive, Upgrade", "Keep-Alive, upgrade, close"})
    void requireValidRequestHeader_shouldThrow_whenConnectionListsUpgrade(final String value) {
        final Throwable thrown =
                catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("Connection", value));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header value is forbidden: Connection must not list upgrade");
    }

    @Test
    void requireValidRequestHeader_shouldAccept_whenConnectionIsClose() {
        final Throwable thrown =
                catchThrowable(() -> HttpHeadersValidator.requireValidRequestHeader("Connection", "close"));

        assertThat(thrown).isNull();
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<HttpHeadersValidator> constructor = HttpHeadersValidator.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
