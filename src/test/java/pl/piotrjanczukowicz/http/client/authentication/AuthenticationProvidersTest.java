package pl.piotrjanczukowicz.http.client.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.piotrjanczukowicz.http.Digests;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.client.HttpCall;
import pl.piotrjanczukowicz.http.client.HttpClient;
import pl.piotrjanczukowicz.http.client.HttpClientConfiguration;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.test.http.HttpResponses;
import pl.piotrjanczukowicz.test.http.HttpTransportMocks;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class AuthenticationProvidersTest {

    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void bearer_shouldSendBearer_whenCreated() {
        final AuthenticationProvider auth = AuthenticationProviders.bearer("gh-token");

        assertSoftly(softly -> {
            softly.assertThat(auth.authentication().map(Authentication::headerValue))
                    .contains("Bearer gh-token");
            softly.assertThat(auth.toString()).doesNotContain("gh-token");
        });
    }

    @Test
    void bearer_shouldThrow_whenBlank() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.bearer("  "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("token must not be blank");
    }

    @Test
    void bearerSupplier_shouldDelegate_whenCalled() {
        final AtomicReference<String> token = new AtomicReference<>("first");
        final AuthenticationProvider auth = AuthenticationProviders.bearer(token::get);

        final Optional<Authentication> first = auth.authentication();
        token.set("second");
        final Optional<Authentication> second = auth.authentication();

        assertSoftly(softly -> {
            softly.assertThat(first.map(Authentication::headerValue)).contains("Bearer first");
            softly.assertThat(second.map(Authentication::headerValue)).contains("Bearer second");
            softly.assertThat(auth.toString()).doesNotContain("first");
        });
    }

    @Test
    void bearerSupplier_shouldThrow_whenNull() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.bearer((Supplier<String>) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("tokens");
    }

    @Test
    void authentication_shouldThrow_whenSupplierReturnsBlank() {
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "  ");

        final Throwable thrown = catchThrowable(auth::authentication);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("token must not be blank");
    }

    @Test
    void isRenewableAfterRejection_shouldBeFalse_whenAuthIsFixedOrSupplied() {
        final Authentication rejected = Authentication.ofBearer("tok_x");

        assertSoftly(softly -> {
            softly.assertThat(AuthenticationProviders.bearer("tok_x").isRenewableAfterRejection(rejected))
                    .isFalse();
            softly.assertThat(AuthenticationProviders.bearer(() -> "tok_x").isRenewableAfterRejection(rejected))
                    .isFalse();
        });
    }

    @Test
    void identity_shouldBeKeyedDigestOfToken_whenDefault() {
        final Optional<String> identity =
                AuthenticationProviders.bearer("abc").authentication().map(Authentication::identity);

        assertSoftly(softly -> {
            softly.assertThat(identity).contains(Digests.keyedSha256Hex("abc"));
            softly.assertThat(identity).isNotEqualTo(Optional.of(SHA256_OF_ABC));
        });
    }

    @Test
    void identity_shouldNotContainToken_whenDefault() {
        final String identity = AuthenticationProviders.bearer("tok_secret_value")
                .authentication()
                .map(Authentication::identity)
                .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(identity).matches("[0-9a-f]{64}");
            softly.assertThat(identity).doesNotContain("tok_secret_value");
        });
    }

    @Test
    void identity_shouldDiffer_whenTokenDiffers() {
        final Optional<String> first =
                AuthenticationProviders.bearer("gh-token-a").authentication().map(Authentication::identity);
        final Optional<String> second =
                AuthenticationProviders.bearer("gh-token-b").authentication().map(Authentication::identity);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void identity_shouldMatch_whenSameTokenInTwoProviders() {
        final Optional<String> first =
                AuthenticationProviders.bearer("same-token").authentication().map(Authentication::identity);
        final Optional<String> second =
                AuthenticationProviders.bearer("same-token").authentication().map(Authentication::identity);

        assertThat(second).isPresent().isEqualTo(first);
    }

    @Test
    void identity_shouldFollowRotatedToken_whenSupplied() {
        final AtomicReference<String> token = new AtomicReference<>("first");
        final AuthenticationProvider auth = AuthenticationProviders.bearer(token::get);
        final Optional<String> before = auth.authentication().map(Authentication::identity);

        token.set("second");

        assertThat(auth.authentication().map(Authentication::identity))
                .isNotEqualTo(before)
                .isEqualTo(AuthenticationProviders.bearer("second")
                        .authentication()
                        .map(Authentication::identity));
    }

    @Test
    void basic_shouldSendBasic_whenCreated() {
        final AuthenticationProvider auth = AuthenticationProviders.basic("app1.abc", "s3cr3t");

        assertThat(auth.authentication().map(Authentication::headerValue)).contains("Basic YXBwMS5hYmM6czNjcjN0");
    }

    @Test
    void basic_shouldThrowWithoutSecret_whenClientIdBlank() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.basic(" ", "s3cr3t"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("s3cr3t");
    }

    @Test
    void basic_shouldThrow_whenSecretBlank() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.basic("app1.abc", " "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void basic_shouldThrowWithoutSecret_whenClientIdContainsColon() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.basic("app1:abc", "s3cr3t"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("s3cr3t");
    }

    @Test
    void basic_shouldShowOnlyClientId_whenToString() {
        final String text = AuthenticationProviders.basic("app1.abc", "s3cr3t").toString();

        assertThat(text).contains("app1.abc").doesNotContain("s3cr3t").doesNotContain("SXYx");
    }

    @Test
    void basic_shouldUseClientIdIdentity_whenCreated() {
        final AuthenticationProvider auth = AuthenticationProviders.basic("app1.abc", "s3cr3t");

        assertThat(auth.authentication().map(Authentication::identity)).contains("basic:app1.abc");
    }

    @Test
    void basic_shouldNotBeRenewable_whenRejected() {
        final AuthenticationProvider auth = AuthenticationProviders.basic("app1.abc", "s3cr3t");

        final boolean isRenewable = auth.isRenewableAfterRejection(Authentication.ofBasic("app1.abc", "s3cr3t"));

        assertThat(isRenewable).isFalse();
    }

    @Test
    void bearerSupplier_shouldShowFactoryNameWithoutToken_whenToString() {
        final String text = AuthenticationProviders.bearer(() -> "tok-9f3a").toString();

        assertSoftly(softly -> {
            softly.assertThat(text).isEqualTo("AuthenticationProviders.bearer(supplier)");
            softly.assertThat(text).doesNotContain("tok-9f3a");
        });
    }

    @Test
    void bearerRenewable_shouldSendCurrentToken_whenSupplierRotates() {
        final AtomicReference<String> token = new AtomicReference<>("first");
        final AuthenticationProvider auth = AuthenticationProviders.bearer(token::get, rejected -> true);

        final Optional<Authentication> first = auth.authentication();
        token.set("second");
        final Optional<Authentication> second = auth.authentication();

        assertSoftly(softly -> {
            softly.assertThat(first.map(Authentication::headerValue)).contains("Bearer first");
            softly.assertThat(second.map(Authentication::headerValue)).contains("Bearer second");
        });
    }

    @Test
    void bearerRenewable_shouldThrow_whenTokensNull() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.bearer(null, rejected -> true));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("tokens");
    }

    @Test
    void bearerRenewable_shouldThrow_whenPredicateNull() {
        final Throwable thrown = catchThrowable(() -> AuthenticationProviders.bearer(() -> "tok_x", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("isRenewableAfterRejection");
    }

    @Test
    void bearerRenewable_shouldBeTrue_whenPredicateAccepts() {
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "tok_new", rejected -> true);

        final boolean isRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer("tok_old"));

        assertThat(isRenewable).isTrue();
    }

    @Test
    void bearerRenewable_shouldBeFalse_whenPredicateRejects() {
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "tok_new", rejected -> false);

        final boolean isRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer("tok_old"));

        assertThat(isRenewable).isFalse();
    }

    @Test
    void bearerRenewable_shouldPassRejectedTokenValue_whenRenewableChecked() {
        final AtomicReference<String> received = new AtomicReference<>();
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "tok_new", token -> {
            received.set(token);
            return true;
        });

        final boolean isRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer("tok_old"));

        assertSoftly(softly -> {
            softly.assertThat(isRenewable).isTrue();
            softly.assertThat(received.get()).isEqualTo("tok_old");
        });
    }

    @Test
    void bearerRenewable_shouldShowFactoryNameWithoutToken_whenToString() {
        final String text = AuthenticationProviders.bearer(() -> "tok-renew-7b1e", rejected -> true)
                .toString();

        assertSoftly(softly -> {
            softly.assertThat(text).isEqualTo("AuthenticationProviders.bearer(supplier, predicate)");
            softly.assertThat(text).doesNotContain("tok-renew-7b1e");
        });
    }

    @Test
    void none_shouldSendNoHeader_whenCreated() {
        final AuthenticationProvider auth = AuthenticationProviders.none();

        assertSoftly(softly -> {
            softly.assertThat(auth.authentication()).isEmpty();
            softly.assertThat(auth.toString()).isEqualTo("AuthenticationProviders.none");
        });
    }

    @Test
    void none_shouldSendAnonymousCredentialKey_whenRequestSent() throws IOException {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(HttpResponses.of(HttpStatus.OK.code(), Map.of(), HttpBody.absent()));
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);

        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.of(URI.create("https://api.example.test/")))
                .auth(AuthenticationProviders.none())
                .build()) {
            client.execute(HttpCall.get("/user"));
        }

        verify(transport).execute(captor.capture());
        assertThat(captor.getValue().credentialKey()).isEqualTo("anonymous");
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<AuthenticationProviders> constructor = AuthenticationProviders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bearerSupplier_shouldReuseAuthentication_whenTokenUnchanged() {
        final AtomicReference<String> token = new AtomicReference<>("first");
        final AuthenticationProvider auth = AuthenticationProviders.bearer(token::get);

        final Authentication first = auth.authentication().orElseThrow();
        final Authentication again = auth.authentication().orElseThrow();
        token.set("second");
        final Authentication rotated = auth.authentication().orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(again).isSameAs(first);
            softly.assertThat(rotated).isNotSameAs(first);
            softly.assertThat(rotated.value()).isEqualTo("second");
        });
    }

    @Test
    void bearerRenewable_shouldReuseAuthentication_whenTokenUnchanged() {
        final AtomicReference<String> token = new AtomicReference<>("first");
        final AuthenticationProvider auth = AuthenticationProviders.bearer(token::get, rejected -> true);

        final Authentication first = auth.authentication().orElseThrow();
        final Authentication again = auth.authentication().orElseThrow();
        token.set("second");
        final Authentication rotated = auth.authentication().orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(again).isSameAs(first);
            softly.assertThat(rotated).isNotSameAs(first);
            softly.assertThat(rotated.value()).isEqualTo("second");
        });
    }
}
