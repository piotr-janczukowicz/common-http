package io.github.piotr_janczukowicz.http.transport;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CACHE_CONTROL;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LAST_MODIFIED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RANGE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.VARY;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HttpTransport} decorator that answers a repeated GET from memory when the server confirms that the copy is
 * current: a 304 to a properly authorized request need not count against the server's rate limit, and the body is not
 * transferred again.
 *
 * <p>When a GET returns HTTP 200 with an {@code ETag} or a {@code Last-Modified} header, a body of at most
 * {@code maxBodyBytes}, and no {@code no-store} directive in {@code Cache-Control}, the response is kept. The bodies
 * kept never add up to more than {@code maxTotalBytes}, and at most {@code maxEntries} responses are kept; the least
 * recently used entries go first. The next GET for the same key is sent with {@code If-None-Match} (when an entity tag
 * was kept) and {@code If-Modified-Since} (when a modification time was kept). On HTTP 304 the caller gets HTTP 200
 * with the kept body, and the headers of the 304 overlaid on the kept ones, so that {@code Date}, the rate-limit
 * headers and any refreshed validator reach the layers above. Any other response is returned unchanged; when it
 * answered a conditional request and cannot be kept itself, the kept copy it supersedes is dropped.
 *
 * <p>The key of an entry is the method, the full URL with its query, the credential as
 * {@link HttpRequest#credentialKey()} names it (never the credential itself), the values of the varying headers, such
 * as {@code Accept}, that change the representation, and always {@code Accept-Encoding}, because a body that was asked
 * for as gzip is not the body of a plain request. Entries of one credential are never sent to another.
 *
 * <p>The {@code Vary} header of a kept response names more request headers that change its representation. The entry
 * keeps the values that its request had for them, and a later request uses the entry only when it has the same values;
 * otherwise it is sent in full, and its answer replaces the entry. A response with {@code Vary: *} is never kept.
 *
 * <p>Only GET requests are cached, and only a caller that sets no conditional header of its own gets the cache: a
 * request with {@code If-None-Match}, {@code If-Modified-Since} or {@code Range} passes through untouched (a 304 would
 * otherwise answer a range request with the whole kept copy), and so does a 304 with no kept copy.
 * {@link #stream(HttpRequest)} passes through as well, because a streamed body is never kept.
 *
 * <p>A request that sets {@code Cache-Control: no-store} opts out: it neither uses a kept copy nor leaves one behind.
 * {@code Cache-Control: no-cache} and {@code Pragma: no-cache} need no handling, because a kept copy is only ever used
 * after the server has confirmed it with a 304.
 *
 * <p>{@code Cache-Control: private} is not treated specially: the key contains the credential, so a kept copy is only
 * ever used for the credential that fetched it, and it is revalidated before every use. Do not share one transport
 * between principals that must not see each other's data if the credential is not part of the request.
 *
 * <p>Thread-safe. The lock guards the map only, never a request, so a slow exchange does not hold up other calls. This
 * class itself does nothing until a stack includes it.
 */
public final class ConditionalCachingHttpTransport implements HttpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionalCachingHttpTransport.class);

    private static final String NO_STORE = "no-store";
    private static final String VARY_ANY = "*";

    private final HttpTransport delegate;
    private final int maxEntries;
    private final int maxBodyBytes;
    private final long maxTotalBytes;
    private final List<String> varyingHeaders;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    // Sum of the body lengths of the entries; read and written under the lock only.
    private long keptBytes;

    /**
     * Caches GETs of {@code delegate}.
     *
     * @param delegate transport that sends the requests; closed when this one is closed
     * @param maxEntries most responses kept, at least one; the least recently used one is dropped beyond it
     * @param maxBodyBytes largest body kept, zero or more; a larger response is passed on but not kept
     * @param maxTotalBytes most body bytes kept in all, zero or more; the least recently used entries are dropped until
     *     the bodies kept fit, and a body larger than the smaller of {@code maxBodyBytes} and {@code maxTotalBytes} is
     *     not kept
     * @param varyingHeaders header names whose values are part of the key, such as {@code Accept}; names are compared
     *     without regard to case, and a blank name is refused
     * @throws NullPointerException if an argument is {@code null}, or {@code varyingHeaders} contains {@code null}
     * @throws IllegalArgumentException if {@code maxEntries} is below one, {@code maxBodyBytes} or
     *     {@code maxTotalBytes} is negative, or a varying header name is blank
     */
    public ConditionalCachingHttpTransport(
            final HttpTransport delegate,
            final int maxEntries,
            final int maxBodyBytes,
            final long maxTotalBytes,
            final Set<String> varyingHeaders) {
        this.delegate = requireNonNull(delegate, "delegate");
        requireAtLeast(maxEntries, 1, "maxEntries");
        requireAtLeast(maxBodyBytes, 0, "maxBodyBytes");
        requireAtLeast(maxTotalBytes, 0L, "maxTotalBytes");
        this.maxEntries = maxEntries;
        this.maxBodyBytes = maxBodyBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.varyingHeaders = copyNames(withAcceptEncoding(requireNonNull(varyingHeaders, "varyingHeaders")));
    }

    /**
     * Sends a GET that has a kept copy as a conditional request, and keeps or refreshes the copy that the answer
     * supports; any other request is sent as it is. See the class description.
     *
     * @param request request to send
     * @return the response, from the server or, on a 304, from memory
     * @throws IOException if the delegate fails
     */
    @Override
    public HttpResponse execute(final HttpRequest request) throws IOException {
        if (!isCacheable(request)) {
            return delegate.execute(request);
        }
        final Key key = keyOf(request);
        final Optional<Entry> kept = lookup(key).filter(entry -> entry.matches(request));
        if (kept.isEmpty()) {
            return remember(key, request, delegate.execute(request));
        }
        final HttpResponse response =
                delegate.execute(withValidators(request, kept.get().response()));
        if (!response.hasStatus(HttpStatus.NOT_MODIFIED)) {
            return replace(key, request, response);
        }
        LOG.atDebug()
                .setMessage("{}: 304 Not Modified, serving the kept copy")
                .addArgument(request.url()::describeSafe)
                .log();
        return replace(key, request, revalidated(kept.get().response(), response));
    }

    /**
     * Opens a stream through the delegate without keeping or revalidating anything.
     *
     * @param request request to send
     * @return open response, to be closed by the caller
     * @throws IOException if the delegate fails before the headers arrive
     */
    @Override
    public HttpStreamResponse stream(final HttpRequest request) throws IOException {
        return delegate.stream(request);
    }

    /** Closes the delegate. The kept responses are dropped with this transport. */
    @Override
    public void close() {
        delegate.close();
    }

    // Accept-Encoding always keys the cache: a request that asks for gzip and one that does not get different bodies.
    private static Set<String> withAcceptEncoding(final Set<String> names) {
        final Set<String> keyed = new LinkedHashSet<>(names);
        keyed.add(ACCEPT_ENCODING);
        return keyed;
    }

    private static List<String> copyNames(final Set<String> names) {
        return List.copyOf(names.stream()
                .map(name -> requireNotBlank(name, "varying header names"))
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))));
    }

    private static boolean isCacheable(final HttpRequest request) {
        return request.method() == HttpMethod.GET
                && request.headers().get(IF_NONE_MATCH).isEmpty()
                && request.headers().get(IF_MODIFIED_SINCE).isEmpty()
                && request.headers().get(RANGE).isEmpty()
                && !hasNoStore(request.headers().get(CACHE_CONTROL));
    }

    private Key keyOf(final HttpRequest request) {
        final List<Optional<String>> values =
                varyingHeaders.stream().map(request.headers()::get).toList();
        return new Key(request.method(), request.url(), request.credentialKey(), values);
    }

    private Optional<Entry> lookup(final Key key) {
        lock.lock();
        try {
            return Optional.ofNullable(entries.get(key));
        } finally {
            lock.unlock();
        }
    }

    // Returns the response it is given. A response that cannot be kept drops the entry that it answered a conditional
    // request for only when it says that the copy is gone or changed (a 200 that cannot be kept, 404, 410), so that the
    // next request does not send validators of a copy that the server no longer stands behind. A rate limit, a server
    // error or a refusal says nothing about the copy, so the entry stays.
    private HttpResponse replace(final Key key, final HttpRequest request, final HttpResponse response) {
        if (storedLength(response).isPresent()) {
            return remember(key, request, response);
        }
        if (response.hasStatus(HttpStatus.OK)
                || response.hasStatus(HttpStatus.NOT_FOUND)
                || response.hasStatus(HttpStatus.GONE)) {
            forget(key);
        }
        return response;
    }

    private void forget(final Key key) {
        lock.lock();
        try {
            final Entry dropped = entries.remove(key);
            if (dropped != null) {
                keptBytes -= dropped.bytes();
                LOG.atDebug()
                        .setMessage("Dropped the kept copy of {}: the server no longer stands behind it")
                        .addArgument(key.url()::describeSafe)
                        .log();
            }
        } finally {
            lock.unlock();
        }
    }

    // Returns the response it is given, so that the caller hands the same object on.
    private HttpResponse remember(final Key key, final HttpRequest request, final HttpResponse response) {
        final OptionalLong length = storedLength(response);
        if (length.isEmpty()) {
            return response;
        }
        final Entry entry = entryOf(request, response, length.getAsLong());
        lock.lock();
        try {
            final Entry replaced = entries.put(key, entry);
            if (replaced != null) {
                keptBytes -= replaced.bytes();
            }
            keptBytes += entry.bytes();
            evictOldest();
        } finally {
            lock.unlock();
        }
        return response;
    }

    // Called with the lock held. The entry just stored is the newest, and it fits the byte budget on its own, so it is
    // never the one dropped.
    private void evictOldest() {
        final Iterator<Entry> oldestFirst = entries.values().iterator();
        while (entries.size() > maxEntries || keptBytes > maxTotalBytes) {
            keptBytes -= oldestFirst.next().bytes();
            oldestFirst.remove();
            LOG.atDebug()
                    .setMessage("Evicted the oldest kept copy: {} entries, {} bytes kept")
                    .addArgument(entries::size)
                    .addArgument(keptBytes)
                    .log();
        }
    }

    // The entry for a response and the request that it answers: the values of the names in its Vary header are kept
    // with it, so that a later request can be matched against them.
    private static Entry entryOf(final HttpRequest request, final HttpResponse response, final long bytes) {
        final List<String> names = varyNames(response);
        final List<Optional<String>> values =
                names.stream().map(request.headers()::get).toList();
        return new Entry(response, bytes, names, values);
    }

    // The names listed in the Vary header of a response, in the order they are listed.
    private static List<String> varyNames(final HttpResponse response) {
        return response.header(VARY)
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::strip)
                        .filter(name -> !name.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    // The body length of a response that may be kept, or empty when it may not. A response with Vary: * depends on
    // something that is not in the request, so it is never kept.
    private OptionalLong storedLength(final HttpResponse response) {
        final boolean hasValidator =
                response.etag().isPresent() || response.header(LAST_MODIFIED).isPresent();
        final OptionalLong length = response.body().length();
        if (!response.hasStatus(HttpStatus.OK)
                || !hasValidator
                || hasNoStore(response)
                || varyNames(response).contains(VARY_ANY)
                || length.isEmpty()) {
            return OptionalLong.empty();
        }
        final long bodyLength = length.getAsLong();
        return bodyLength <= maxBodyBytes && bodyLength <= maxTotalBytes ? length : OptionalLong.empty();
    }

    // True when Cache-Control has the no-store directive, compared as a token and without regard to case.
    private static boolean hasNoStore(final HttpResponse response) {
        return hasNoStore(response.header(CACHE_CONTROL));
    }

    private static boolean hasNoStore(final Optional<String> cacheControl) {
        return cacheControl
                .map(value -> Arrays.stream(value.split(","))
                        .map(directive -> directive.split("=", 2)[0].strip().toLowerCase(Locale.ROOT))
                        .anyMatch(NO_STORE::equals))
                .orElse(false);
    }

    private static HttpRequest withValidators(final HttpRequest request, final HttpResponse kept) {
        final HttpHeaders withEtag = kept.etag()
                .map(etag -> request.headers().with(IF_NONE_MATCH, etag))
                .orElse(request.headers());
        final HttpHeaders headers = kept.header(LAST_MODIFIED)
                .map(since -> withEtag.with(IF_MODIFIED_SINCE, since))
                .orElse(withEtag);
        return new HttpRequest(request.method(), request.url(), headers, request.body(), request.credentialId());
    }

    // The 304 overlays the kept headers, except the framing and representation headers (Content-Length, Content-Type,
    // Content-Encoding, Transfer-Encoding), which describe the body the kept copy still holds.
    private static HttpResponse revalidated(final HttpResponse kept, final HttpResponse notModified) {
        final HttpHeaders overlay =
                notModified.headers().without(CONTENT_LENGTH, CONTENT_TYPE, CONTENT_ENCODING, TRANSFER_ENCODING);
        return new HttpResponse(HttpStatus.OK.code(), kept.headers().merge(overlay), kept.body());
    }

    /**
     * Key of an entry: the request target and the credential, and the values of the varying headers in the order of
     * {@link #varyingHeaders}.
     *
     * @param method method of the request
     * @param url full target, with its query
     * @param credential key of the credential, from {@link HttpRequest#credentialKey()}
     * @param varying value of each varying header, or empty when the request has none
     */
    private record Key(HttpMethod method, ParsedUrl url, String credential, List<Optional<String>> varying) {}

    /**
     * A kept response, the length of its body (what it adds to the bytes kept), and the values that the request had for
     * the names in the response's Vary header. A later request matches only when it has the same values.
     *
     * @param response the kept response
     * @param bytes length of its body
     * @param varyNames names listed in the Vary header of the response
     * @param varyValues value of each of those names in the request that the response answers
     */
    private record Entry(HttpResponse response, long bytes, List<String> varyNames, List<Optional<String>> varyValues) {

        boolean matches(final HttpRequest request) {
            return IntStream.range(0, varyNames.size())
                    .allMatch(i -> request.headers().get(varyNames.get(i)).equals(varyValues.get(i)));
        }
    }
}
