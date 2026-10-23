package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtLeast;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireAtMost;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNegative;
import static pl.piotrjanczukowicz.util.ValidationUtils.requireNonNull;
import static pl.piotrjanczukowicz.util.ValidationUtils.requirePositive;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.NonRetryableIOException;
import pl.piotrjanczukowicz.http.ResponseTooLargeException;
import pl.piotrjanczukowicz.util.IoUtils;

/**
 * Timeouts for an HTTP transport.
 *
 * <p>{@code connectTimeout} and {@code responseTimeout} must be positive. Transports always disable automatic
 * redirects. {@code responseTimeout} is the time until response headers arrive, and the inactivity limit while reading
 * the body. OkHttp also applies it as a write timeout.
 *
 * <p>{@code callTimeout} is an optional overall deadline for one transport call (one attempt, not the retries of a
 * {@link pl.piotrjanczukowicz.http.transport.RetryingHttpTransport}). It covers connecting, sending the request,
 * waiting for the response, and reading the whole body, so it also stops a body that keeps arriving but never finishes,
 * which the inactivity-based {@code responseTimeout} cannot. {@link Duration#ZERO} (the default) means no overall
 * deadline; it must not be negative. Redirects are not followed by the transport, so each redirect hop is a separate
 * call with its own deadline. When it expires the call fails with an {@link java.io.InterruptedIOException} whose
 * message is {@code timeout}; a retrying transport treats that as a deadline and does not retry it.
 *
 * <p>Every timeout must fit OkHttp's limit of {@link Integer#MAX_VALUE} milliseconds (about 24.8 days); a longer one is
 * rejected here instead of when the transport is created.
 *
 * <p>Response bodies are buffered in memory in one array. {@code maxResponseBytes} bounds the buffered (decompressed)
 * size and must not exceed {@link IoUtils#MAX_BUFFER_BYTES} ({@code Integer.MAX_VALUE - 8}); a larger body fails with
 * {@link ResponseTooLargeException}.
 *
 * <p><b>Memory.</b> The limit applies to each call, not to the transport as a whole: the buffered bodies in memory at
 * once can reach {@code maxConcurrentRequests} times {@code maxResponseBytes} (with the defaults of 100 and 64 MiB, 6.4
 * GiB), and a body of unknown length (for example chunked) is copied once more while it is joined, and over OkHttp the
 * chunk decoding adds more, so plan for two to three times its size (3.0 times was measured locally for a 16 MiB
 * chunked body). The library adds no overall budget, and an {@link OutOfMemoryError} is thrown in the calling thread,
 * not reported as a library exception. Lower {@code maxResponseBytes} or {@code maxConcurrentRequests} when large
 * bodies are expected, and read large downloads with {@code stream(...)}, whose body is not buffered and not subject to
 * {@code maxResponseBytes}.
 *
 * <p><b>Connection pool.</b> {@code maxIdleConnections} and {@code connectionKeepAlive} (default 5 minutes) size the
 * pool of a transport that creates its own client. The limit counts <em>idle</em> connections kept for reuse, not
 * concurrent requests: more requests than that can run at once, but connections beyond the limit are closed as soon as
 * they go idle and opened again for the next request, which costs a TCP and TLS handshake each time. Unless set
 * explicitly, the builder makes {@code maxIdleConnections} equal to {@code maxConcurrentRequests}, so that a steady
 * concurrency does not churn connections over HTTP/1.1; when concurrency is unlimited it uses
 * {@link #DEFAULT_MAX_IDLE_CONNECTIONS}. With HTTP/2 (for example {@code api.example.com}) all requests to a host share
 * one connection anyway.
 *
 * <p><b>Concurrency.</b> {@code maxConcurrentRequests} (default 100) caps the requests in flight on one transport; a
 * further caller waits for a free slot, which keeps a burst of threads from overwhelming a server that limits
 * concurrent requests. The cap is applied by a {@link ConcurrencyLimitingHttpTransport} that the composing code puts
 * around the transport, not by the OkHttp transport itself; use {@link #UNLIMITED_REQUESTS} to switch it off. A client
 * that talks to a service with its own rate limits sets the limit to suit that service; a higher limit raises
 * throughput and the risk of tripping the service's rate limits. {@code maxSlotWait} (default 60 seconds, must be
 * positive and at most {@link Integer#MAX_VALUE} milliseconds) bounds how long a caller waits for a free slot; after it
 * the call fails with a {@link NonRetryableIOException}. A streaming response keeps its slot until it is closed, so a
 * call made from inside a stream handler can wait for its own slot; see {@link ConcurrencyLimitingHttpTransport}.
 *
 * @param connectTimeout time allowed to establish a connection
 * @param responseTimeout time until response headers arrive, and the body-read inactivity limit
 * @param maxResponseBytes most response body bytes buffered, after any content decoding
 * @param callTimeout overall deadline for one call including the whole body, or {@link Duration#ZERO} for none
 * @param network proxy, TLS trust, certificate pins and host resolution; {@link HttpNetworkConfiguration#none()} for
 *     the JVM and system defaults
 * @param maxIdleConnections most idle connections kept in the connection pool; see the pool notes above for the builder
 *     default
 * @param connectionKeepAlive how long an idle pooled connection is kept before it is closed
 * @param maxConcurrentRequests most requests in flight at once on one transport, or {@link #UNLIMITED_REQUESTS}; see
 *     the concurrency notes above
 * @param maxSlotWait longest wait of a caller for a free request slot, above zero and at most {@link Integer#MAX_VALUE}
 *     milliseconds
 */
