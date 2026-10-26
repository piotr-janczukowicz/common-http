package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.BoundedReadLimitException;
import io.github.piotr_janczukowicz.util.IoUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

@UnitTest
class BoundedBodyReadersTest {

    @Test
    void readAll_shouldReturnAllBytes_whenBodyIsWithinLimit() throws Exception {
        final InputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3});

        final byte[] read = BoundedBodyReaders.readAll(body, 3, -1);

        assertThat(read).containsExactly(1, 2, 3);
    }

    @Test
    void readAll_shouldReturnAllBytes_whenSizeHintIsGiven() throws Exception {
        final InputStream body = new ByteArrayInputStream(new byte[] {4, 5});

        final byte[] read = BoundedBodyReaders.readAll(body, 16, 2);

        assertThat(read).containsExactly(4, 5);
    }

    @Test
    void readAll_shouldThrowResponseTooLargeWithLimitCause_whenBodyExceedsLimit() {
        final InputStream body = new ByteArrayInputStream(new byte[20_001]);

        final Throwable thrown = catchThrowable(() -> BoundedBodyReaders.readAll(body, 20_000, -1));

        assertSoftly(softly -> {
            softly.assertThat(thrown)
                    .isInstanceOf(ResponseTooLargeException.class)
                    .hasMessageContaining("20000 bytes");
            softly.assertThat(thrown).hasCauseInstanceOf(BoundedReadLimitException.class);
            softly.assertThat(thrown.getCause())
                    .isInstanceOfSatisfying(
                            BoundedReadLimitException.class,
                            cause -> softly.assertThat(cause.limit()).isEqualTo(20_000L));
            softly.assertThat(thrown)
                    .isInstanceOfSatisfying(
                            ResponseTooLargeException.class,
                            tooLarge -> softly.assertThat(tooLarge.maxBytes()).isEqualTo(20_000L));
        });
    }

    @Test
    void readAll_shouldClampToZeroAndRefuseAnyByte_whenMaxBytesIsNegative() {
        final InputStream body = new ByteArrayInputStream(new byte[] {7});

        final Throwable thrown = catchThrowable(() -> BoundedBodyReaders.readAll(body, -1, -1));

        assertThat(thrown)
                .isInstanceOfSatisfying(
                        ResponseTooLargeException.class,
                        tooLarge -> assertThat(tooLarge.maxBytes()).isZero());
    }

    @Test
    void readAll_shouldThrowNullPointerException_whenStreamIsNull() {
        final Throwable thrown = catchThrowable(() -> BoundedBodyReaders.readAll(null, 1, -1));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("in");
    }

    @Test
    void maxBufferBytes_shouldMatchIoUtilsLimit_whenRead() {
        final long maxBufferBytes = IoUtils.MAX_BUFFER_BYTES;

        assertThat(maxBufferBytes).isEqualTo(Integer.MAX_VALUE - 8L);
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<BoundedBodyReaders> constructor = BoundedBodyReaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
