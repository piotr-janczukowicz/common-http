package pl.piotrjanczukowicz.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import org.jspecify.annotations.Nullable;

/**
 * Turns the body of an unsuccessful response into bounded text for an exception message and raw body, so that a huge
 * error page never becomes a huge string.
 */
public final class HttpErrorBodies {

    /** Most characters of a response body that an exception keeps as its raw body. */
    public static final int MAX_RAW_BODY_CHARS = 65_536;

    /**
     * How many leading bytes of an error body are read to recognise a rate limit. The rate-limit classification looks
     * no further, and the retry layer keeps no more of a rejection that it stores.
     */
    public static final int ERROR_BODY_SCAN_BYTES = 8192;

    private static final int MAX_MESSAGE_CHARS = 1024;
    private static final String TRUNCATION_MARKER = "... [truncated]";

    private HttpErrorBodies() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * {@code text} cut to 1024 characters with an ellipsis marker when it was longer, never splitting a surrogate pair.
     * For exception messages built from a response body.
     *
     * @param text message text
     * @return {@code text}, or its truncated form
     */
    public static String truncated(final String text) {
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        int end = MAX_MESSAGE_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATION_MARKER;
    }

    /**
     * {@code body} cut to {@link #MAX_RAW_BODY_CHARS} characters, never splitting a surrogate pair. An error body can
     * be as large as the transport's response limit, and an exception should not pin that much memory.
     *
     * @param body body text, or {@code null}
     * @return {@code body}, or its cut form
     */
    public static @Nullable String cappedBody(final @Nullable String body) {
        return body == null ? null : capped(body);
    }

    /**
     * Decodes at most {@value #MAX_RAW_BODY_CHARS} characters of {@code bytes}; the rest is never turned into text.
     * Malformed input is replaced, not rejected.
     *
     * @param bytes response body
     * @param charset charset to decode with
     * @return the leading part of the body as text
     */
    public static String decodeBounded(final byte[] bytes, final Charset charset) {
        if (bytes.length == 0) {
            return "";
        }
        final CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // No byte yields more than maxCharsPerByte characters, so a short body does not need the full cap.
        final int capacity =
                (int) Math.min(MAX_RAW_BODY_CHARS, Math.ceil(bytes.length * (double) decoder.maxCharsPerByte()));
        final CharBuffer text = CharBuffer.allocate(capacity);
        // An OVERFLOW result just means the body is longer than the cap; what fits is kept.
        if (!decoder.decode(ByteBuffer.wrap(bytes), text, true).isOverflow()) {
            decoder.flush(text);
        }
        text.flip();
        return capped(text.toString());
    }

    private static String capped(final String body) {
        if (body.length() <= MAX_RAW_BODY_CHARS) {
            return body;
        }
        int end = MAX_RAW_BODY_CHARS;
        if (Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(0, end);
    }
}
