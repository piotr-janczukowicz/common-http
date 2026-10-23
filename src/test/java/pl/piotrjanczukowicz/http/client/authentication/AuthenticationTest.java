package pl.piotrjanczukowicz.http.client.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.Digests;
import pl.piotrjanczukowicz.http.HttpHeaderNames;
import pl.piotrjanczukowicz.http.client.authentication.Authentication.Type;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class AuthenticationTest {

    @Test
    void ofBearer_shouldHoldTokenAndBearerType_whenTokenGiven() {
        final Authentication authentication = Authentication.ofBearer("tok_abc");

        assertSoftly(softly -> {
            softly.assertThat(authentication.type()).isEqualTo(Type.BEARER);
            softly.assertThat(authentication.value()).isEqualTo("tok_abc");
            softly.assertThat(authentication.headerValue()).isEqualTo("Bearer tok_abc");
        });
    }

    @Test
    void ofBasic_shouldEncodeUserAndPasswordAsBase64_whenBothGiven() {
        final Authentication authentication = Authentication.ofBasic("app1.abc", "s3cr3t");

        assertSoftly(softly -> {
            softly.assertThat(authentication.type()).isEqualTo(Type.BASIC);
            softly.assertThat(authentication.value()).isEqualTo("YXBwMS5hYmM6czNjcjN0");
            softly.assertThat(authentication.headerValue()).isEqualTo("Basic YXBwMS5hYmM6czNjcjN0");
        });
    }

    @Test
    void ofBasic_shouldEncodeAsUtf8_whenPasswordHasNonAsciiCharacters() {
        final Authentication authentication = Authentication.ofBasic("user", "pässword");

        assertThat(authentication.value()).isEqualTo("dXNlcjpww6Rzc3dvcmQ=");
    }

    @Test
    void ofBasic_shouldThrowWithoutPassword_whenUserContainsColon() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBasic("a:b", "s3cr3t"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user")
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void ofBasic_shouldThrow_whenUserBlank() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBasic(" ", "s3cr3t"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("s3cr3t");
    }

    @Test
    void ofBasic_shouldThrow_whenPasswordBlank() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBasic("user", " "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password");
    }

    @Test
    void ofBasic_shouldThrow_whenUserNull() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBasic(null, "p"));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofBasic_shouldThrow_whenPasswordNull() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBasic("u", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofBearer_shouldThrow_whenTokenBlank() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBearer(" "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofBearer_shouldThrow_whenTokenNull() {
        final Throwable thrown = catchThrowable(() -> Authentication.ofBearer(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldThrow_whenTypeNull() {
        final Throwable thrown = catchThrowable(() -> new Authentication(null, "v", "id"));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void constructor_shouldThrow_whenIdentityBlank() {
        final Throwable thrown = catchThrowable(() -> new Authentication(Type.BEARER, "v", " "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
    }

    @Test
    void ofBearer_shouldUseKeyedDigestOfValueAsIdentity_whenCreated() {
        final Authentication authentication = Authentication.ofBearer("abc");

        assertThat(authentication.identity()).isEqualTo(Digests.keyedSha256Hex("abc"));
    }

    @Test
    void withIdentity_shouldKeepCredentialAndReplaceIdentity_whenNamed() {
        final Authentication authentication = Authentication.ofBearer("abc").withIdentity("app:1");

        assertSoftly(softly -> {
            softly.assertThat(authentication.identity()).isEqualTo("app:1");
            softly.assertThat(authentication.value()).isEqualTo("abc");
            softly.assertThat(authentication.type()).isEqualTo(Type.BEARER);
        });
    }

    @Test
    void parse_shouldReadBearer_whenSchemeIsInAnyCase() {
        final Optional<Authentication> parsed = Authentication.parse("bEaReR abc");

        assertThat(parsed).contains(Authentication.ofBearer("abc"));
    }

    @Test
    void parse_shouldReadBasic_whenHeaderIsBasic() {
        final Optional<Authentication> parsed = Authentication.parse("Basic YXBwMS5hYmM6czNjcjN0");

        assertThat(parsed).contains(Authentication.ofBasic("app1.abc", "s3cr3t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Digest abc", "Bearer", "Bearer   "})
    void parse_shouldBeEmpty_whenHeaderCannotBeRead(final String header) {
        final Optional<Authentication> parsed = Authentication.parse(header);

        assertThat(parsed).isEmpty();
    }

    @Test
    void parse_shouldThrow_whenNull() {
        final Throwable thrown = catchThrowable(() -> Authentication.parse(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toString_shouldShowSchemeOnly_whenCalled() {
        final Authentication bearer = Authentication.ofBearer("tok_secret_token_value");
        final Authentication basic = Authentication.ofBasic("id", "secret_value_x");

        assertSoftly(softly -> {
            softly.assertThat(bearer).hasToString("Authentication[Bearer]");
            softly.assertThat(basic).hasToString("Authentication[Basic]");
        });
    }

    @Test
    void scheme_shouldMatchConstants_whenTypesListed() {
        assertSoftly(softly -> {
            softly.assertThat(HttpHeaderNames.AUTHORIZATION).isEqualTo(AUTHORIZATION);
            softly.assertThat(Type.BASIC.scheme()).isEqualTo(Authentication.BASIC);
            softly.assertThat(Type.BEARER.scheme()).isEqualTo(Authentication.BEARER);
        });
    }
}
