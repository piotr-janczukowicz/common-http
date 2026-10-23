package pl.piotrjanczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ACCEPT;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ACCEPT_ENCODING;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CACHE_CONTROL;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ETAG;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LAST_MODIFIED;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LINK;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.RANGE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.VARY;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpResponses;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;
import pl.piotrjanczukowicz.test.UnitTest;

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

    private static ConditionalCachingHttpTransport cached(final HttpTransport delegate) {
        return new ConditionalCachingHttpTransport(
                delegate, MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
    }

    @Test
    void execute_shouldReturnStoredBody_whenNotModified() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(text(response)).isEqualTo(BODY);
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_VALUE);
        });
    }

    @Test
    void execute_shouldSendIfModifiedSince_whenOnlyLastModifiedKept() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, LAST_MODIFIED, LAST_MODIFIED_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldSendBothValidators_whenEtagAndLastModifiedKept() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE, LAST_MODIFIED, LAST_MODIFIED_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_VALUE);
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
        });
    }

    @Test
    void execute_shouldNotCache_whenResponseHasNoValidator() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldDropEntry_whenConditionalRequestGetsStatusThatIsNotKept() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(404, "{}"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldPassRangeRequestThrough_whenFullResponseIsKept() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(206, "{\"na", ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.of(RANGE, "bytes=0-3")));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(206);
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldNotKeepResponse_whenStatusIsPartialContent() throws Exception {
        final HttpTransport delegate =
                scripted(response(206, BODY, ETAG, ETAG_VALUE), response(200, BODY, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(RANGE, "bytes=0-4")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldAnswerEveryCall_whenManyThreadsFillAndEvictASmallCache() throws Exception {
        final HttpTransport delegate = HttpTransportMocks.mock(invocation -> {
            if (!"execute".equals(invocation.getMethod().getName())) {
                return null;
            }
            final HttpRequest sent = invocation.getArgument(0);
            return sent.headers().get(IF_NONE_MATCH).isPresent()
                    ? response(304, null, ETAG, ETAG_VALUE)
                    : response(200, BODY, ETAG, ETAG_VALUE);
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

    @Test
    void execute_shouldDropEntry_whenConditionalRequestGetsGone() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(410, "{}"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429, 500, 503})
    void execute_shouldKeepEntry_whenConditionalRequestGetsStatusThatSaysNothingAboutTheCopy(final int status)
            throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(status, "{}"), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldDropEntry_whenConditionalRequestGetsNoStoreResponse() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE),
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "no-store"),
                response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotCache_whenStatusIsNotOk() throws Exception {
        final HttpTransport delegate = scripted(response(404, "{}", ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenCredentialDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer one")));

        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer two")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenCredentialIdDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer same"), "app:1/installation:1"));

        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer same"), "app:1/installation:2"));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldSendConditionalRequest_whenCredentialIdRepeats() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer one"), "app:1/installation:1"));

        transport.execute(get(URL, HttpHeaders.of(AUTHORIZATION, "Bearer two"), "app:1/installation:1"));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotShareEntry_whenAcceptDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(ACCEPT, "application/json")));

        transport.execute(get(URL, HttpHeaders.of(ACCEPT, "application/vnd.example.raw")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenVaryingHeaderDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = new ConditionalCachingHttpTransport(
                delegate, 16, 1024, MAX_TOTAL_BYTES, Set.of(ACCEPT, VERSION_HEADER));
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotShareEntry_whenAcceptEncodingDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(ACCEPT_ENCODING, "gzip")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldRevalidate_whenVaryHeaderValueMatches() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE, VARY, VERSION_HEADER), response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotRevalidate_whenVaryHeaderValueDiffers() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE, VARY, VERSION_HEADER), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotKeep_whenVaryIsStar() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE, VARY, "*"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldSkipEmptyVaryElements_whenVaryListsThem() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE, VARY, "X-One, , X-Two"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotKeep_whenBodyLengthIsUnknown() throws Exception {
        final HttpTransport delegate = scripted(response(200, null, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldReturnResponse_whenEntryWasEvictedDuringRevalidation() throws Exception {
        final AtomicReference<ConditionalCachingHttpTransport> transport = new AtomicReference<>();
        final Deque<HttpResponse> responses = new ArrayDeque<>();
        responses.add(response(200, BODY, ETAG, ETAG_VALUE));
        responses.add(response(404, null));
        final HttpTransport delegate = HttpTransportMocks.mock(invocation -> {
            if (!"execute".equals(invocation.getMethod().getName())) {
                return null;
            }
            if (responses.size() == 1 && transport.get() != null) {
                // another request keeps its copy meanwhile and, with room for one entry, pushes this one out
                final ConditionalCachingHttpTransport self = transport.getAndSet(null);
                responses.addFirst(response(200, BODY, ETAG, ETAG_VALUE));
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
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = new ConditionalCachingHttpTransport(
                delegate, MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2026-03-10")));

        transport.execute(get(URL, HttpHeaders.of(VERSION_HEADER, "2022-11-28")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldNotShareEntry_whenQueryDiffers() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL + "?page=1", HttpHeaders.empty()));

        transport.execute(get(URL + "?page=2", HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpMethod.class,
            names = {"POST", "PUT", "PATCH", "DELETE"})
    void execute_shouldNotCacheOrRevalidate_whenMethodIsNotGet(final HttpMethod method) throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(request(method, URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(request(method, URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldPassThroughWithCallerIfNoneMatch_whenCallerSetsIt() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.of(IF_NONE_MATCH, "W/\"caller\"")));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .contains("W/\"caller\"");
            softly.assertThat(requestCount(delegate)).isEqualTo(2);
        });
    }

    @Test
    void execute_shouldPassThroughWithCallerIfModifiedSince_whenCallerSetsIt() throws Exception {
        final HttpTransport delegate = scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response =
                transport.execute(get(URL, HttpHeaders.of(IF_MODIFIED_SINCE, LAST_MODIFIED_VALUE)));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_MODIFIED_SINCE))
                    .contains(LAST_MODIFIED_VALUE);
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldReturnNotModifiedUnchanged_whenNoEntryKept() throws Exception {
        final HttpTransport delegate = scripted(response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
            softly.assertThat(response.etag()).contains(ETAG_VALUE);
            softly.assertThat(requestCount(delegate)).isEqualTo(1);
        });
    }

    @Test
    void execute_shouldEvictLeastRecentlyUsed_whenMaxEntriesExceeded() throws Exception {
        final String urlC = "https://api.example.test/repos/owner/third";
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE),
                response(200, BODY, ETAG, ETAG_NEXT),
                response(304, null),
                response(200, BODY, ETAG, "W/\"third\""),
                response(200, BODY));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 2, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(urlC, HttpHeaders.empty()));

        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_VALUE);
            softly.assertThat(sentRequest(delegate, 4).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldNotKeepBody_whenLargerThanLimit() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, "12345", ETAG, ETAG_VALUE),
                response(200, "1234", ETAG, ETAG_NEXT),
                response(200, "12345", ETAG, ETAG_VALUE),
                response(304, null));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 16, 4, MAX_TOTAL_BYTES, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH))
                    .isEmpty();
            softly.assertThat(sentRequest(delegate, 3).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_NEXT);
        });
    }

    @Test
    void execute_shouldOverlayRateLimitHeaders_whenNotModified() throws Exception {
        final String link = "<https://api.example.test/repos/owner/repo/issues?page=2>; rel=\"next\"";
        final HttpTransport delegate = scripted(
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
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE), response(304, null, ETAG, ETAG_NEXT), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response = transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 2).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_NEXT);
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
        final Throwable thrown = catchThrowable(() -> cached(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("delegate");
    }

    @Test
    void constructor_shouldThrow_whenMaxEntriesBelowOne() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() ->
                new ConditionalCachingHttpTransport(delegate, 0, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(ACCEPT)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxEntries");
    }

    @Test
    void constructor_shouldThrow_whenMaxBodyBytesNegative() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(
                () -> new ConditionalCachingHttpTransport(delegate, MAX_ENTRIES, -1, MAX_TOTAL_BYTES, Set.of(ACCEPT)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBodyBytes");
    }

    @Test
    void constructor_shouldThrow_whenVaryingHeaderBlank() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() -> new ConditionalCachingHttpTransport(
                delegate, MAX_ENTRIES, MAX_BODY_BYTES, MAX_TOTAL_BYTES, Set.of(" ")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_shouldEvictLeastRecentlyUsed_whenTotalBytesExceeded() throws Exception {
        final String kilobyteAndAHalf = "x".repeat(1536);
        final String urlC = "https://api.example.test/repos/owner/third";
        final HttpTransport delegate = scripted(
                response(200, kilobyteAndAHalf, ETAG, ETAG_VALUE),
                response(200, kilobyteAndAHalf, ETAG, ETAG_NEXT),
                response(200, kilobyteAndAHalf, ETAG, "W/\"third\""),
                response(304, null),
                response(304, null),
                response(200, BODY));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 16, MAX_BODY_BYTES, 3 * 1024, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));
        transport.execute(get(urlC, HttpHeaders.empty()));

        transport.execute(get(OTHER_URL, HttpHeaders.empty()));
        transport.execute(get(urlC, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 3).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_NEXT);
            softly.assertThat(sentRequest(delegate, 4).headers().get(IF_NONE_MATCH))
                    .contains("W/\"third\"");
            softly.assertThat(sentRequest(delegate, 5).headers().get(IF_NONE_MATCH))
                    .isEmpty();
        });
    }

    @Test
    void execute_shouldNotDoubleCountBytes_whenEntryReplaced() throws Exception {
        final String kilobyteAndAHalf = "x".repeat(1536);
        final HttpTransport delegate = scripted(
                response(200, kilobyteAndAHalf, ETAG, ETAG_VALUE),
                response(200, kilobyteAndAHalf, ETAG, ETAG_NEXT),
                response(200, kilobyteAndAHalf, ETAG, "W/\"other\""),
                response(304, null),
                response(304, null));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 16, MAX_BODY_BYTES, 3 * 1024, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));
        transport.execute(get(OTHER_URL, HttpHeaders.empty()));

        assertSoftly(softly -> {
            softly.assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_VALUE);
            softly.assertThat(sentRequest(delegate, 3).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_NEXT);
            softly.assertThat(sentRequest(delegate, 4).headers().get(IF_NONE_MATCH))
                    .contains("W/\"other\"");
        });
    }

    @Test
    void execute_shouldNotKeepBody_whenLargerThanTotalLimit() throws Exception {
        final HttpTransport delegate = scripted(response(200, "12345", ETAG, ETAG_VALUE), response(200, BODY));
        final ConditionalCachingHttpTransport transport =
                new ConditionalCachingHttpTransport(delegate, 16, MAX_BODY_BYTES, 4, Set.of(ACCEPT));
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotKeepResponse_whenRequestHasNoStore() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "no-store")));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldBypassKeptCopy_whenRequestHasNoStoreAmongOtherDirectives() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(200, BODY, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "max-age=0, No-Store")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldRevalidateKeptCopy_whenRequestHasNoCache() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE), response(304, null, ETAG, ETAG_VALUE));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        final HttpResponse response =
                transport.execute(get(URL, HttpHeaders.of(CACHE_CONTROL, "no-cache", "Pragma", "no-cache")));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void execute_shouldNotRevalidate_whenCacheControlHasNoStore() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "no-store"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotRevalidate_whenCacheControlHasNoStoreAmongOtherDirectives() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "Private, NO-STORE"), response(200, BODY));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldKeepEntry_whenCacheControlHasOnlyMaxAge() throws Exception {
        final HttpTransport delegate = scripted(
                response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "private, max-age=60"), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void execute_shouldKeepEntry_whenNoStoreIsPartOfAnotherToken() throws Exception {
        final HttpTransport delegate =
                scripted(response(200, BODY, ETAG, ETAG_VALUE, CACHE_CONTROL, "x-no-store"), response(304, null));
        final ConditionalCachingHttpTransport transport = cached(delegate);
        transport.execute(get(URL, HttpHeaders.empty()));

        transport.execute(get(URL, HttpHeaders.empty()));

        assertThat(sentRequest(delegate, 1).headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    @Test
    void constructor_shouldThrow_whenMaxTotalBytesNegative() {
        final HttpTransport delegate = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(
                () -> new ConditionalCachingHttpTransport(delegate, MAX_ENTRIES, MAX_BODY_BYTES, -1L, Set.of(ACCEPT)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTotalBytes");
    }

    private static HttpRequest get(final String url, final HttpHeaders headers) {
        return request(HttpMethod.GET, url, headers);
    }

    private static HttpRequest get(final String url, final HttpHeaders headers, final String credentialId) {
        return new HttpRequest(HttpMethod.GET, ParsedUrl.of(URI.create(url)), headers, HttpBody.absent(), credentialId);
    }

    private static HttpRequest request(final HttpMethod method, final String url, final HttpHeaders headers) {
        return new HttpRequest(method, ParsedUrl.of(URI.create(url)), headers, HttpBody.absent());
    }

    private static HttpResponse response(final int status, final String body, final String... namesAndValues) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
        }
        final HttpBody content = body == null ? HttpBody.absent() : HttpBody.of(body.getBytes(StandardCharsets.UTF_8));
        return HttpResponses.of(status, headers, content);
    }

    private static String text(final HttpResponse response) {
        return new String(response.requireBodyBytes(), StandardCharsets.UTF_8);
    }

    private static HttpTransport scripted(final HttpResponse first, final HttpResponse... rest) {
        final Deque<HttpResponse> responses = new ArrayDeque<>();
        responses.add(first);
        responses.addAll(List.of(rest));
        return HttpTransportMocks.mock(
                invocation -> "execute".equals(invocation.getMethod().getName()) ? responses.poll() : null);
    }

    private static List<HttpRequest> executions(final HttpTransport delegate) {
        return mockingDetails(delegate).getInvocations().stream()
                .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.<HttpRequest>getArgument(0))
                .toList();
    }

    private static HttpRequest sentRequest(final HttpTransport delegate, final int index) {
        return executions(delegate).get(index);
    }

    private static int requestCount(final HttpTransport delegate) {
        return executions(delegate).size();
    }
}
