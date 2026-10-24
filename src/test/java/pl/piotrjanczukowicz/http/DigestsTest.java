package pl.piotrjanczukowicz.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Constructor;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class DigestsTest {

    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void constructor_shouldThrowUnsupported_whenCalledViaReflection() throws Exception {
        final Constructor<Digests> constructor = Digests.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keyedSha256Hex_shouldBeStableAndDifferFromPlainDigest_whenSameValue() {
        final String first = Digests.keyedSha256Hex("abc");
        final String second = Digests.keyedSha256Hex("abc");

        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(second).hasSize(64);
            softly.assertThat(first).isNotEqualTo(SHA256_OF_ABC);
            softly.assertThat(Digests.keyedSha256Hex("abd")).isNotEqualTo(first);
        });
    }

    @Test
    void keyedSha256Hex_shouldThrow_whenValueNull() {
        final Throwable thrown = catchThrowable(() -> Digests.keyedSha256Hex(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void keyedHex_shouldThrowIllegalState_whenAlgorithmUnknown() {
        final Throwable thrown = catchThrowable(() -> Digests.keyedHex("x", "NO-SUCH-MAC"));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO-SUCH-MAC")
                .hasCauseInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void newKey_shouldThrowIllegalState_whenAlgorithmUnknown() {
        final Throwable thrown = catchThrowable(() -> Digests.newKey("NO-SUCH-KEY"));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO-SUCH-KEY")
                .hasCauseInstanceOf(NoSuchAlgorithmException.class);
    }
}
