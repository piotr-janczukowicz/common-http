package pl.piotrjanczukowicz.http;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNotBlank;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A {@code multipart/form-data} request entity (RFC 7578): text fields and files, encoded in memory.
 *
 * <p>Send it with the caller: the {@link #body()} is the entity and the {@link #contentType()} names the boundary that
 * separates the parts, so both belong on the same request. Names and file names have {@code "}, {@code CR} and
 * {@code LF} percent-encoded as the HTML form encoding does.
 */
public final class MultipartFormData {

    private static final String CRLF = "\r\n";
    private static final String DELIMITER = "--";
    private static final String NAME = "name";
    private static final Pattern BOUNDARY = Pattern.compile("[0-9A-Za-z'()+_,./:=?-]{1,70}");

    private final String boundary;
    private final HttpBody body;

    private MultipartFormData(final String boundary, final HttpBody body) {
        this.boundary = boundary;
        this.body = body;
    }

    /**
     * Starts a form without parts.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The boundary between the parts.
     *
     * @return boundary, 1 to 70 characters of the RFC 2046 set
     */
    public String boundary() {
        return boundary;
    }

    /**
     * Value of the {@code Content-Type} header of the request.
     *
     * @return {@code multipart/form-data} with the boundary parameter
     */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /**
     * The encoded entity.
     *
     * @return body holding every part
     */
    public HttpBody body() {
        return body;
    }

    private static void write(final ByteArrayOutputStream out, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    /** Collects the parts of a {@link MultipartFormData}. */
    public static final class Builder {

        private final List<Part> parts = new ArrayList<>();

        private Builder() {
            // Created by MultipartFormData.builder().
        }

        /**
         * Adds a text field, encoded as UTF-8.
         *
         * @param name field name
         * @param value field value
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank
         */
        public Builder text(final String name, final String value) {
            requireNotBlank(name, NAME);
            requireNonNull(value, "value");
            parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        /**
         * Adds a file. The content is copied.
         *
         * @param name field name
         * @param filename file name sent to the server
         * @param contentType media type of the content
         * @param content file content
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if {@code name}, {@code filename} or {@code contentType} is blank, or
         *     {@code contentType} holds a line break
         */
        public Builder file(final String name, final String filename, final String contentType, final byte[] content) {
            requireNotBlank(name, NAME);
            requireNotBlank(filename, "filename");
            requireNotBlank(contentType, "contentType");
            requireNonNull(content, "content");
            if (contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("contentType must not hold a line break");
            }
            parts.add(new Part(name, filename, contentType, content.clone()));
            return this;
        }

        /**
         * Encodes the parts with a random boundary (a UUID, so a part never contains it in practice).
         *
         * @return the form
         * @throws IllegalStateException if no part was added
         */
        public MultipartFormData build() {
            return build(randomBoundary());
        }

        /**
         * Encodes the parts with the given boundary.
         *
         * @param boundary boundary, 1 to 70 characters of {@code 0-9A-Za-z'()+_,-./:=?}
         * @return the form
         * @throws NullPointerException if {@code boundary} is {@code null}
         * @throws IllegalArgumentException if {@code boundary} is not valid or occurs in a part
         * @throws IllegalStateException if no part was added
         */
        public MultipartFormData build(final String boundary) {
            requireNonNull(boundary, "boundary");
            if (!BOUNDARY.matcher(boundary).matches()) {
                throw new IllegalArgumentException("boundary must be 1 to 70 characters of [0-9A-Za-z'()+_,-./:=?]");
            }
            requireParts();
            if (isInAnyPart(boundary)) {
                throw new IllegalArgumentException("boundary occurs in a part");
            }
            return encode(boundary);
        }

        private void requireParts() {
            if (parts.isEmpty()) {
                throw new IllegalStateException("a multipart form needs at least one part");
            }
        }

        private static String randomBoundary() {
            return "----" + UUID.randomUUID().toString().replace("-", "");
        }

        private boolean isInAnyPart(final String boundary) {
            final String marker = DELIMITER + boundary;
            return parts.stream().anyMatch(part -> part.contains(marker));
        }

        private MultipartFormData encode(final String boundary) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (final Part part : parts) {
                part.writeTo(out, boundary);
            }
            write(out, DELIMITER + boundary + DELIMITER + CRLF);
            return new MultipartFormData(boundary, HttpBody.of(out.toByteArray()));
        }
    }

    private record Part(
            String name,
            @Nullable String filename,
            @Nullable String contentType,
            byte[] content) {

        boolean contains(final String marker) {
            return new String(content, StandardCharsets.ISO_8859_1).contains(marker);
        }

        void writeTo(final ByteArrayOutputStream out, final String boundary) {
            write(out, DELIMITER + boundary + CRLF + headers() + CRLF);
            out.write(content, 0, content.length);
            write(out, CRLF);
        }

        private String headers() {
            final String disposition = "Content-Disposition: form-data; name=\"" + escaped(name) + "\""
                    + (filename == null ? "" : "; filename=\"" + escaped(filename) + "\"") + CRLF;
            return disposition + (contentType == null ? "" : "Content-Type: " + contentType + CRLF);
        }

        private static String escaped(final String text) {
            return text.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
        }
    }
}
