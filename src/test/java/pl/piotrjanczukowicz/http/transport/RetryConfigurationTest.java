package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class RetryConfigurationTest {

    @Test
    void defaults_shouldUseExpectedPolicy_whenCalled() {
        final RetryConfiguration config = RetryConfiguration.defaults();

        assertSoftly(softly -> {
            softly.assertThat(config).hasNoNullFieldsOrPropertiesExcept("totalDeadline");
            softly.assertThat(config.totalDeadline()).isNull();
            softly.assertThat(config.attempts().maxIoAttempts()).isEqualTo(3);
            softly.assertThat(config.attempts().maxRateLimitAttempts()).isEqualTo(4);
            softly.assertThat(config.coordinateRateLimits()).isTrue();
            softly.assertThat(config.waits().defaultBackoff()).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(config.waits().maxBackoff()).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(config.jitter().fullJitter()).isTrue();
            softly.assertThat(config.retryable().retryRateLimitedResponses()).isTrue();
            softly.assertThat(config.retryable().retryIoExceptions()).isTrue();
            softly.assertThat(config.retryable().ioRetryableMethods())
                    .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
            softly.assertThat(config.waits().maxRateLimitWait()).isEqualTo(Duration.ofMinutes(5));
            softly.assertThat(config.waits().maxServerErrorWait()).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(config.waits().minRateLimitWait()).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(config.waits().maxRateLimitBlock()).isEqualTo(Duration.ofHours(1));
            softly.assertThat(config.jitter().rateLimitJitter()).isEqualTo(Duration.ofSeconds(10));
            softly.assertThat(config.jitter().clockSkewMargin()).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(config.retryable().retryableServerStatuses()).containsExactlyInAnyOrder(502, 503, 504);
            softly.assertThat(config.attempts().maxServerErrorAttempts()).isEqualTo(3);
        });
    }

    @Test
    void build_shouldOverrideAllFields_whenBuilderUsed() {
        final Set<HttpMethod> methods = Set.of(HttpMethod.GET);
        final RateLimitEventListener listener = event -> {};
        final RetryConfiguration config = RetryConfiguration.builder()
                .maxIoAttempts(5)
                .maxRateLimitAttempts(6)
                .coordinateRateLimits(false)
                .rateLimitEventListener(listener)
                .defaultBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(30))
                .fullJitter(false)
                .retryRateLimitedResponses(false)
                .retryIoExceptions(false)
                .ioRetryableMethods(methods)
                .maxRateLimitWait(Duration.ofSeconds(20))
                .maxServerErrorWait(Duration.ofSeconds(15))
                .minRateLimitWait(Duration.ofSeconds(20))
                .rateLimitJitter(Duration.ofMillis(250))
                .clockSkewMargin(Duration.ofMillis(500))
                .retryableServerStatuses(Set.of(503))
                .maxServerErrorAttempts(4)
                .totalDeadline(Duration.ofMinutes(2))
                .build();

        assertSoftly(softly -> {
            softly.assertThat(config).hasNoNullFieldsOrProperties();
            softly.assertThat(config.attempts().maxIoAttempts()).isEqualTo(5);
            softly.assertThat(config.attempts().maxRateLimitAttempts()).isEqualTo(6);
            softly.assertThat(config.coordinateRateLimits()).isFalse();
            softly.assertThat(config.rateLimitEventListener()).isSameAs(listener);
            softly.assertThat(config.waits().defaultBackoff()).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(config.waits().maxBackoff()).isEqualTo(Duration.ofSeconds(30));
            softly.assertThat(config.jitter().fullJitter()).isFalse();
            softly.assertThat(config.retryable().retryRateLimitedResponses()).isFalse();
            softly.assertThat(config.retryable().retryIoExceptions()).isFalse();
            softly.assertThat(config.retryable().ioRetryableMethods()).containsExactly(HttpMethod.GET);
            softly.assertThat(config.waits().maxRateLimitWait()).isEqualTo(Duration.ofSeconds(20));
            softly.assertThat(config.waits().maxServerErrorWait()).isEqualTo(Duration.ofSeconds(15));
            softly.assertThat(config.waits().minRateLimitWait()).isEqualTo(Duration.ofSeconds(20));
            softly.assertThat(config.jitter().rateLimitJitter()).isEqualTo(Duration.ofMillis(250));
            softly.assertThat(config.jitter().clockSkewMargin()).isEqualTo(Duration.ofMillis(500));
            softly.assertThat(config.retryable().retryableServerStatuses()).containsExactly(503);
            softly.assertThat(config.attempts().maxServerErrorAttempts()).isEqualTo(4);
            softly.assertThat(config.totalDeadline()).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {399, 600})
    void build_shouldThrow_whenRetryableServerStatusIsOutsideErrorRange(final int status) {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder().retryableServerStatuses(Set.of(status));

        assertThat(catchThrowable(builder::build)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_shouldAcceptBoundaryStatuses_whenTheyAreErrorStatuses() {
        final RetryConfiguration config = RetryConfiguration.builder()
                .retryableServerStatuses(Set.of(400, 599))
                .build();

        assertThat(config.retryable().retryableServerStatuses()).containsExactlyInAnyOrder(400, 599);
    }

    @Test
    void build_shouldThrow_whenRetryableServerStatusIsNotAnErrorStatus() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder().retryableServerStatuses(Set.of(200));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryableServerStatuses")
                .hasMessageContaining("200");
    }

    @Test
    void build_shouldThrow_whenTotalDeadlineNotPositive() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder().totalDeadline(Duration.ZERO);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalDeadline");
    }

    @Test
    void hasDefaultRateLimitDetector_shouldBeFalse_whenCustomDetectorSet() {
        final RetryConfiguration explicit = RetryConfiguration.builder()
                .rateLimitDetector((response, clock) -> Optional.empty())
                .build();

        assertSoftly(softly -> {
            softly.assertThat(RetryConfiguration.defaults().hasDefaultRateLimitDetector())
                    .isTrue();
            softly.assertThat(explicit.hasDefaultRateLimitDetector()).isFalse();
        });
    }

    @Test
    void hasDefaultRateLimitDetector_shouldBeTrue_whenDefaultsDetectorSetExplicitly() {
        final RetryConfiguration custom = RetryConfiguration.builder()
                .rateLimitDetector((response, clock) -> Optional.empty())
                .build();

        assertSoftly(softly -> {
            softly.assertThat(custom.withRateLimitDetector(RateLimitDetector.defaults())
                            .hasDefaultRateLimitDetector())
                    .isTrue();
            softly.assertThat(RetryConfiguration.builder()
                            .rateLimitDetector(RateLimitDetector.defaults())
                            .build()
                            .hasDefaultRateLimitDetector())
                    .isTrue();
        });
    }

    @Test
    void build_shouldCopyIoRetryableMethods_whenSourceMutated() {
        final Set<HttpMethod> methods = new HashSet<>(Set.of(HttpMethod.GET));
        final RetryConfiguration config =
                RetryConfiguration.builder().ioRetryableMethods(methods).build();

        methods.add(HttpMethod.POST);

        assertThat(config.retryable().ioRetryableMethods()).containsExactly(HttpMethod.GET);
    }

    @Test
    void ioRetryableMethods_shouldCopySet_whenMutatedBeforeBuild() {
        final Set<HttpMethod> methods = new HashSet<>(Set.of(HttpMethod.GET));
        final RetryConfiguration.Builder builder = RetryConfiguration.builder().ioRetryableMethods(methods);
        methods.add(HttpMethod.POST);

        final RetryConfiguration config = builder.build();

        assertThat(config.retryable().ioRetryableMethods()).containsExactly(HttpMethod.GET);
    }

    @ParameterizedTest
    @MethodSource("invalidBuilders")
    void build_shouldThrow_whenFieldInvalid(final Consumer<RetryConfiguration.Builder> mutator, final String field) {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder();
        mutator.accept(builder);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    private static Stream<Arguments> invalidBuilders() {
        return Stream.of(
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxIoAttempts(0), "maxIoAttempts"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxRateLimitAttempts(0),
                        "maxRateLimitAttempts"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.defaultBackoff(Duration.ofSeconds(-1)),
                        "defaultBackoff"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxBackoff(Duration.ofSeconds(-1)),
                        "maxBackoff"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder ->
                                builder.defaultBackoff(Duration.ofSeconds(10)).maxBackoff(Duration.ofSeconds(5)),
                        "maxBackoff"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.ioRetryableMethods(Set.of()),
                        "ioRetryableMethods"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.maxRateLimitWait(Duration.ofSeconds(-1)),
                        "maxRateLimitWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.maxServerErrorWait(Duration.ofSeconds(-1)),
                        "maxServerErrorWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.minRateLimitWait(Duration.ofSeconds(-1)),
                        "minRateLimitWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.rateLimitJitter(Duration.ofSeconds(-1)),
                        "rateLimitJitter"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>)
                                builder -> builder.clockSkewMargin(Duration.ofSeconds(-1)),
                        "clockSkewMargin"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxServerErrorAttempts(0),
                        "maxServerErrorAttempts"));
    }

    @ParameterizedTest
    @MethodSource("oversizedBuilders")
    void build_shouldThrowTooLarge_whenDurationExceedsNanoseconds(
            final Consumer<RetryConfiguration.Builder> mutator, final String field) {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder();
        mutator.accept(builder);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(field + " must be at most PT2562047H47M16.854775807S");
    }

    private static Stream<Arguments> oversizedBuilders() {
        final Duration oversized = Duration.ofDays(200_000);
        return Stream.of(
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.defaultBackoff(oversized),
                        "defaultBackoff"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxBackoff(oversized), "maxBackoff"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxRateLimitWait(oversized),
                        "maxRateLimitWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxServerErrorWait(oversized),
                        "maxServerErrorWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.minRateLimitWait(oversized),
                        "minRateLimitWait"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.maxRateLimitBlock(oversized),
                        "maxRateLimitBlock"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.rateLimitJitter(oversized),
                        "rateLimitJitter"),
                Arguments.of(
                        (Consumer<RetryConfiguration.Builder>) builder -> builder.clockSkewMargin(oversized),
                        "clockSkewMargin"));
    }

    @Test
    void build_shouldAcceptLargestNanosecondDuration_whenRateLimitJitterIsAtLimit() {
        final RetryConfiguration config = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ofNanos(Long.MAX_VALUE))
                .build();

        assertThat(config.jitter().rateLimitJitter()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    void build_shouldThrow_whenMinRateLimitWaitExceedsMaxRateLimitWait() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder()
                .minRateLimitWait(Duration.ofSeconds(60))
                .maxRateLimitWait(Duration.ofSeconds(30));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRateLimitWait");
    }

    @Test
    void build_shouldAcceptEqualMinAndMaxRateLimitWait_whenSet() {
        final RetryConfiguration config = RetryConfiguration.builder()
                .minRateLimitWait(Duration.ofSeconds(30))
                .maxRateLimitWait(Duration.ofSeconds(30))
                .build();

        assertThat(config.waits().maxRateLimitWait()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void build_shouldKeepDefaultMaxRateLimitWait_whenMaxBackoffIsChanged() {
        final RetryConfiguration config = RetryConfiguration.builder()
                .maxBackoff(Duration.ofSeconds(5))
                .minRateLimitWait(Duration.ofSeconds(60))
                .build();

        assertThat(config.waits().maxRateLimitWait()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void build_shouldThrow_whenMinRateLimitWaitExceedsDefaultMaxRateLimitWait() {
        final RetryConfiguration.Builder builder =
                RetryConfiguration.builder().minRateLimitWait(Duration.ofMinutes(10));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRateLimitWait");
    }

    @Test
    void maxRateLimitWait_shouldThrow_whenNull() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.maxRateLimitWait(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("maxRateLimitWait");
    }

    @Test
    void maxRateLimitBlock_shouldThrow_whenNull() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.maxRateLimitBlock(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("maxRateLimitBlock");
    }

    @Test
    void build_shouldThrow_whenMaxRateLimitBlockNotPositive() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder().maxRateLimitBlock(Duration.ZERO);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRateLimitBlock");
    }

    @Test
    void build_shouldStoreMaxRateLimitBlock_whenSet() {
        final RetryConfiguration config = RetryConfiguration.builder()
                .maxRateLimitBlock(Duration.ofMinutes(15))
                .build();

        assertThat(config.waits().maxRateLimitBlock()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rateLimitEventListener_shouldThrow_whenNull() {
        final RetryConfiguration.Builder builder = RetryConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.rateLimitEventListener(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("rateLimitEventListener");
    }

    @Test
    void withRateLimitDetector_shouldKeepOtherSettings_whenDetectorReplaced() {
        final RateLimitEventListener listener = event -> {};
        final RetryConfiguration original = RetryConfiguration.builder()
                .coordinateRateLimits(false)
                .rateLimitEventListener(listener)
                .build();

        final RetryConfiguration copy = original.withRateLimitDetector((response, clock) -> Optional.empty());

        assertSoftly(softly -> {
            softly.assertThat(copy.coordinateRateLimits()).isFalse();
            softly.assertThat(copy.rateLimitEventListener()).isSameAs(listener);
            softly.assertThat(copy.attempts()).isEqualTo(original.attempts());
        });
    }

    @Test
    void constructor_shouldThrow_whenWaitCapsAreInconsistent() {
        final RetryConfiguration valid = RetryConfiguration.defaults();
        final RetryConfiguration.WaitCaps waits = new RetryConfiguration.WaitCaps(
                Duration.ZERO,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofSeconds(20),
                Duration.ofHours(1));

        final Throwable thrown = catchThrowable(() -> new RetryConfiguration(
                valid.attempts(),
                waits,
                valid.jitter(),
                valid.retryable(),
                valid.rateLimitDetector(),
                true,
                valid.rateLimitEventListener(),
                null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRateLimitWait");
    }
}
