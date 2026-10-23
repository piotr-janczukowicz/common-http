package pl.piotrjanczukowicz.http;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class HttpResponses {

    private HttpResponses() {}

    public static HttpResponse of(
            final int statusCode, final Map<String, List<String>> headers, final @Nullable HttpBody body) {
        return new HttpResponse(statusCode, HttpHeaders.fromWire(headers), body);
    }
}
