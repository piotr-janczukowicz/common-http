package pl.piotrjanczukowicz.test;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpResponse;

public final class TestResponses {

    private static final int OK = 200;

    private TestResponses() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static HttpResponse of(final int statusCode, final @Nullable String body) {
        final HttpBody httpBody = body == null ? HttpBody.absent() : HttpBody.of(body.getBytes(StandardCharsets.UTF_8));
        return new HttpResponse(statusCode, HttpHeaders.empty(), httpBody);
    }

    public static HttpResponse ok(final String body) {
        return of(OK, body);
    }
}
