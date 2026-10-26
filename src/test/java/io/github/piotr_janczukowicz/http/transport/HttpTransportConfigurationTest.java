package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.IoUtils;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class HttpTransportConfigurationTest {

    @Test
    void defaults_shouldUseExpectedValues_whenCalled() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.defaults();

        assertSoftly(softly -> {
            softly.assertThat(config).hasNoNullFieldsOrProperties();
            softly.assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            softly.assertThat(config.responseTimeout()).isEqualTo(Duration.ofSeconds(30));
            softly.assertThat(config.maxResponseBytes()).isEqualTo(64L * 1024 * 1024);
            softly.assertThat(config.callTimeout()).isEqualTo(Duration.ZERO);
            softly.assertThat(config.maxIdleConnections()).isEqualTo(100);
            softly.assertThat(config.connectionKeepAlive()).isEqualTo(Duration.ofMinutes(5));
            softly.assertThat(config.maxConcurrentRequests()).isEqualTo(100);
            softly.assertThat(config.maxSlotWait()).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(config.isConcurrencyLimited()).isTrue();
            softly.assertThat(config.toString())
                    .contains(
                            "maxIdleConnections=100",
                            "connectionKeepAlive=PT5M",
                            "maxConcurrentRequests=100",
                            "maxSlotWait=PT1M");
        });
    }

    @Test
    void build_shouldOverrideAllFields_whenBuilderUsed() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(15))
                .maxResponseBytes(1024)
                .callTimeout(Duration.ofSeconds(60))
                .maxIdleConnections(8)
                .connectionKeepAlive(Duration.ofSeconds(90))
                .maxConcurrentRequests(7)
                .maxSlotWait(Duration.ofSeconds(3))
                .build();

        assertSoftly(softly -> {
            softly.assertThat(config).hasNoNullFieldsOrProperties();
            softly.assertThat(config.maxIdleConnections()).isEqualTo(8);
            softly.assertThat(config.connectionKeepAlive()).isEqualTo(Duration.ofSeconds(90));
            softly.assertThat(config.maxConcurrentRequests()).isEqualTo(7);
            softly.assertThat(config.maxSlotWait()).isEqualTo(Duration.ofSeconds(3));
            softly.assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            softly.assertThat(config.responseTimeout()).isEqualTo(Duration.ofSeconds(15));
            softly.assertThat(config.maxResponseBytes()).isEqualTo(1024);
            softly.assertThat(config.callTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void builder_shouldUseDefaultMaxResponseBytes_whenOnlyTimeoutsAreSet() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .responseTimeout(Duration.ofSeconds(2))
                .build();

        assertSoftly(softly -> {
            softly.assertThat(config.maxResponseBytes()).isEqualTo(64L * 1024 * 1024);
            softly.assertThat(config)
                    .isEqualTo(HttpTransportConfiguration.builder()
                            .connectTimeout(Duration.ofSeconds(1))
                            .responseTimeout(Duration.ofSeconds(2))
                            .maxResponseBytes(64L * 1024 * 1024)
                            .build());
            softly.assertThat(config)
                    .hasSameHashCodeAs(HttpTransportConfiguration.builder()
                            .connectTimeout(Duration.ofSeconds(1))
                            .responseTimeout(Duration.ofSeconds(2))
                            .build());
            softly.assertThat(config.toString()).contains("maxResponseBytes=67108864");
            softly.assertThat(config.callTimeout()).isEqualTo(Duration.ZERO);
        });
    }

    @Test
    void build_shouldBeEqualToDefaults_whenPoolFieldsSetToTheirDefaults() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .maxIdleConnections(100)
                .connectionKeepAlive(Duration.ofMinutes(5))
                .maxConcurrentRequests(100)
                .build();

        assertThat(config)
                .isEqualTo(HttpTransportConfiguration.defaults())
                .hasSameHashCodeAs(HttpTransportConfiguration.defaults());
    }

    @Test
    void build_shouldNotBeEqual_whenPoolFieldDiffers() {
        final HttpTransportConfiguration config =
                HttpTransportConfiguration.builder().maxIdleConnections(101).build();

        assertThat(config).isNotEqualTo(HttpTransportConfiguration.defaults());
    }

    @Test
    void build_shouldReportUnlimited_whenMaxConcurrentRequestsUnlimited() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .maxConcurrentRequests(HttpTransportConfiguration.UNLIMITED_REQUESTS)
                .build();

        assertThat(config.isConcurrencyLimited()).isFalse();
    }

    @Test
    void build_shouldFollowConcurrencyLimit_whenMaxIdleConnectionsNotSet() {
        final HttpTransportConfiguration config =
                HttpTransportConfiguration.builder().maxConcurrentRequests(20).build();

        assertThat(config.maxIdleConnections()).isEqualTo(20);
    }

    @Test
    void build_shouldPreferExplicitMaxIdleConnections_whenSet() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .maxConcurrentRequests(20)
                .maxIdleConnections(5)
                .build();

        assertThat(config.maxIdleConnections()).isEqualTo(5);
    }

    @Test
    void build_shouldUseDefaultMaxIdleConnections_whenConcurrencyUnlimited() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .maxConcurrentRequests(HttpTransportConfiguration.UNLIMITED_REQUESTS)
                .build();

        assertThat(config.maxIdleConnections()).isEqualTo(HttpTransportConfiguration.DEFAULT_MAX_IDLE_CONNECTIONS);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void build_shouldThrow_whenMaxSlotWaitNotPositive(final long seconds) {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxSlotWait(Duration.ofSeconds(seconds));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSlotWait");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void build_shouldThrow_whenMaxIdleConnectionsNotPositive(final int value) {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxIdleConnections(value);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxIdleConnections");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void build_shouldThrow_whenMaxConcurrentRequestsNotPositive(final int value) {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxConcurrentRequests(value);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrentRequests");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void build_shouldThrow_whenConnectionKeepAliveNotPositive(final long seconds) {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().connectionKeepAlive(Duration.ofSeconds(seconds));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connectionKeepAlive");
    }

    @Test
    void build_shouldThrow_whenConnectionKeepAliveNull() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().connectionKeepAlive(null);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("connectionKeepAlive");
    }

    @Test
    void builder_shouldDisableCallTimeout_whenMaxResponseBytesIsSetWithoutCallTimeout() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .responseTimeout(Duration.ofSeconds(2))
                .maxResponseBytes(1024)
                .build();

        assertThat(config.callTimeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void build_shouldThrow_whenCallTimeoutNegative() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().callTimeout(Duration.ofSeconds(-1));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("callTimeout");
    }

    @Test
    void build_shouldThrow_whenCallTimeoutNull() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().callTimeout(null);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("callTimeout");
    }

    @Test
    void build_shouldThrow_whenMaxResponseBytesNotPositive() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxResponseBytes(0);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxResponseBytes");
    }

    @ParameterizedTest
    @MethodSource("nonPositiveTimeouts")
    void build_shouldThrow_whenTimeoutNotPositive(
            final Consumer<HttpTransportConfiguration.Builder> mutator, final String field) {
        final HttpTransportConfiguration.Builder builder = HttpTransportConfiguration.builder();
        mutator.accept(builder);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void build_shouldThrow_whenConnectTimeoutNegative() {
        final HttpTransportConfiguration.Builder builder = HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(-1))
                .responseTimeout(Duration.ofSeconds(30));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connectTimeout");
    }

    private static Stream<Arguments> nonPositiveTimeouts() {
        return Stream.of(
                Arguments.of(
                        (Consumer<HttpTransportConfiguration.Builder>) builder -> builder.connectTimeout(Duration.ZERO),
                        "connectTimeout"),
                Arguments.of(
                        (Consumer<HttpTransportConfiguration.Builder>)
                                builder -> builder.responseTimeout(Duration.ZERO),
                        "responseTimeout"));
    }

    @Test
    void build_shouldAcceptMaxResponseBytes_whenAtMaxBufferSize() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .maxResponseBytes(IoUtils.MAX_BUFFER_BYTES)
                .build();

        assertThat(config.maxResponseBytes()).isEqualTo(Integer.MAX_VALUE - 8L);
    }

    @ParameterizedTest
    @ValueSource(longs = {Integer.MAX_VALUE - 7L, Integer.MAX_VALUE, 1L << 31, Long.MAX_VALUE})
    void build_shouldThrow_whenMaxResponseBytesExceedsMaxBufferSize(final long maxResponseBytes) {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxResponseBytes(maxResponseBytes);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxResponseBytes");
    }

    @Test
    void build_shouldAcceptTimeouts_whenAtOkHttpLimit() {
        final Duration limit = Duration.ofMillis(Integer.MAX_VALUE);

        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .connectTimeout(limit)
                .responseTimeout(limit)
                .callTimeout(limit)
                .build();

        assertThat(config.callTimeout()).isEqualTo(limit);
    }

    @ParameterizedTest
    @MethodSource("timeoutsAboveOkHttpLimit")
    void build_shouldThrow_whenTimeoutExceedsOkHttpLimit(
            final Consumer<HttpTransportConfiguration.Builder> setter, final String field) {
        final HttpTransportConfiguration.Builder builder = HttpTransportConfiguration.builder();
        setter.accept(builder);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    private static Stream<Arguments> timeoutsAboveOkHttpLimit() {
        final Duration tooLong = Duration.ofMillis(Integer.MAX_VALUE + 1L);
        return Stream.of(
                Arguments.of(
                        (Consumer<HttpTransportConfiguration.Builder>) b -> b.connectTimeout(tooLong),
                        "connectTimeout"),
                Arguments.of(
                        (Consumer<HttpTransportConfiguration.Builder>) b -> b.responseTimeout(tooLong),
                        "responseTimeout"),
                Arguments.of((Consumer<HttpTransportConfiguration.Builder>) b -> b.callTimeout(tooLong), "callTimeout"),
                Arguments.of(
                        (Consumer<HttpTransportConfiguration.Builder>) b -> b.callTimeout(Duration.ofDays(1_000_000)),
                        "callTimeout"));
    }

    @Test
    void build_shouldThrow_whenMaxSlotWaitAboveOkHttpLimit() {
        final HttpTransportConfiguration.Builder builder =
                HttpTransportConfiguration.builder().maxSlotWait(Duration.ofDays(365 * 400));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSlotWait");
    }

    @Test
    void toBuilder_shouldRoundTripEqual_whenBuiltFromDefaults() {
        final HttpTransportConfiguration defaults = HttpTransportConfiguration.defaults();

        final HttpTransportConfiguration rebuilt = defaults.toBuilder().build();

        assertThat(rebuilt).isEqualTo(defaults);
    }

    @Test
    void toBuilder_shouldRoundTripEqual_whenEveryFieldIsCustomized() {
        final HttpTransportConfiguration config = HttpTransportConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(15))
                .maxResponseBytes(1024)
                .callTimeout(Duration.ofSeconds(60))
                .network(HttpNetworkConfiguration.builder()
                        .certificatePin(
                                "api.example.com",
                                "sha256/" + Base64.getEncoder().encodeToString(new byte[32]))
                        .build())
                .maxIdleConnections(8)
                .connectionKeepAlive(Duration.ofSeconds(90))
                .maxConcurrentRequests(7)
                .maxSlotWait(Duration.ofSeconds(3))
                .build();

        final HttpTransportConfiguration rebuilt = config.toBuilder().build();

        assertThat(rebuilt).isEqualTo(config);
    }

    @Test
    void toBuilder_shouldKeepIdleConnectionLimit_whenConcurrencyChanged() {
        final HttpTransportConfiguration config =
                HttpTransportConfiguration.builder().maxConcurrentRequests(7).build();

        final HttpTransportConfiguration changed =
                config.toBuilder().maxConcurrentRequests(3).build();

        assertSoftly(softly -> {
            softly.assertThat(changed.maxConcurrentRequests()).isEqualTo(3);
            softly.assertThat(changed.maxIdleConnections()).isEqualTo(7);
        });
    }
}
