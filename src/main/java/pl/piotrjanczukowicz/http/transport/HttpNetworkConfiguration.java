package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.EncodingUtils.decodeBase64;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonEmpty;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNotBlank;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Optional network settings of a transport: proxy, TLS trust, certificate pins and host resolution.
 *
 * <p>Every part is optional. A part left unset keeps the default of the transport: no proxy, the JVM's TLS trust, no
 * pins and the system resolver.
 *
 * <p>{@code certificatePins} maps a host pattern ({@code api.example.com} or {@code *.example.com}) to the pins of the
 * public keys it may present, each written {@code sha256/<base64 of the SHA-256 of the key's SubjectPublicKeyInfo>}.
 * Pin at least two keys, the current one and a backup, because a connection to a pinned host fails when none matches,
 * for instance after a certificate rotation. Pinning applies on top of normal certificate validation and does not
 * replace it.
 *
 * @param proxy proxy address and optional credentials to send requests through, or {@code null} for none
 * @param tls TLS trust settings, or {@code null} for the JVM defaults
 * @param certificatePins host pattern to the pins accepted for it, possibly empty
 * @param hostResolver replacement for the system resolver, or {@code null} for the system one; when a proxy is set it
 *     resolves the proxy's host name, not the origin's
 */
public record HttpNetworkConfiguration(
        @Nullable HttpProxy proxy,
        @Nullable HttpTls tls,
        Map<String, Set<String>> certificatePins,
        @Nullable HostResolver hostResolver) {

    private static final HttpNetworkConfiguration NONE = new HttpNetworkConfiguration(null, null, Map.of(), null);
    private static final String PIN_PREFIX = "sha256/";
    private static final int SHA256_BYTES = 32;

    public HttpNetworkConfiguration {
        requireNonNull(certificatePins, "certificatePins");
        final Map<String, Set<String>> copy = new LinkedHashMap<>();
        certificatePins.forEach((pattern, pins) -> {
            requireNotBlank(pattern, "pin host pattern");
            requireNonEmpty(pins, "pins for " + pattern);
            pins.forEach(HttpNetworkConfiguration::requireValidPin);
            copy.put(pattern, Set.copyOf(pins));
        });
        certificatePins = Map.copyOf(copy);
    }

    /**
     * No proxy, the JVM's TLS trust, no pins and the system resolver.
     *
     * @return empty network configuration
     */
    public static HttpNetworkConfiguration none() {
        return NONE;
    }

    /**
     * Builder with nothing set.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "HttpNetworkConfiguration[proxy=" + proxy + ", tls=" + (tls == null ? "default" : "custom")
                + ", certificatePins=" + certificatePins.keySet() + ", hostResolver="
                + (hostResolver == null ? "system" : "custom") + "]";
    }

    private static void requireValidPin(final String pin) {
        requireNonNull(pin, "pin");
        if (!pin.startsWith(PIN_PREFIX)) {
            throw new IllegalArgumentException(
                    "pin must start with " + PIN_PREFIX + ", only SHA-256 pins are supported");
        }
        final byte[] decoded = decodeBase64(pin.substring(PIN_PREFIX.length()), "pin is not valid base64: " + pin);
        if (decoded.length != SHA256_BYTES) {
            throw new IllegalArgumentException("pin must be a 32 byte SHA-256 hash: " + pin);
        }
    }

    /** Builds an {@link HttpNetworkConfiguration}. */
    public static final class Builder {
        private @Nullable HttpProxy proxy;
        private @Nullable HttpTls tls;
        private final Map<String, Set<String>> certificatePins = new LinkedHashMap<>();
        private @Nullable HostResolver hostResolver;

        private Builder() {}

        /**
         * Sends requests through {@code proxy}.
         *
         * @param proxy proxy address and optional credentials
         * @return this builder
         * @throws NullPointerException if {@code proxy} is {@code null}
         */
        public Builder proxy(final HttpProxy proxy) {
            this.proxy = requireNonNull(proxy, "proxy");
            return this;
        }

        /**
         * Uses custom TLS trust, for example a private certificate authority.
         *
         * @param tls TLS settings
         * @return this builder
         * @throws NullPointerException if {@code tls} is {@code null}
         */
        public Builder tls(final HttpTls tls) {
            this.tls = requireNonNull(tls, "tls");
            return this;
        }

        /**
         * Accepts only the given public keys for hosts that match {@code hostPattern}. Calling it again for the same
         * pattern adds pins.
         *
         * @param hostPattern host name or {@code *.domain} pattern
         * @param pins one or more {@code sha256/<base64>} pins
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if {@code pins} is empty, or a pin is not a SHA-256 pin
         */
        public Builder certificatePin(final String hostPattern, final String... pins) {
            requireNotBlank(hostPattern, "pin host pattern");
            requireNonEmpty(pins, "pins for " + hostPattern);
            final Set<String> all = certificatePins.computeIfAbsent(hostPattern, key -> new LinkedHashSet<>());
            for (final String pin : pins) {
                all.add(requireNonNull(pin, "pin"));
            }
            return this;
        }

        /**
         * Resolves host names with {@code hostResolver} instead of the system resolver.
         *
         * @param hostResolver maps a host name to its addresses
         * @return this builder
         * @throws NullPointerException if {@code hostResolver} is {@code null}
         */
        public Builder hostResolver(final HostResolver hostResolver) {
            this.hostResolver = requireNonNull(hostResolver, "hostResolver");
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return network configuration
         * @throws IllegalArgumentException if a pin is not a valid SHA-256 pin
         */
        public HttpNetworkConfiguration build() {
            return new HttpNetworkConfiguration(proxy, tls, certificatePins, hostResolver);
        }
    }
}
