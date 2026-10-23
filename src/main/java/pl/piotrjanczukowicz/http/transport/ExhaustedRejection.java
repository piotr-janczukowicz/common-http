package pl.piotrjanczukowicz.http.transport;

import static pl.piotrjanczukowicz.http.HttpHeaderNames.DATE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ETAG;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LAST_MODIFIED;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.TRANSFER_ENCODING;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;

/** Builds the rejection that a rate-limit gate replays for an exhausted quota. */
final class ExhaustedRejection {

    // Lower-cased, because isReplayable compares the lower-cased name. Every Content-* header describes the body.
    private static final String CONTENT_HEADER_PREFIX = "content-";
    // Lower-cased, because isReplayable compares the lower-cased name.
    private static final Set<String> NOT_REPLAYED = Stream.of(DATE, ETAG, LAST_MODIFIED, TRANSFER_ENCODING)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private ExhaustedRejection() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The rejection that a gate replays for an exhausted quota: HTTP 429 with the headers of the response that reported
     * it, except the ones that describe its body or its validity ({@code Content-*}, {@code ETag},
     * {@code Last-Modified}, {@code Transfer-Encoding}, {@code Date}), and no body.
     */
    static HttpResponse of(final HttpResponse response) {
        final Map<String, List<String>> wire = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().asMap().forEach((name, value) -> {
            if (isReplayable(name)) {
                wire.put(name, List.of(value));
            }
        });
        return new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.fromWire(wire), HttpBody.absent());
    }

    private static boolean isReplayable(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(CONTENT_HEADER_PREFIX) && !NOT_REPLAYED.contains(lower);
    }
}
