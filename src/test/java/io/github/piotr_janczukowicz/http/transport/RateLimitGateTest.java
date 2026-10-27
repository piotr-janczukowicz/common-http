package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.TRANSFER_ENCODING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@UnitTest
class RateLimitGateTest {

    private static final Instant START = Instant.parse("2026-10-09T12:00:00Z");
    private static final String SECRET = "Bearer tok_super_secret_token";

    private final MutableClock clock = new MutableClock(START);
    private final RateLimitGate gate =
            new RateLimitGate(clock, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMinutes(5));
    private final HttpResponse rejection = new HttpResponse(
            429,
            HttpHeaders.of(DATE, "Fri, 09 Oct 2026 12:00:00 GMT", "X-Custom", "kept", RETRY_AFTER, "1"),
            HttpBody.of(new byte[] {1, 2, 3}));

    @Test
    void blockFor_shouldFindBlock_whenSameOriginCredentialAndBucket() {
        gate.block(request("https://api.example.test/user", SECRET), "bucket-a", Duration.ofSeconds(60), rejection);

        assertThat(gate.blockFor(request("https://api.example.test/repos/o/r", SECRET), "bucket-a"))
                .hasValueSatisfying(block -> assertSoftly(softly -> {
                    softly.assertThat(block.bucket()).isEqualTo("bucket-a");
                    softly.assertThat(block.until()).isEqualTo(START.plusSeconds(60));
                    softly.assertThat(block.rejection()).isSameAs(rejection);
                }));
    }

    @Test
    void blockFor_shouldBeEmpty_whenOtherBucketCredentialOrOrigin() {
        gate.block(request("https://api.example.test/user", SECRET), "bucket-a", Duration.ofSeconds(60), rejection);

        assertSoftly(softly -> {
            softly.assertThat(gate.blockFor(request("https://api.example.test/user", SECRET), "search"))
                    .isEmpty();
            softly.assertThat(gate.blockFor(request("https://api.example.test/user", "Bearer other"), "bucket-a"))
                    .isEmpty();
            softly.assertThat(gate.blockFor(request("https://api.example.test/user", null), "bucket-a"))
                    .isEmpty();
            softly.assertThat(gate.blockFor(request("https://other.example.test/user", SECRET), "bucket-a"))
                    .isEmpty();
            softly.assertThat(gate.blockFor(request("http://api.example.test/user", SECRET), "bucket-a"))
                    .isEmpty();
        });
    }

    @Test
    void blockFor_shouldApplyGlobalBlockToEveryBucket_whenBucketIsNull() {
        gate.block(request("https://api.example.test/user", SECRET), null, Duration.ofSeconds(60), rejection);

        assertSoftly(softly -> {
            softly.assertThat(gate.blockFor(request("https://api.example.test/user", SECRET), "bucket-a"))
                    .isPresent();
            softly.assertThat(gate.blockFor(request("https://api.example.test/user", SECRET), "search"))
                    .isPresent();
        });
    }

