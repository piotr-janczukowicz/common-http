package io.github.piotr_janczukowicz.test.http;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class HttpResponses {

    private HttpResponses() {}

    public static HttpResponse of(
            final int statusCode, final Map<String, List<String>> headers, final @Nullable HttpBody body) {
        return new HttpResponse(statusCode, HttpHeaders.fromWire(headers), body);
    }

    public static HttpResponse ok(final String body) {
        return response(200, body);
    }

    /**
     * Builds a response from name/value header pairs. A repeated name appends to the values of that header, and a
     * {@code null} body means no body.
     *
     * @throws IllegalArgumentException if {@code namesAndValues} has an odd length
     */
    public static HttpResponse response(
            final int statusCode, final @Nullable String body, final String... namesAndValues) {
        requireNonNull(namesAndValues, "namesAndValues");
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "namesAndValues must hold name/value pairs but has " + namesAndValues.length + " elements");
        }
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.computeIfAbsent(namesAndValues[i], name -> new ArrayList<>())
                    .add(namesAndValues[i + 1]);
        }
        final HttpBody content = body == null ? HttpBody.absent() : HttpBody.of(body.getBytes(UTF_8));
        return of(statusCode, headers, content);
    }
}