public record HttpTransportConfiguration(
        Duration connectTimeout,
        Duration responseTimeout,
        long maxResponseBytes,
        Duration callTimeout,
        HttpNetworkConfiguration network,
        int maxIdleConnections,
        Duration connectionKeepAlive,
        int maxConcurrentRequests,
        Duration maxSlotWait) {

    /** {@code maxIdleConnections} used when none is set and concurrent requests are unlimited: 32. */
    public static final int DEFAULT_MAX_IDLE_CONNECTIONS = 32;

    /** Default {@code maxSlotWait}: 60 seconds. */
    public static final Duration DEFAULT_MAX_SLOT_WAIT = Duration.ofSeconds(60);

    /** {@code maxConcurrentRequests} value meaning that concurrent requests are not limited. */
    public static final int UNLIMITED_REQUESTS = Integer.MAX_VALUE;

    private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    public HttpTransportConfiguration {
        requireTimeoutAtMostOkHttpLimit(requirePositive(connectTimeout, "connectTimeout"), "connectTimeout");
        requireTimeoutAtMostOkHttpLimit(requirePositive(responseTimeout, "responseTimeout"), "responseTimeout");
        requireAtMost(
                requireAtLeast(maxResponseBytes, 1L, "maxResponseBytes"), IoUtils.MAX_BUFFER_BYTES, "maxResponseBytes");
        requireTimeoutAtMostOkHttpLimit(requireNonNegative(callTimeout, "callTimeout"), "callTimeout");
        requireNonNull(network, "network");
        requirePositive(maxIdleConnections, "maxIdleConnections");
        requirePositive(connectionKeepAlive, "connectionKeepAlive");
        requirePositive(maxConcurrentRequests, "maxConcurrentRequests");
        requireSlotWait(maxSlotWait);
    }

    /**
     * Whether {@code maxConcurrentRequests} is an actual limit.
     *
     * @return {@code false} for {@link #UNLIMITED_REQUESTS}
     */
    public boolean isConcurrencyLimited() {
        return maxConcurrentRequests != UNLIMITED_REQUESTS;
    }

    /**
     * 10 second connect timeout, 30 second response timeout, 64 MiB response limit, no overall call timeout, 100 idle
     * connections kept for 5 minutes, at most 100 concurrent requests, each waiting at most 60 seconds for a slot.
     *
     * @return default configuration
     */
    public static HttpTransportConfiguration defaults() {
        return builder().build();
    }

    /**
     * Builder initialized to {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder initialized to the values of this configuration. Every field is copied, including
     * {@code maxIdleConnections}, which is set explicitly, so a later change of {@code maxConcurrentRequests} on the
     * copy does not re-derive it. {@code toBuilder().build()} therefore equals this configuration.
     *
     * @return a new builder holding the values of this configuration
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    static Duration requireSlotWait(final Duration maxSlotWait) {
        requirePositive(maxSlotWait, "maxSlotWait");
        if (maxSlotWait.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "maxSlotWait must be at most " + MAX_TIMEOUT.toMillis() + " ms, was " + maxSlotWait);
        }
        return maxSlotWait;
    }

    private static void requireTimeoutAtMostOkHttpLimit(final Duration timeout, final String name) {
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    name + " must be at most " + MAX_TIMEOUT.toMillis() + " ms (OkHttp limit), was " + timeout);
        }
    }

    /** Builds an {@link HttpTransportConfiguration}. Unset fields keep the {@link #defaults()} values. */
    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(30);
        private long maxResponseBytes = 64L * 1024 * 1024;
        private Duration callTimeout = Duration.ZERO;
        private HttpNetworkConfiguration network = HttpNetworkConfiguration.none();
        private @Nullable Integer maxIdleConnections;
        private Duration connectionKeepAlive = Duration.ofMinutes(5);
        private int maxConcurrentRequests = 100;
        private Duration maxSlotWait = DEFAULT_MAX_SLOT_WAIT;

        private Builder() {}

        private Builder(final HttpTransportConfiguration source) {
            this.connectTimeout = source.connectTimeout();
            this.responseTimeout = source.responseTimeout();
            this.maxResponseBytes = source.maxResponseBytes();
            this.callTimeout = source.callTimeout();
            this.network = source.network();
            this.maxIdleConnections = source.maxIdleConnections();
            this.connectionKeepAlive = source.connectionKeepAlive();
            this.maxConcurrentRequests = source.maxConcurrentRequests();
            this.maxSlotWait = source.maxSlotWait();
        }

        /**
         * Sets the connect timeout.
         *
         * @param connectTimeout time allowed to establish a connection
         * @return this builder
         */
        public Builder connectTimeout(final Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Sets the timeout until response headers arrive and the body-read inactivity limit.
         *
         * @param responseTimeout time until response headers arrive, and the body-read inactivity limit
         * @return this builder
         */
        public Builder responseTimeout(final Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        /**
         * Sets the most response body bytes buffered, counted after content decoding such as gzip.
         *
         * @param maxResponseBytes size limit in bytes
         * @return this builder
         */
        public Builder maxResponseBytes(final long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /**
         * Sets the overall deadline for one call: connecting, sending, waiting for the response, and reading the whole
         * body. {@link Duration#ZERO} (the default) means no overall deadline.
         *
         * @param callTimeout overall call deadline, or {@link Duration#ZERO} for none
         * @return this builder
         */
        public Builder callTimeout(final Duration callTimeout) {
            this.callTimeout = callTimeout;
            return this;
        }

        /**
         * Sets the proxy, TLS trust, certificate pins and host resolution; see {@link HttpNetworkConfiguration}.
         *
         * @param network network settings
         * @return this builder
         */
        public Builder network(final HttpNetworkConfiguration network) {
            this.network = network;
            return this;
        }

        /**
         * Sets the most idle connections the connection pool keeps. Unless set, it equals
         * {@code maxConcurrentRequests}, or {@link #DEFAULT_MAX_IDLE_CONNECTIONS} when that is
         * {@link #UNLIMITED_REQUESTS}; a value set here always wins. The limit is on idle connections, not on
         * concurrent requests; see {@link HttpTransportConfiguration}.
         *
         * @param maxIdleConnections idle connection limit, above 0
         * @return this builder
         */
        public Builder maxIdleConnections(final int maxIdleConnections) {
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        /**
         * Sets how long an idle pooled connection is kept (default 5 minutes).
         *
         * @param connectionKeepAlive keep-alive of idle connections, above zero
         * @return this builder
         */
        public Builder connectionKeepAlive(final Duration connectionKeepAlive) {
            this.connectionKeepAlive = connectionKeepAlive;
            return this;
        }

        /**
         * Sets the most requests in flight at once on one transport (default 100). Unless {@code maxIdleConnections} is
         * set explicitly, the idle connection limit follows this value.
         *
         * @param maxConcurrentRequests concurrency limit, above 0, or {@link #UNLIMITED_REQUESTS}
         * @return this builder
         */
        public Builder maxConcurrentRequests(final int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /**
         * Sets how long a caller waits for a free request slot before the call fails (default 60 seconds).
         *
         * @param maxSlotWait longest wait for a slot, above zero and at most {@link Integer#MAX_VALUE} milliseconds
         * @return this builder
         */
        public Builder maxSlotWait(final Duration maxSlotWait) {
            this.maxSlotWait = maxSlotWait;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return configuration from this builder
         * @throws NullPointerException if a timeout, the keep-alive or the network configuration is {@code null}
         * @throws IllegalArgumentException if a connect or response timeout is not positive, {@code callTimeout} is
         *     negative, a timeout exceeds {@link Integer#MAX_VALUE} milliseconds, {@code maxResponseBytes} is below 1
         *     or above {@code Integer.MAX_VALUE - 8}, or {@code maxIdleConnections}, {@code connectionKeepAlive} or
         *     {@code maxConcurrentRequests} or {@code maxSlotWait} is not positive, or {@code maxSlotWait} exceeds
         *     {@link Integer#MAX_VALUE} milliseconds
         */
        public HttpTransportConfiguration build() {
            return new HttpTransportConfiguration(
                    connectTimeout,
                    responseTimeout,
                    maxResponseBytes,
                    callTimeout,
                    network,
                    maxIdleConnections != null ? maxIdleConnections : defaultMaxIdleConnections(),
                    connectionKeepAlive,
                    maxConcurrentRequests,
                    maxSlotWait);
        }

        private int defaultMaxIdleConnections() {
            return maxConcurrentRequests < 1 || maxConcurrentRequests == UNLIMITED_REQUESTS
                    ? DEFAULT_MAX_IDLE_CONNECTIONS
                    : maxConcurrentRequests;
        }
    }
}
