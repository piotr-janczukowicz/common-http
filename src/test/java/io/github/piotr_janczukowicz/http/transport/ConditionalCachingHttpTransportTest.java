package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CACHE_CONTROL;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LAST_MODIFIED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LINK;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RANGE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.VARY;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.http.ScriptedTransport;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class ConditionalCachingHttpTransportTest {

    private static final String ETAG_VALUE = "W/\"abc\"";
    private static final String ETAG_NEXT = "W/\"def\"";
    private static final String LAST_MODIFIED_VALUE = "Wed, 21 Oct 2015 07:28:00 GMT";
    private static final String URL = "https://api.example.test/repos/owner/repo";
    private static final String OTHER_URL = "https://api.example.test/repos/owner/other";
    private static final String BODY = "{\"name\":\"repo\"}";
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    private static final String VERSION_HEADER = "X-Api-Version";
    private static final String REMAINING_HEADER = "X-Remaining";

    private static ConditionalCachingHttpTransport cached(final ScriptedTransport delegate) {
        return cached(delegate.transport());
    }

    private static ConditionalCachingHttpTransport cached(final HttpTransport delegate) {
        return new ConditionalCachingHttpTransport(
                delegate, MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
    }

    /** A cache whose first request already got a 200 with an ETag; {@code afterFirst} are the replies that follow. */
    private static CachedFixture primedCache(final HttpResponse... afterFirst) throws Exception {
        final List<HttpResponse> replies = new ArrayList<>();
        replies.add(cacheable());
        replies.addAll(List.of(afterFirst));
        final ScriptedTransport delegate = ScriptedTransport.replying(replies.toArray(HttpResponse[]::new));
        final CachedFixture fixture = new CachedFixture(delegate, cached(delegate));
        fixture.get();
        return fixture;
    }

    private record CachedFixture(ScriptedTransport delegate, ConditionalCachingHttpTransport transport) {

        HttpResponse get() throws Exception {
            return transport.execute(ConditionalCachingHttpTransportTest.get(URL, HttpHeaders.empty()));
        }
    }

    @Test
    void execute_shouldReturnStoredBody_whenNotModified() throws Exception {
        final CachedFixture cache = primedCache(response(304, null, ETAG, ETAG_VALUE));

        final HttpResponse response = cache.get();

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(text(response)).isEqualTo(BODY);
            softly.assertThat(ifNoneMatch(cache.delegate(), 1)).contains(ETAG_VALUE);
        });
    }

    @Test
    void execute_shouldSendIfModifiedSince_whenOnlyLastModifiedKept() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, LAST_MODIFIED, LAST_MODIFIED_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(delegate.request(1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
            softly.assertThat(ifNoneMatch(delegate, 1)).isEmpty();
        });
    }

    @Test
    void execute_shouldSendBothValidators_whenEtagAndLastModifiedKept() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, LAST_MODIFIED, LAST_MODIFIED_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
            softly.assertThat(delegate.request(1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
        });
    }

    @Test
    void execute_shouldNotCache_whenResponseHasNoValidator() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(response(200, BODY), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldPassRangeRequestThrough_whenFullResponseIsKept() throws Exception {
        final CachedFixture cache = primedCache(response(206, "{\"na", ETAG, ETAG_VALUE));

        final HttpResponse response = cache.transport().execute(get(URL, HttpHeaders.of(RANGE, "bytes=0-3")));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(206);
            softly.assertThat(ifNoneMatch(cache.delegate(), 1)).isEmpty();
        });
    }

    @Test
    void execute_shouldNotKeepResponse_whenStatusIsPartialContent() throws Exception {
        final ScriptedTransport delegate =
                ScriptedTransport.replying(response(206, BODY, ETAG, ETAG_VALUE), cacheable());
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(RANGE, "bytes=0-4")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldAnswerEveryCall_whenManyThreadsFillAndEvictASmallCache() throws Exception {
        final HttpTransport delegate = HttpTransportMocks.mock(invocation -> {
            if (!"execute".equals(invocation.getMethod().getName())) {
                return null;
            }
            final HttpRequest sent = invocation.getArgument(0);
            return sent.headers().get(IF_NONE_MATCH).isPresent() ? response(304, null, ETAG, ETAG_VALUE) : cacheable();
        });
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 4, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        final int threads = 8;
        final int rounds = 300;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final List<Future<Integer>> results = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            final int offset = thread;
            results.add(executor.submit(() -> {
                int answered = 0;
                for (int round = 0; round < rounds; round++) {
                    final HttpResponse response =
                            transport.execute(get(URL + "/" + (round + offset) % 20, HttpHeaders.empty()));
                    if (response.statusCode() == 200 && BODY.equals(text(response))) {
                        answered++;
                    }
                }
                return answered;
            }));
        }
        int answered = 0;
        for (final Future<Integer> result : results) {
            answered += result.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(answered).isEqualTo(threads * rounds);
    }

    @ParameterizedTest
    @CsvSource({"404,false", "410,false", "403,true", "429,true", "500,true", "503,true"})
    void execute_shouldKeepEntryOnlyWhenStatusSaysNothingAboutCopy_whenConditionalRequestGetsStatus(
            final int secondStatus, final boolean expectIfNoneMatch) throws Exception {
        final HttpResponse third = expectIfNoneMatch ? response(304, null) : response(200, BODY);
        final CachedFixture cache = primedCache(response(secondStatus, "{}"), third);
        cache.get();

        cache.get();

        assertThat(ifNoneMatch(cache.delegate(), 2))
                .isEqualTo(expectIfNoneMatch ? Optional.of(ETAG_VALUE) : Optional.empty());
    }

    @Test
    void execute_shouldDropEntry_whenConditionalRequestGetsNoStoreResponse() throws Exception {
        final CachedFixture cache =
                primedCache(response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "no-store"), response(200, BODY));
        cache.get();

        cache.get();

        assertThat(ifNoneMatch(cache.delegate(), 2)).isEmpty();
    }

    @Test
    void execute_shouldNotCache_whenStatusIsNotOk() throws Exception {
        final ScriptedTransport delegate =
                ScriptedTransport.replying(response(404, "{}", ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("differingCredentials")
    void execute_shouldNotShareEntry_whenCredentialDiffers(final HttpRequest first, final HttpRequest second)
            throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(first);

        transport.execute(second);

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    private static Stream<Arguments> differingCredentials() {
        return Stream.of(
                Arguments.of(
                        get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer one")),
                        get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer two"))),
                Arguments.of(
                        get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer same"), "app:1/installation:1"),
                        get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer same"), "app:1/installation:2")));
    }

    @Test
    void execute_shouldSendConditionalRequest_whenCredentialIdRepeats() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer one"), "app:1/installation:1"));

        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer two"), "app:1/installation:1"));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotShareEntry_whenAcceptDiffers() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(ACCEPT, "application/json")));

        transport.execute(get(URL, HttpHeaders.of(ACCEPT, "application/vnd.example.raw")));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenVaryingHeaderDiffers() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = new ConditionalCachingHttpTransport(
                delegate.transport(), 16, 1024, MAX_TOTAL_BYTES, Set.of(ACCEPT, VERSION_HEADER));
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenAcceptEncodingDiffers() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(ACCEPT_ENCODING, "gzip")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldRevalidate_whenVaryHeaderValueMatches() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, VARY, VERSION_HEADER), response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotRevalidate_whenVaryHeaderValueDiffers() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, VARY, VERSION_HEADER), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldNotKeep_whenVaryIsStar() throws Exception {
        final ScriptedTransport delegate =
                ScriptedTransport.replying(response(200, BODY, ETAG, ETAG_VALUE, VARY, "*"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldSkipEmptyVaryElements_whenVaryListsThem() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, VARY, "X-One, , X-Two"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotKeep_whenBodyLengthIsUnknown() throws Exception {
        final ScriptedTransport delegate =
                ScriptedTransport.replying(response(200, null, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldReturnResponse_whenEntryWasEvictedDuringRevalidation() throws Exception {
        final AtomicReference<ConditionalCachingHttpTransport> transport = new AtomicReference<>();
        final Deque<HttpResponse> responses = new ArrayDeque<>();
        responses.add(cacheable());
        responses.add(response(404, null));
        final HttpTransport delegate = HttpTransportMocks.mock(invocation -> {
            if (!"execute".equals(invocation.getMethod().getName())) {
                return null;
            }
            if (responses.size() == 1 && transport.get() != null) {
                // another request keeps its copy meanwhile and, with room for one entry, pushes this one out
                final ConditionalCachingHttpTransport self = transport.getAndSet(null);
                responses.addFirst(cacheable());
                self.execute(get(OTHER_URL, HttpHeaders.empty()));
            }
            return responses.poll();
        });
        transport.set(
                new ConditionalCachingHttpTransport(delegate, 1, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT)));
        transport.get().execute(get(URL, HttpHeaders.empty()));
        final ConditionalCachingHttpTransport cache = transport.get();

        final HttpResponse response = cache.execute(get(URL, HttpHeaders.empty()));

        assertThat(response.hasStatus(HttpStatus.NOT_FOUND)).isTrue();
    }

    @Test
    void execute_shouldIgnoreVersionHeader_whenHeaderNotVarying() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(304, null));
        final ConditionalCachingHttpTransport transport = new ConditionalCachingHttpTransport(
                delegate.transport(), MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotShareEntry_whenQueryDiffers() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL + "?page=1", HttpHeaders.empty()));

        transport.execute(get(URL + "?page=2", HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PUT", "PATCH", "DELETE"})
    void execute_shouldNotCacheOrRevalidate_whenMethodIsNotGet(final HttpMethod method) throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(request(method, URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(request(method, URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(ifNoneMatch(delegate, 1)).isEmpty();
        });
    }

    @Test
    void execute_shouldPassThroughWithCallerIfNoneMatch_whenCallerSetsIt() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.of(IF_NONE_MATCH, "W/\"caller\"")));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(ifNoneMatch(delegate, 1)).contains("W/\"caller\"");
            softly.assertThat(delegate.calls()).isEqualTo(2);
        });
    }

    @Test
    void execute_shouldPassThroughWithCallerIfModifiedSince_whenCallerSetsIt() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response =
                transport.execute(get(URL, HttpHeaders.of(IF_MODIFIED_SINCE, LAST_MODIFIED_VALUE)));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(delegate.request(1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
            softly.assertThat(ifNoneMatch(delegate, 1)).isEmpty();
        });
    }

    @Test
    void execute_shouldReturnNotModifiedUnchanged_whenNoEntryKept() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(response.etag()).contains(ETAG_VALUE);
            softly.assertThat(delegate.calls()).isEqualTo(1);
        });
    }

    @Test
    void execute_shouldEvictLeastRecentlyUsed_whenMaxEntriesExceeded() throws Exception {
        final String urlC = "https://api.example.test/repos/owner/third";
        final ScriptedTransport delegate = ScriptedTransport.replying(
                cacheable(),
                response(200, BODY, ETAG, ETAG_NEXT),
                response(304, null),
                response(200, BODY, ETAG, "W/\"third\""),
                response(200, BODY));
        final ConditionalCachingHttpTransport transport = new ConditionalCachingHttpTransport(
                delegate.transport(), 2, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        fetch(transport, URL, OTHER_URL, URL);
        transport.execute(get(urlC, HttpHeaders.empty()));

        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(delegate, 2)).contains(ETAG_VALUE);
            softly.assertThat(ifNoneMatch(delegate, 4)).isEmpty();
        });
    }

    @Test
    void execute_shouldNotKeepBody_whenLargerThanLimit() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, "12345", ETAG, ETAG_VALUE),
                response(200, "1234", ETAG, ETAG_NEXT),
                response(200, "12345", ETAG, ETAG_VALUE),
                response(304, null));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate.transport(), 16, 4, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        fetch(transport, URL, OTHER_URL);

        fetch(transport, URL, OTHER_URL);

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(delegate, 2)).isEmpty();
            softly.assertThat(ifNoneMatch(delegate, 3)).contains(ETAG_NEXT);
        });
    }

    @Test
    void execute_shouldOverlayRateLimitHeaders_whenNotModified() throws Exception {
        final String link = "<https://api.example.test/repos/owner/repo/issues?page=2>; rel=\"next\"";
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(
                        200,
                        BODY,
                        ETAG,
                        ETAG_VALUE,
                        DATE,
                        "Tue, 01 Sep 2026 10:00:00 GMT",
                        REMAINING_HEADER,
                        "4999",
                        LINK,
                        link,
                        CONTENT_TYPE,
                        "application/json"),
                response(
                        304,
                        null,
                        DATE,
                        "Tue, 01 Sep 2026 10:00:05 GMT",
                        REMAINING_HEADER,
                        "4998",
                        CONTENT_TYPE,
                        "text/plain",
                        CONTENT_LENGTH,
                        "0"));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(text(response)).isEqualTo(BODY);
            softly.assertThat(response.header(DATE)).contains("Tue, 01 Sep 2026 10:00:05 GMT");
            softly.assertThat(response.header(REMAINING_HEADER)).contains("4998");
            softly.assertThat(response.header(LINK)).contains(link);
            softly.assertThat(response.header(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(response.etag()).contains(ETAG_VALUE);
        });
    }

    @Test
    void execute_shouldSendRefreshedEtag_whenNotModifiedCarriesNewEtag() throws Exception {
        final CachedFixture cache = primedCache(response(304, null, ETAG, ETAG_NEXT), response(304, null));
        cache.get();

        final HttpResponse response = cache.get();

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(cache.delegate(), 2)).contains(ETAG_NEXT);
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(response.etag()).contains(ETAG_NEXT);
        });
    }

    @Test
    void stream_shouldPassThroughWithoutCaching_whenCalled() throws Exception {
        final HttpTransport delegate = HttpTransportMocks.mock();
        final HttpStreamResponse opened = mock(HttpStreamResponse.class);
        when(delegate.stream(any())).thenReturn(opened);
        final ConditionalCachingHttpTransport transport = cached(delegate);

        final HttpStreamResponse response = transport.stream(get(URL, HttpHeaders.empty()));

        assertThat(response).isSameAs(opened);
        verify(delegate, never()).execute(any());
    }

    @Test
    void close_shouldCloseDelegate_whenClosed() {
        final HttpTransport delegate = HttpTransportMocks.mock();
        final ConditionalCachingHttpTransport transport = cached(delegate);

        transport.close();

        verify(delegate).close();
    }

    @Test
    void constructor_shouldThrow_whenDelegateNull() {
        final Throwable thrown = catchThrowable(() -> cached((HttpTransport) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("delegate");
    }

    @Test
    void constructor_shouldThrow_whenMaxEntriesBelowOne() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Set<String> headers = Set.of(ACCEPT);

        final Throwable thrown = catchThrowable(
                () -> new ConditionalCachingHttpTransport(delegate, 0, MAX_BODY_BYTES, MAX_TOTAL_BYTES, headers));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxEntries");
    }

    @Test
    void constructor_shouldThrow_whenMaxBodyBytesNegative() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Set<String> headers = Set.of(ACCEPT);

        final Throwable thrown = catchThrowable(
                () -> new ConditionalCachingHttpTransport(delegate, MAX_ENTRIES, -1, MAX_TOTAL_BYTES, headers));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBodyBytes");
    }

    @Test
    void constructor_shouldThrow_whenVaryingHeaderBlank() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Set<String> headers = Set.of(" ");

        final Throwable thrown = catchThrowable(() ->
                new ConditionalCachingHttpTransport(delegate, MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, headers));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_shouldEvictLeastRecentlyUsed_whenTotalBytesExceeded() throws Exception {
        final String kilobyteAndAHalf = "x".repeat(1536);
        final String urlC = "https://api.example.test/repos/owner/third";
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, kilobyteAndAHalf, ETAG, ETAG_VALUE),
                response(200, kilobyteAndAHalf, ETAG, ETAG_NEXT),
                response(200, kilobyteAndAHalf, ETAG, "W/\"third\""),
                response(304, null),
                response(304, null),
                response(200, BODY));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate.transport(), 16, MAX_BODY_BYTES, 3 * 1024, Set.of(ACCEPT));
        fetch(transport, URL, OTHER_URL);
        transport.execute(get(urlC, HttpHeaders.empty()));

        transport.execute(get(OTHER_URL, HttpHeaders.empty()));
        transport.execute(get(urlC, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(delegate, 3)).contains(ETAG_NEXT);
            softly.assertThat(ifNoneMatch(delegate, 4)).contains("W/\"third\"");
            softly.assertThat(ifNoneMatch(delegate, 5)).isEmpty();
        });
    }

    @Test
    void execute_shouldNotDoubleCountBytes_whenEntryReplaced() throws Exception {
        final String kilobyteAndAHalf = "x".repeat(1536);
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, kilobyteAndAHalf, ETAG, ETAG_VALUE),
                response(200, kilobyteAndAHalf, ETAG, ETAG_NEXT),
                response(200, kilobyteAndAHalf, ETAG, "W/\"other\""),
                response(304, null),
                response(304, null));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate.transport(), 16, MAX_BODY_BYTES, 3 * 1024, Set.of(ACCEPT));
        fetch(transport, URL, URL, OTHER_URL);

        fetch(transport, URL, OTHER_URL);

        assertSoftly(softly -> {
            softly.assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
            softly.assertThat(ifNoneMatch(delegate, 3)).contains(ETAG_NEXT);
            softly.assertThat(ifNoneMatch(delegate, 4)).contains("W/\"other\"");
        });
    }

    @Test
    void execute_shouldNotKeepBody_whenLargerThanTotalLimit() throws Exception {
        final ScriptedTransport delegate =
                ScriptedTransport.replying(response(200, "12345", ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate.transport(), 16, MAX_BODY_BYTES, 4, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldNotKeepResponse_whenRequestHasNoStore() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(cacheable(), cacheable());
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "no-store")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldBypassKeptCopy_whenRequestHasNoStoreAmongOtherDirectives() throws Exception {
        final CachedFixture cache = primedCache(cacheable());

        cache.transport().execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "max-age=0, No-Store")));

        assertThat(ifNoneMatch(cache.delegate(), 1)).isEmpty();
    }

    @Test
    void execute_shouldRevalidateKeptCopy_whenRequestHasNoCache() throws Exception {
        final CachedFixture cache = primedCache(response(304, null, ETAG, ETAG_VALUE));

        final HttpResponse response =
                cache.transport().execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "no-cache", "Pragma", "no-cache")));

        assertThat(ifNoneMatch(cache.delegate(), 1)).contains(ETAG_VALUE);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void execute_shouldNotRevalidate_whenCacheControlHasNoStore() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "no-store"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldNotRevalidate_whenCacheControlHasNoStoreAmongOtherDirectives() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "Private, NO-STORE"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).isEmpty();
    }

    @Test
    void execute_shouldKeepEntry_whenCacheControlHasOnlyMaxAge() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "private, max-age=60"), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldKeepEntry_whenNoStoreIsPartOfAnotherToken() throws Exception {
        final ScriptedTransport delegate = ScriptedTransport.replying(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "x-no-store"), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(ifNoneMatch(delegate, 1)).contains(ETAG_VALUE);
    }

    @Test
    void constructor_shouldThrow_whenMaxTotalBytesNegative() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Set<String> headers2 = Set.of(ACCEPT);

        final Throwable thrown = catchThrowable(
                () -> new ConditionalCachingHttpTransport(delegate, MAX_ENTRIES, MAX_BODY_BYTES, -1L, headers2));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTotalBytes");
    }

    private static HttpRequest get(final String url, final HttpHeaders headers) {
        return request(HttpMethod.GET, url, headers);
    }

    private static HttpRequest get(final String url, final HttpHeaders headers, final String credentialId) {
        return new HttpRequest(HttpMethod.GET, ParsedUrl.of(URI.create(url)), headers, HttpBody.absent(), credentialId);
    }

    private static void fetch(final ConditionalCachingHttpTransport transport, final String... urls) throws Exception {
        for (final String url : urls) {
            transport.execute(get(url, HttpHeaders.empty()));
        }
    }

    private static Optional<String> ifNoneMatch(final ScriptedTransport delegate, final int index) {
        return delegate.request(index).headers().get(IF_NONE_MATCH);
    }

    private static HttpResponse cacheable() {
        return response(200, BODY, ETAG, ETAG_VALUE);
    }

    private static HttpRequest request(final HttpMethod method, final String url, final HttpHeaders headers) {
        return new HttpRequest(method, ParsedUrl.of(URI.create(url)), headers, HttpBody.absent());
    }

    private static String text(final HttpResponse response) {
        return new String(response.requireBodyBytes(), StandardCharsets.UTF_8);
    }
}