    @Test
    void blockFor_shouldReturnTheLaterBlock_whenBucketAndGlobalBothApply() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, "bucket-a", Duration.ofSeconds(60), rejection);
        gate.block(request, null, Duration.ofSeconds(120), rejection);
        final HttpRequest other = request("https://api.example.test/user", "Bearer other");
        gate.block(other, "bucket-a", Duration.ofSeconds(300), rejection);
        gate.block(other, null, Duration.ofSeconds(30), rejection);

        assertSoftly(softly -> {
            softly.assertThat(gate.blockFor(request, "bucket-a").orElseThrow().until())
                    .isEqualTo(START.plusSeconds(120));
            softly.assertThat(gate.blockFor(other, "bucket-a").orElseThrow().until())
                    .isEqualTo(START.plusSeconds(300));
        });
    }

    @Test
    void block_shouldKeepTheLaterEnd_whenKeyIsRecordedAgain() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        final HttpResponse later = new HttpResponse(429, HttpHeaders.empty(), HttpBody.absent());

        gate.block(request, "bucket-a", Duration.ofSeconds(120), later);
        final Instant ignored = gate.block(request, "bucket-a", Duration.ofSeconds(60), rejection);

        assertSoftly(softly -> {
            softly.assertThat(ignored).isEqualTo(START.plusSeconds(60));
            softly.assertThat(gate.blockFor(request, "bucket-a").orElseThrow().rejection())
                    .isSameAs(later);
            softly.assertThat(gate.size()).isEqualTo(1);
        });
    }

    @Test
    void block_shouldEndAtMaxBlockFromNow_whenMaxBlockIsConfigured() {
        final RateLimitGate shortGate =
                new RateLimitGate(clock, Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofMinutes(5));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        final Instant until = shortGate.block(request, null, Duration.ofMinutes(30), rejection);

        assertThat(until).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @Test
    void block_shouldEndOneHourFromNow_whenWaitIsHuge() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        final Instant until = gate.block(request, null, Duration.ofSeconds(Long.MAX_VALUE), rejection);

        assertSoftly(softly -> {
            softly.assertThat(until).isEqualTo(START.plus(Duration.ofHours(1)));
            softly.assertThat(gate.blockFor(request, "bucket-a").orElseThrow().until())
                    .isEqualTo(START.plus(Duration.ofHours(1)));
        });
    }

    @Test
    void block_shouldNotCutTheWait_whenItIsUnderAnHour() {
        final Instant until =
                gate.block(request("https://api.example.test/user", SECRET), null, Duration.ofMinutes(59), rejection);

        assertThat(until).isEqualTo(START.plus(Duration.ofMinutes(59)));
    }

    @Test
    void blockFor_shouldBeEmptyAfterAnHour_whenWaitWasHuge() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, null, Duration.ofSeconds(Long.MAX_VALUE), rejection);

        clock.advance(Duration.ofHours(1));

        assertThat(gate.blockFor(request, "bucket-a")).isEmpty();
    }

    @Test
    void block_shouldKeepOnlyTheFirst8KiBOfTheBody_whenRejectionIsLarge() {
        final byte[] large = new byte[64 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        final HttpResponse big = new HttpResponse(429, HttpHeaders.of("X-Custom", "kept"), HttpBody.of(large));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        gate.block(request, "bucket-a", Duration.ofSeconds(60), big);
        final HttpResponse stored =
                gate.blockFor(request, "bucket-a").orElseThrow().rejection();

        assertSoftly(softly -> {
            softly.assertThat(stored.requireBodyBytes()).hasSize(8192).containsExactly(Arrays.copyOf(large, 8192));
            softly.assertThat(stored.statusCode()).isEqualTo(429);
            softly.assertThat(stored.header("X-Custom")).contains("kept");
        });
    }

    @Test
    void block_shouldDropContentLength_whenBodyIsCut() {
        final byte[] large = new byte[64 * 1024];
        Arrays.fill(large, (byte) 9);
        final HttpResponse big = new HttpResponse(
                429,
                HttpHeaders.fromWire(Map.of(
                        CONTENT_LENGTH, List.of("65536"), "X-Custom", List.of("kept"), RETRY_AFTER, List.of("1"))),
                HttpBody.of(large));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        gate.block(request, "bucket-a", Duration.ofSeconds(60), big);
        final HttpResponse stored =
                gate.blockFor(request, "bucket-a").orElseThrow().rejection();

        assertSoftly(softly -> {
            softly.assertThat(stored.header(CONTENT_LENGTH)).isEmpty();
            softly.assertThat(stored.requireBodyBytes()).hasSize(8192);
            softly.assertThat(stored.header("X-Custom")).contains("kept");
            softly.assertThat(stored.header(RETRY_AFTER)).contains("1");
        });
    }

    @Test
    void block_shouldDropContentLengthInAnyCase_whenBodyIsCut() {
        final byte[] large = new byte[64 * 1024];
        final HttpResponse big = new HttpResponse(
                429, HttpHeaders.fromWire(Map.of("content-length", List.of("65536"))), HttpBody.of(large));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        gate.block(request, "bucket-a", Duration.ofSeconds(60), big);
        final HttpResponse stored =
                gate.blockFor(request, "bucket-a").orElseThrow().rejection();

        assertSoftly(softly -> {
            softly.assertThat(stored.headers().asMap().keySet()).noneMatch("content-length"::equalsIgnoreCase);
            softly.assertThat(stored.requireBodyBytes()).hasSize(8192);
        });
    }

    @Test
    void block_shouldKeepTheBodyUnchanged_whenItIsSmall() {
        final byte[] small = new byte[100];
        Arrays.fill(small, (byte) 7);
        final HttpResponse little = new HttpResponse(429, HttpHeaders.empty(), HttpBody.of(small));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        gate.block(request, "bucket-a", Duration.ofSeconds(60), little);

        assertThat(gate.blockFor(request, "bucket-a").orElseThrow().rejection().requireBodyBytes())
                .containsExactly(small);
    }

    @Test
    void block_shouldKeepTheBodyUnchanged_whenItIsExactly8KiB() {
        final byte[] edge = new byte[8192];
        final HttpResponse exact = new HttpResponse(429, HttpHeaders.empty(), HttpBody.of(edge));
        final HttpRequest request = request("https://api.example.test/user", SECRET);

        gate.block(request, "bucket-a", Duration.ofSeconds(60), exact);

        assertThat(gate.blockFor(request, "bucket-a").orElseThrow().rejection()).isSameAs(exact);
    }

    @Test
    void blockFor_shouldIgnoreExpiredBlock_whenLimitHasEnded() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, "bucket-a", Duration.ofSeconds(60), rejection);
        gate.block(request, "search", Duration.ofSeconds(120), rejection);

        clock.advance(Duration.ofSeconds(60));
        final boolean coreBlocked = gate.blockFor(request, "bucket-a").isPresent();
        final boolean searchBlocked = gate.blockFor(request, "search").isPresent();

        assertSoftly(softly -> {
            softly.assertThat(coreBlocked).isFalse();
            softly.assertThat(searchBlocked).isTrue();
            softly.assertThat(gate.size()).isEqualTo(2);
        });
    }

    @Test
    void block_shouldDropExpiredBlocks_whenLimitHasEnded() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, "bucket-a", Duration.ofSeconds(60), rejection);
        gate.block(request, "search", Duration.ofSeconds(120), rejection);
        clock.advance(Duration.ofSeconds(60));

        gate.block(request, "graphql", Duration.ofSeconds(60), rejection);

        assertThat(gate.size()).isEqualTo(2);
    }

    @Test
    void block_shouldDropExpiredBlocksOfOtherCredentials_whenTokensRotate() {
        gate.block(request("https://api.example.test/user", "Bearer old"), null, Duration.ofSeconds(60), rejection);
        clock.advance(Duration.ofSeconds(61));

        gate.block(request("https://api.example.test/user", "Bearer new"), null, Duration.ofSeconds(60), rejection);

        assertThat(gate.size()).isEqualTo(1);
    }

    @Test
    void blockFor_shouldFindEveryOwnBlock_whenManyThreadsBlockAndLookUpConcurrently() throws Exception {
        final int callers = 8;
        final int rounds = 200;
        final CyclicBarrier start = new CyclicBarrier(callers);
        final ExecutorService executor = Executors.newFixedThreadPool(callers);

        final int found;
        try {
            final List<Future<Integer>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                final HttpRequest request = request("https://api.example.test/user", "Bearer thread-" + caller);
                futures.add(executor.submit(() -> {
                    start.await();
                    int own = 0;
                    for (int round = 0; round < rounds; round++) {
                        gate.block(request, "bucket-a", Duration.ofSeconds(60), rejection);
                        own += gate.blockFor(request, "bucket-a").isPresent() ? 1 : 0;
                    }
                    return own;
                }));
            }
            int total = 0;
            for (final Future<Integer> future : futures) {
                total += future.get(30, TimeUnit.SECONDS);
            }
            found = total;
        } finally {
            executor.shutdownNow();
        }

        assertSoftly(softly -> {
            softly.assertThat(found).isEqualTo(callers * rounds);
            softly.assertThat(gate.size()).isEqualTo(callers);
        });
    }

    @Test
    void block_shouldDropEveryExpiredBlock_whenManyThreadsRecordAfterTheLimitEnded() throws Exception {
        final int callers = 8;
        for (int caller = 0; caller < callers; caller++) {
            gate.block(
                    request("https://api.example.test/user", "Bearer old-" + caller),
                    null,
                    Duration.ofSeconds(60),
                    rejection);
        }
        clock.advance(Duration.ofSeconds(61));
        final CyclicBarrier start = new CyclicBarrier(callers);
        final ExecutorService executor = Executors.newFixedThreadPool(callers);

        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                final HttpRequest request = request("https://api.example.test/user", "Bearer old-" + caller);
                futures.add(executor.submit(() -> {
                    start.await();
                    gate.block(request, null, Duration.ofSeconds(60), rejection);
                    return null;
                }));
            }
            for (final Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(gate.size()).isEqualTo(callers);
    }

    @Test
    void remaining_shouldCountDownAndNeverBeNegative_whenTimePasses() {
        gate.block(request("https://api.example.test/user", SECRET), null, Duration.ofSeconds(60), rejection);
        final RateLimitGate.Block block = gate.blockFor(request("https://api.example.test/user", SECRET), "bucket-a")
                .orElseThrow();

        final Duration before = gate.remaining(block);
        clock.advance(Duration.ofSeconds(100));
        final Duration after = gate.remaining(block);

        assertSoftly(softly -> {
            softly.assertThat(before).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(after).isEqualTo(Duration.ZERO);
        });
    }

    @Test
    void rejection_shouldRewriteHeadersAndKeepStatusAndBody_whenReplayed() {
        gate.block(request("https://api.example.test/user", SECRET), "bucket-a", Duration.ofSeconds(60), rejection);
        final RateLimitGate.Block block = gate.blockFor(request("https://api.example.test/user", SECRET), "bucket-a")
                .orElseThrow();

        final HttpResponse replay = gate.rejection(block, Duration.ofMillis(59_001));

        assertSoftly(softly -> {
            softly.assertThat(replay.statusCode()).isEqualTo(429);
            softly.assertThat(replay.requireBodyBytes()).containsExactly(1, 2, 3);
            softly.assertThat(replay.header(DATE)).isEmpty();
            softly.assertThat(replay.header(RETRY_AFTER)).contains("60");
            softly.assertThat(replay.header("X-Custom")).contains("kept");
            softly.assertThat(replay.header(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                    .contains(RateLimitGateHeaders.BLOCKED);
        });
    }

    @Test
    void rejection_shouldReplayHeadersWithFramingNames_whenResponseCameFromTheWire() {
        final HttpResponse wire = new HttpResponse(
                429,
                HttpHeaders.fromWire(Map.of(
                        CONTENT_LENGTH, List.of("3"),
                        TRANSFER_ENCODING, List.of("identity"),
                        DATE, List.of("Fri, 09 Oct 2026 12:00:00 GMT"))),
                HttpBody.of(new byte[] {1, 2, 3}));
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, "bucket-a", Duration.ofSeconds(60), wire);
        final RateLimitGate.Block block = gate.blockFor(request, "bucket-a").orElseThrow();

        final HttpResponse replay = gate.rejection(block, Duration.ofSeconds(60));

        assertSoftly(softly -> {
            softly.assertThat(replay.header(CONTENT_LENGTH)).contains("3");
            softly.assertThat(replay.header(DATE)).isEmpty();
            softly.assertThat(replay.header(RETRY_AFTER)).contains("60");
        });
    }

    @Test
    void rejection_shouldKeepJoinedValuesOnce_whenHeaderHadSeveralValues() {
        final HttpResponse wire = new HttpResponse(
                429,
                HttpHeaders.fromWire(Map.of(
                        "Link",
                                List.of(
                                        "<https://api.example.test/a>; rel=\"next\"",
                                        "<https://api.example.test/b>; rel=\"last\""),
                        "Vary", List.of("Accept", "Authorization"))),
                HttpBody.absent());
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, "bucket-a", Duration.ofSeconds(60), wire);
        final RateLimitGate.Block block = gate.blockFor(request, "bucket-a").orElseThrow();

        final HttpResponse replay = gate.rejection(block, Duration.ofSeconds(60));

        assertSoftly(softly -> {
            softly.assertThat(replay.header("Link")).isEqualTo(wire.header("Link"));
            softly.assertThat(replay.header("Vary")).contains("Accept, Authorization");
        });
    }

    @Test
    void rejection_shouldNotRoundUp_whenRemainingIsWholeSeconds() {
        gate.block(request("https://api.example.test/user", SECRET), null, Duration.ofSeconds(60), rejection);
        final RateLimitGate.Block block = gate.blockFor(request("https://api.example.test/user", SECRET), "bucket-a")
                .orElseThrow();

        assertThat(gate.rejection(block, Duration.ofSeconds(45)).header(RETRY_AFTER))
                .contains("45");
    }

    @Test
    void block_shouldNeverKeepTheAuthorizationValue_whenCredentialIsHashed() throws Exception {
        gate.block(request("https://api.example.test/user", SECRET), "bucket-a", Duration.ofSeconds(60), rejection);

        final Field blocks = RateLimitGate.class.getDeclaredField("blocks");
        blocks.setAccessible(true);
        final Map<?, ?> keys = (Map<?, ?>) blocks.get(gate);

        assertThat(keys.keySet().toString())
                .doesNotContain("tok_super_secret_token")
                .doesNotContain("Bearer");
    }

    @Test
    void constructor_shouldThrow_whenClockIsNull() {
        final Duration duration = Duration.ofHours(1);
        final Duration durationValue = Duration.ofSeconds(60);
        final Duration duration3 = Duration.ofMinutes(5);

        final Throwable thrown = catchThrowable(() -> new RateLimitGate(null, durationValue, duration3, duration));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    @Test
    void blockFor_shouldFindBlock_whenCredentialIdentityIsTheSameAndTokenRotated() {
        gate.block(identified("Bearer old", "app:1/installation:42"), "bucket-a", Duration.ofSeconds(60), rejection);

        assertThat(gate.blockFor(identified("Bearer new", "app:1/installation:42"), "bucket-a"))
                .isPresent();
    }

    @Test
    void blockFor_shouldBeEmpty_whenCredentialIdentityDiffersEvenWithSameAuthorization() {
        gate.block(identified(SECRET, "app:1/installation:42"), "bucket-a", Duration.ofSeconds(60), rejection);

        assertThat(gate.blockFor(identified(SECRET, "app:1/installation:43"), "bucket-a"))
                .isEmpty();
    }

    @Test
    void unnamedWait_shouldKeepLevel_whenLimitArrivesWhileBlockHolds() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        final Duration first = gate.unnamedWait(request, null);
        gate.block(request, null, first, rejection);

        final Duration inFlight = gate.unnamedWait(request, null);

        assertThat(inFlight).isEqualTo(first);
    }

    @Test
    void unnamedWait_shouldKeepLevel_whenLimitArrivesBeforeEarlierBlockIsRecorded() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        final Duration first = gate.unnamedWait(request, null);

        final Duration inFlight = gate.unnamedWait(request, null);

        assertThat(inFlight).isEqualTo(first);
    }

    @Test
    void unnamedWait_shouldGiveSameWait_whenManyLimitsArriveTogether() throws Exception {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        final int callers = 16;
        final CyclicBarrier start = new CyclicBarrier(callers);
        final ExecutorService executor = Executors.newFixedThreadPool(callers);

        final Set<Duration> waits;
        try {
            final List<Future<Duration>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return gate.unnamedWait(request, null);
                }));
            }
            waits = new HashSet<>();
            for (final Future<Duration> future : futures) {
                waits.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(waits).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void unnamedWait_shouldDouble_whenLimitArrivesAfterBlockEnded() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, null, gate.unnamedWait(request, null), rejection);
        clock.advance(Duration.ofSeconds(60));

        assertThat(gate.unnamedWait(request, null)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void unnamedWait_shouldStartAtBase_whenQuietPeriodPassedAfterBlockEnded() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        gate.block(request, null, gate.unnamedWait(request, null), rejection);
        clock.advance(Duration.ofSeconds(60 + 300));

        assertThat(gate.unnamedWait(request, null)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void unnamedWait_shouldCapAtMaxWait_whenLevelsGrow() {
        final HttpRequest request = request("https://api.example.test/user", SECRET);
        Duration wait = Duration.ZERO;
        for (int limit = 0; limit < 30; limit++) {
            wait = gate.unnamedWait(request, null);
            gate.block(request, null, wait, rejection);
            clock.advance(wait);
        }

        assertThat(wait).isEqualTo(Duration.ofMinutes(5));
    }

    private static HttpRequest identified(final String authorization, final String credentialId) {
        return new HttpRequest(
                HttpMethod.GET,
                ParsedUrl.of(URI.create("https://api.example.test/user")),
                HttpHeaders.of(AUTHORIZATION, authorization),
                null,
                credentialId);
    }

    private static HttpRequest request(final String uri, final String authorization) {
        final HttpHeaders headers =
                authorization == null ? HttpHeaders.empty() : HttpHeaders.of(AUTHORIZATION, authorization);
        return new HttpRequest(HttpMethod.GET, URI.create(uri), headers, null);
    }
}
