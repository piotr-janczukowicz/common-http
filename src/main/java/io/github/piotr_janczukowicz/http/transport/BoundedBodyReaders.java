package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.util.BoundedReadLimitException;
import io.github.piotr_janczukowicz.util.IoUtils;
import java.io.IOException;
import java.io.InputStream;

/**
 * Buffers a response stream without letting it grow past a byte limit. Used by the transport of this package.
 *
 * <p>Delegates to {@link IoUtils#readAllBounded(InputStream, long, long)} and translates its
 * {@link BoundedReadLimitException} into a {@link ResponseTooLargeException} with the original as its cause.
 */
final class BoundedBodyReaders {

    private BoundedBodyReaders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads {@code in} to its end, sizing the buffer from {@code expectedLength}; see
     * {@link IoUtils#readAllBounded(InputStream, long, long)}.
     *
     * <p>A negative {@code maxBytes} is clamped to {@code 0} here, so this reader keeps its earlier behavior.
     * {@link IoUtils} itself still rejects negative limits.
     *
     * @param in stream to drain; not closed
     * @param maxBytes most bytes accepted; a negative value is treated as {@code 0}, so any byte is refused; capped at
     *     {@link IoUtils#MAX_BUFFER_BYTES}
     * @param expectedLength announced body length, or a negative value when unknown; bounded by the limit
     * @return all bytes read
     * @throws ResponseTooLargeException if more than the effective limit of bytes are available
     * @throws IOException if reading fails
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public static byte[] readAll(final InputStream in, final long maxBytes, final long expectedLength)
            throws IOException {
        requireNonNull(in, "in");
        try {
            return IoUtils.readAllBounded(in, Math.max(maxBytes, 0L), expectedLength);
        } catch (final BoundedReadLimitException exceeded) {
            throw toResponseTooLarge(exceeded);
        }
    }

    private static ResponseTooLargeException toResponseTooLarge(final BoundedReadLimitException exceeded) {
        final ResponseTooLargeException tooLarge = new ResponseTooLargeException(exceeded.limit());
        tooLarge.initCause(exceeded);
        return tooLarge;
    }
}
