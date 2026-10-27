package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@UnitTest
class OnCloseInputStreamTest {

    @Test
    void close_shouldRunHookOnce_whenClosedTwice() throws IOException {
        final AtomicInteger runs = new AtomicInteger();
        final OnCloseInputStream stream =
                new OnCloseInputStream(new ByteArrayInputStream(new byte[0]), runs::incrementAndGet);

        stream.close();
        stream.close();

        assertThat(runs).hasValue(1);
    }

    @Test
    void close_shouldRunHookAndRethrow_whenWrappedCloseFails() {
        final AtomicInteger runs = new AtomicInteger();
        final InputStream failing = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        final OnCloseInputStream stream = new OnCloseInputStream(failing, runs::incrementAndGet);

        final Throwable thrown = catchThrowable(stream::close);

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IOException.class).hasMessage("close failed");
            softly.assertThat(runs).hasValue(1);
        });
    }

    @Test
    void read_shouldPassBytesThrough_whenBufferGiven() throws IOException {
        final OnCloseInputStream stream =
                new OnCloseInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), () -> {});
        final byte[] buffer = new byte[3];

        final int read = stream.read(buffer, 0, 3);

        assertSoftly(softly -> {
            softly.assertThat(read).isEqualTo(3);
            softly.assertThat(buffer).containsExactly(1, 2, 3);
        });
    }

    @Test
    void constructor_shouldThrow_whenHookIsNull() {
        final InputStream in = new ByteArrayInputStream(new byte[0]);

        final Throwable thrown = catchThrowable(() -> new OnCloseInputStream(in, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("onClose");
    }
}
