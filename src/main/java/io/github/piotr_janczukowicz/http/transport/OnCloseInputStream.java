package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passes reads through and runs a hook when the stream is closed, even if closing the wrapped stream fails.
 *
 * <p>The hook runs at most once: a second {@link #close()} closes the wrapped stream again but does not repeat the
 * hook.
 */
final class OnCloseInputStream extends FilterInputStream {

    private final Runnable onClose;
    private final AtomicBoolean isClosed = new AtomicBoolean();

    OnCloseInputStream(final InputStream in, final Runnable onClose) {
        super(requireNonNull(in, "in"));
        this.onClose = requireNonNull(onClose, "onClose");
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        return in.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            if (isClosed.compareAndSet(false, true)) {
                onClose.run();
            }
        }
    }
}
