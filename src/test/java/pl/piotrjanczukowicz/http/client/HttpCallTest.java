package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.testsupport.UnitTest;

@UnitTest
class HttpCallTest {

    @Test
    void withQuery_shouldKeepSingleValue_whenValueContainsComma() {
        final HttpCall call = HttpCall.get("/issues");

        final HttpCall withQuery = call.withQuery(Map.of("labels[]", "a,b"));

        assertThat(withQuery.query().get("labels[]")).containsExactly("a,b");
    }

    @Test
    void withQueryValues_shouldKeepRepeatedValues_whenAddedExplicitly() {
        final HttpCall call = HttpCall.get("/issues");

        final HttpCall withQuery = call.withQueryValues(Map.of("labels[]", List.of("bug", "help")));

        assertThat(withQuery.query().get("labels[]")).containsExactly("bug", "help");
    }

    @Test
    void withQuery_shouldNameParameter_whenValueNull() {
        final Map<String, String> query = new HashMap<>();
        query.put("state", null);
        final HttpCall call = HttpCall.get("/issues");

        final Throwable thrown = catchThrowable(() -> call.withQuery(query));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("state");
    }

    @Test
    void get_shouldReject_whenUriIsRelative() {
        final URI relative = URI.create("/users");

        final Throwable thrown = catchThrowable(() -> HttpCall.get(relative));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void of_shouldSetMethodAndTarget_whenPathOrUriGiven() {
        final URI user = URI.create("https://api.example.test/user");
        final URI assets = URI.create("https://uploads.example.test/assets");

        final HttpCall byPath = HttpCall.of(HttpMethod.POST, "/repos");
        final HttpCall byUri = HttpCall.get(user);
        final HttpCall postByUri = HttpCall.of(HttpMethod.POST, assets);

        assertSoftly(softly -> {
            softly.assertThat(byPath.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(byUri.target()).isEqualTo(new HttpCall.AbsoluteUri(user));
            softly.assertThat(postByUri.target()).isEqualTo(new HttpCall.AbsoluteUri(assets));
        });
    }

    @Test
    void with_shouldSetQueryHeadersAndBody_whenChained() {
        final HttpCall call = HttpCall.of(HttpMethod.POST, URI.create("https://uploads.example.test/assets"));

        final HttpCall chained = call.withQueryValues(Map.of("name", List.of("a.bin")))
                .withHeaders(HttpHeaders.of(CONTENT_TYPE, "application/octet-stream"))
                .withBody(HttpBody.of(new byte[] {1}));

        assertSoftly(softly -> {
            softly.assertThat(chained.target()).isInstanceOf(HttpCall.AbsoluteUri.class);
            softly.assertThat(chained.headers().get(CONTENT_TYPE)).contains("application/octet-stream");
            softly.assertThat(chained.body().isAbsent()).isFalse();
            softly.assertThat(chained.target())
                    .isEqualTo(new HttpCall.AbsoluteUri(URI.create("https://uploads.example.test/assets")));
        });
    }

    @Test
    void head_shouldBuildHeadCall_whenPathGiven() {
        final HttpCall call = HttpCall.head("/user");

        assertThat(call.method()).isEqualTo(HttpMethod.HEAD);
    }

    @Test
    void bodyFactories_shouldSetMethodAndBody_whenPutPatchDelete() {
        final HttpBody body = HttpBody.of("{}".getBytes(StandardCharsets.UTF_8));

        final HttpCall put = HttpCall.put("/x", body);
        final HttpCall patch = HttpCall.patch("/x", body);
        final HttpCall delete = HttpCall.delete("/x", body);

        assertSoftly(softly -> {
            softly.assertThat(put.method()).isEqualTo(HttpMethod.PUT);
            softly.assertThat(put.body()).isEqualTo(body);
            softly.assertThat(patch.method()).isEqualTo(HttpMethod.PATCH);
            softly.assertThat(patch.body()).isEqualTo(body);
            softly.assertThat(delete.method()).isEqualTo(HttpMethod.DELETE);
            softly.assertThat(delete.body()).isEqualTo(body);
        });
    }

    @Test
    void toString_shouldHideQueryValuesAndUserInfo_whenRequestIsPrinted() {
        final HttpCall call = HttpCall.get(URI.create("https://user:pw@api.example.test/x?access_token=s3cret"))
                .withQueryValues(Map.of("access_token", List.of("s3cret")));

        final String text = call.toString();

        assertThat(text)
                .contains("GET", "https://api.example.test/x", "access_token", "body=none")
                .doesNotContain("s3cret", "user", "pw@");
    }

    @Test
    void toString_shouldDropQueryFromPathTarget_whenPathHasQuery() {
        final HttpCall call = HttpCall.get("/x?token=s3cret");

        final String text = call.toString();

        assertThat(text).contains("/x").doesNotContain("s3cret");
    }

    @Test
    void toString_shouldShowBodySizeAndHideCredentials_whenBodyAndHeadersPresent() {
        final HttpCall call = HttpCall.of(HttpMethod.POST, "/x")
                .withHeaders(HttpHeaders.of(AUTHORIZATION, "Bearer s3cret"))
                .withBody(HttpBody.of(new byte[] {1, 2, 3}));

        final String text = call.toString();

        assertThat(text).contains("3 bytes").doesNotContain("s3cret");
    }

    @Test
    void toString_shouldNameFileBodyWithoutMeasuringIt_whenBodyIsFile() {
        final HttpCall call =
                HttpCall.of(HttpMethod.PUT, "/x").withBody(HttpBody.ofFile(Path.of("no-such-file-for-length.bin")));

        final String text = call.toString();

        assertThat(text).contains("body=file");
    }

    @Test
    void post_shouldCarryBody_whenPathAndBodyGiven() {
        final HttpBody body = HttpBody.of(new byte[] {1});

        final HttpCall call = HttpCall.post("/issues", body);

        assertThat(call.body()).isSameAs(body);
    }

    @Test
    void requirePath_shouldReturnPath_whenTargetIsPath() {
        assertThat(HttpCall.get("/issues").requirePath()).isEqualTo("/issues");
    }

    @Test
    void requirePath_shouldThrow_whenTargetIsAbsoluteUri() {
        final HttpCall call = HttpCall.of(HttpMethod.GET, java.net.URI.create("https://api.example.test/issues"));

        final Throwable thrown = catchThrowable(call::requirePath);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no API path");
    }
}
