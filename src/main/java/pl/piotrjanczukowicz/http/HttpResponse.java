package pl.piotrjanczukowicz.http;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ETAG;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.piotrjanczukowicz.util.DateTimeUtils;

/**
 * An inbound HTTP response: status, headers, and body.
 *
 * <p>Null {@code headers} become empty. Null {@code body} becomes {@link HttpBody#absent()}. {@code statusCode} must be
 * a valid HTTP status.
 *
 * @param statusCode HTTP status code
 * @param headers response headers
 * @param body response body, possibly absent
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "HttpHeaders is immutable")
public record HttpResponse(int statusCode, HttpHeaders headers, HttpBody body) {

    private static final Logger LOG = LoggerFactory.getLogger(HttpResponse.class);
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([^\\s;\"]+)\"?");

    public HttpResponse(final int statusCode, final @Nullable HttpHeaders headers, final @Nullable HttpBody body) {
        HttpStatus.requireValid(statusCode);
        this.statusCode = statusCode;
        this.headers = headers == null ? HttpHeaders.empty() : headers;
        this.body = body == null ? HttpBody.absent() : body;
    }

    /**
     * Value of header {@code name}, trimmed, when it is not blank. A header the server sent several times is one value
     * with the parts joined by {@code ", "}; see {@link HttpHeaders#fromWire}.
     *
     * @param name header name
     * @return header value, or empty
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<String> header(final String name) {
        requireNonNull(name, "name");

        return headers.get(name).map(String::trim).filter(value -> !value.isEmpty());
    }

    /**
     * {@code ETag} header, to send back as {@code If-None-Match}.
     *
     * <p>Optional: the client already sends conditional requests from its in-memory cache, and caller-managed ETags are
     * useful only to keep validators across process restarts.
     *
     * @return entity tag as sent, including quotes and any {@code W/} prefix, or empty
     */
    public Optional<String> etag() {
        return header(ETAG);
    }

    /**
     * Reads the {@code Date} header: the server's clock at the time it answered.
     *
     * @return instant, or empty when the header is absent or is not an RFC 1123 date; the obsolete RFC 850 and asctime
     *     forms are not read
     */
    public Optional<Instant> serverDate() {
        return header(DATE).flatMap(DateTimeUtils::parseRfc1123);
    }

    /**
     * Reads the {@code Retry-After} header: a delay in seconds, or an RFC 1123 HTTP-date. An HTTP-date is measured
     * against the {@code Date} header of this response when that parses, so a local clock that is off does not change
     * the wait, and against {@code clock} otherwise.
     *
     * @param clock clock used when the header is an HTTP-date and the response has no usable {@code Date}
     * @return delay, negative for a negative number or a date in the past, or empty when the header is absent or is
     *     neither seconds nor an RFC 1123 date (the obsolete RFC 850 and asctime dates are not read); an ignored value
     *     is logged at debug level
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public Optional<Duration> retryAfter(final Clock clock) {
        requireNonNull(clock, "clock");
        return header(RETRY_AFTER)
                .flatMap(value -> parseRetryAfter(value, () -> serverDate().orElseGet(clock::instant)));
    }

    /**
     * Whether {@code status} matches {@link #statusCode()}.
     *
     * @param status expected status
     * @return {@code true} if the codes match
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public boolean hasStatus(final HttpStatus status) {
        return status.hasCode(statusCode);
    }

    /**
     * Whether {@link #statusCode()} is HTTP 2xx.
     *
     * @return {@code true} if successful
     */
    public boolean isSuccessful() {
        return statusCode / 100 == 2;
    }

    /**
     * Charset named by the {@code Content-Type} {@code charset} parameter, or UTF-8 when the header is missing, names
     * no charset, or names an unknown or illegal one. Used to decode error bodies.
     *
     * @return charset to decode the body with
     */
    public Charset charset() {
        final Matcher matcher = CHARSET.matcher(header(CONTENT_TYPE).orElse(""));
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (final IllegalArgumentException unused) {
                // An unknown or illegal charset name in the header is not an error: the body falls back to UTF-8.
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Body bytes; fails when there is no in-memory body (for example HTTP 202 or 204).
     *
     * @return body bytes
     * @throws IllegalStateException if the body is absent; the message names the status code
     */
    public byte[] requireBodyBytes() {
        return body.mapBytes(bytes -> bytes, () -> {
            throw new IllegalStateException("HTTP " + statusCode + " response has no body");
        });
    }

    /**
     * Maps in-memory body bytes through {@code present}, or {@code absent} when there is no in-memory body. File and
     * stream bodies use {@code absent} and are never loaded into heap ({@link HttpBody#mapBytes}).
     *
     * @param present mapping for present bytes
     * @param absent mapping when the body is absent
     * @param <T> result type
     * @return result of {@code present} or {@code absent}
     */
    public <T> T mapBody(final Function<byte[], T> present, final Supplier<T> absent) {
        return body.mapBytes(present, absent);
    }

    private static Optional<Duration> parseRetryAfter(final String value, final Supplier<Instant> now) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (final NumberFormatException unused) {
            final Optional<Duration> delay =
                    DateTimeUtils.parseRfc1123(value).map(until -> Duration.between(now.get(), until));
            if (delay.isEmpty()) {
                LOG.atDebug()
                        .setMessage("Ignoring Retry-After value that is neither seconds nor an HTTP-date ({} chars)")
                        .addArgument(value.length())
                        .log();
            }
            return delay;
        }
    }
}
