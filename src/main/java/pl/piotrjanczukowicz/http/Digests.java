package pl.piotrjanczukowicz.http;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Keyed digests of text, for keys and identities that must never hold the text itself. */
public final class Digests {

    private static final String MAC_ALGORITHM = "HmacSHA256";

    // Random for each process and never written out: a digest that leaks into a log or an event cannot be matched
    // against guessed credentials by anyone who does not hold this key.
    private static final SecretKey PROCESS_KEY = newKey(MAC_ALGORITHM);

    private Digests() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * HMAC-SHA-256 of {@code value} encoded as UTF-8, in lowercase hexadecimal, under a random key that is made once
     * for each process. The same text gives the same result for as long as the process lives, and a different one in
     * the next process, so use it for in-memory keys and for identities that may reach a log, and never for anything
     * that is stored or compared across processes. Unlike a plain digest it cannot be matched against guessed
     * credentials offline.
     *
     * @param value text to digest
     * @return 64 hexadecimal characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String keyedSha256Hex(final String value) {
        return keyedHex(requireNonNull(value, "value"), MAC_ALGORITHM);
    }

    static SecretKey newKey(final String algorithm) {
        try {
            return KeyGenerator.getInstance(algorithm).generateKey();
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(algorithm + " is not available", unavailable);
        }
    }

    static String keyedHex(final String value, final String algorithm) {
        try {
            final Mac mac = Mac.getInstance(algorithm);
            mac.init(PROCESS_KEY);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final GeneralSecurityException unavailable) {
            throw new IllegalStateException(algorithm + " is not available", unavailable);
        }
    }
}
