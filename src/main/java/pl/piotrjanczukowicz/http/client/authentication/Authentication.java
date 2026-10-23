package pl.piotrjanczukowicz.http.client.authentication;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNotBlank;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import pl.piotrjanczukowicz.http.Digests;

/**
 * Credentials of an HTTP request, sent as the {@code Authorization} header: a scheme and the credential that follows
 * it. The credential is the token of a {@code Bearer} header and the Base64 text of a {@code Basic} one. It is a
 * secret, so {@link #toString()} never shows it.
 *
 * <p>It also names the principal the credential belongs to, its {@code identity}. The identity is never a secret: the
 * rate-limit gate and the response cache key their state by it, so that a limit that was hit survives a rotation of the
 * token. Unless a provider names the principal with {@link #withIdentity(String)}, it is a keyed digest of the
 * credential ({@link Digests#keyedSha256Hex(String)}): the same credential has the same identity for as long as the
 * process lives, and a different one in the next process, so an identity that reaches a log or an event cannot be
 * matched against guessed credentials. Do not store it or compare it across processes.
 *
 * @param type scheme of the header; {@code null} is refused with a {@link NullPointerException}
 * @param value the raw credential after the scheme, which is the secret itself: it must not be logged, printed or put
 *     in an exception message; {@link #identity()} is the loggable key of the principal. Blank is refused with an
 *     {@link IllegalArgumentException}
 * @param identity name of the principal, which must be unique per principal (two principals never share one), stable
 *     across credential rotation where the principal stays the same, and never a secret. The rate-limit gate and the
 *     conditional cache key their state by it, so a secret would leak into those keys, and a shared name would share
 *     limits and cached responses between principals. Blank is refused with an {@link IllegalArgumentException}
 */
public record Authentication(Type type, String value, String identity) {

    /** Scheme name of HTTP Basic authentication, RFC 7617. */
    public static final String BASIC = "Basic";

    /** Scheme name of bearer token authentication, RFC 6750. */
    public static final String BEARER = "Bearer";

    /** Authentication scheme. */
    public enum Type {
        /** HTTP Basic authentication: Base64 of {@code user:password}. */
        BASIC(Authentication.BASIC),
        /** Bearer token. */
        BEARER(Authentication.BEARER);

        private final String scheme;

        Type(final String scheme) {
            this.scheme = scheme;
        }

        /**
         * Scheme name as it appears in the header.
         *
         * @return {@code Basic} or {@code Bearer}
         */
        public String scheme() {
            return scheme;
        }
    }

    public Authentication {
        requireNonNull(type, "type");
        requireNotBlank(value, "value");
        requireNotBlank(identity, "identity");
    }

    /**
     * Basic authentication for {@code user} and {@code password}: the credential is the Base64 encoding of
     * {@code user:password} as UTF-8.
     *
     * @param user username, or a client ID; it must not be blank or contain {@code :}
     * @param password password, or a client secret; it must not be blank
     * @return authentication of type {@link Type#BASIC}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code user} is blank or contains {@code :}, or {@code password} is blank;
     *     the message never contains the password
     */
    public static Authentication ofBasic(final String user, final String password) {
        requireNotBlank(user, "user");
        if (user.indexOf(':') >= 0) {
            throw new IllegalArgumentException("user must not contain ':'");
        }
        requireNotBlank(password, "password");
        final String encoded =
                Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return of(Type.BASIC, encoded);
    }

    /**
     * Bearer authentication.
     *
     * @param token token, not including the {@code Bearer} prefix
     * @return authentication of type {@link Type#BEARER}
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws IllegalArgumentException if {@code token} is blank
     */
    public static Authentication ofBearer(final String token) {
        return of(Type.BEARER, requireNotBlank(token, "token"));
    }

    /**
     * Reads the value of an {@code Authorization} header, as {@link #headerValue()} writes it.
     *
     * @param headerValue value of the header, such as {@code Bearer abc}
     * @return the authentication with the default identity, or empty if the scheme is neither {@code Basic} nor
     *     {@code Bearer}, or no credential follows it
     * @throws NullPointerException if {@code headerValue} is {@code null}
     */
    public static Optional<Authentication> parse(final String headerValue) {
        requireNonNull(headerValue, "headerValue");
        final int space = headerValue.indexOf(' ');
        if (space < 0) {
            return Optional.empty();
        }
        final String credential = headerValue.substring(space + 1).strip();
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        final String scheme = headerValue.substring(0, space).toLowerCase(Locale.ROOT);
        if (scheme.equals(BASIC.toLowerCase(Locale.ROOT))) {
            return Optional.of(of(Type.BASIC, credential));
        }
        if (scheme.equals(BEARER.toLowerCase(Locale.ROOT))) {
            return Optional.of(of(Type.BEARER, credential));
        }
        return Optional.empty();
    }

    /**
     * The same credential, attributed to the principal {@code identity}, for a provider whose credential rotates while
     * the principal stays the same, such as an installation token. The identity must be unique per principal, stable
     * across the rotation, and never a secret: the rate-limit gate and the conditional cache key their state by it.
     *
     * @param identity name of the principal, never the credential itself
     * @return authentication with that identity
     * @throws NullPointerException if {@code identity} is {@code null}
     * @throws IllegalArgumentException if {@code identity} is blank
     */
    public Authentication withIdentity(final String identity) {
        return new Authentication(type, value, identity);
    }

    /**
     * Value of the header: the scheme, a space and the credential.
     *
     * @return for example {@code Bearer abc}; it holds the secret, so keep it out of logs
     */
    public String headerValue() {
        return type.scheme() + " " + value;
    }

    /**
     * Shows the scheme only.
     *
     * @return {@code Authentication[Bearer]} or {@code Authentication[Basic]}
     */
    @Override
    public String toString() {
        return "Authentication[" + type.scheme() + "]";
    }

    private static Authentication of(final Type type, final String value) {
        return new Authentication(type, value, Digests.keyedSha256Hex(requireNotBlank(value, "value")));
    }
}
