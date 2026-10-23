package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpMethodTest {

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PUT", "PATCH"})
    void usesEmptyBodyWhenAbsent_shouldBeTrue_whenMethodHasEmptyBody(final HttpMethod method) {
        assertThat(method.isEmptyBodySentWhenAbsent()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PUT", "PATCH"},
            mode = EnumSource.Mode.EXCLUDE)
    void usesEmptyBodyWhenAbsent_shouldBeFalse_whenMethodOmitsBody(final HttpMethod method) {
        assertThat(method.isEmptyBodySentWhenAbsent()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"GET", "HEAD"})
    void isBodyless_shouldBeTrue_whenMethodRefusesBody(final HttpMethod method) {
        assertThat(method.isBodyless()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"GET", "HEAD"},
            mode = EnumSource.Mode.EXCLUDE)
    void isBodyless_shouldBeFalse_whenMethodMayCarryBody(final HttpMethod method) {
        assertThat(method.isBodyless()).isFalse();
    }
}
