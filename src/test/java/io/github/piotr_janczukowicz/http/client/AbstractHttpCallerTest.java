package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.HttpStreamResponse;
import io.github.piotr_janczukowicz.http.MultipartFormData;
import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.json.JsonDecodeException;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.http.PlainJsonCodec;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@UnitTest
class AbstractHttpCallerTest {

    private static final JsonType<List<User>> USERS = JsonTypes.listOf(User.class);
    private static final String USER_JSON = "{\"login\":\"octo\"}";
    private static final String USERS_JSON = "[{\"login\":\"octo\"}]";
    private static final HttpAcceptedPolling NO_WAIT = new HttpAcceptedPolling(3, Duration.ZERO);

    private HttpTransport transport;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void fetch_shouldDecodeBody_whenOk() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final User user = caller(client).fetch(HttpCall.of(HttpMethod.GET, "/user"), User.class);

            assertThat(user.login()).isEqualTo("octo");
        }
    }

    @Test
    void fetch_shouldDecodeBody_whenTypeHasArguments() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USERS_JSON));

        try (final HttpClient client = client()) {
            final List<User> users = caller(client).fetch(HttpCall.get("/users"), USERS);

            assertThat(users).containsExactly(new User("octo"));
        }
    }

    @Test
    void fetch_shouldOmitQueryAndUserInfo_whenAbsoluteUriBodyAbsent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final HttpCall call =
                    HttpCall.of(HttpMethod.GET, URI.create("https://api.example.test/user/42?token=secret#frag"));

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetch(call, User.class));

            assertThat(thrown)
                    .isInstanceOf(HttpEmptyResponseException.class)
                    .hasMessage("HTTP 204 response to GET https://api.example.test/user/42 has no body to decode");
        }
    }

    @Test
    void fetch_shouldThrowDescriptiveFailure_whenBodyAbsent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.of(HttpMethod.DELETE, "/user/42");

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetch(call, User.class));

            assertThat(thrown)
                    .isInstanceOf(HttpEmptyResponseException.class)
                    .hasMessage("HTTP 204 response to DELETE /user/42 has no body to decode");
            assertThat(((HttpEmptyResponseException) thrown).uri())
                    .contains(URI.create("https://api.example.test/user/42"));
        }
    }

    @Test
    void fetch_shouldCarryResolvedUriWithoutQuery_whenPathCallHasQuery() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/user/42").withQuery(Map.of("token", "secret"));

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetch(call, User.class));

            assertThat(((HttpEmptyResponseException) thrown).uri())
                    .contains(URI.create("https://api.example.test/user/42"));
        }
    }

    @Test
    void fetch_shouldThrowIllegalState_whenCodecReadsJsonNull() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "null"));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.of(HttpMethod.GET, "/user");

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetch(call, User.class));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("null");
        }
    }

    @Test
    void fetchOrEmpty_shouldBeEmpty_whenNotFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), null));

        try (final HttpClient client = client()) {
            final Optional<User> user = caller(client).fetchOrEmpty(HttpCall.of(HttpMethod.GET, "/user"), User.class);

            assertThat(user).isEmpty();
        }
    }

    @Test
    void fetchOrEmpty_shouldDecodeGenericType_whenFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "[" + USER_JSON + "]"));

        try (final HttpClient client = client()) {
            final Optional<List<User>> users = caller(client).fetchOrEmpty(HttpCall.get("/users"), USERS);

            assertThat(users)
                    .hasValueSatisfying(
                            list -> assertThat(list).extracting(User::login).containsExactly("octo"));
        }
    }

    @Test
    void fetchOrEmpty_shouldBeEmptyForGenericType_whenNotFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), null));

        try (final HttpClient client = client()) {
            assertThat(caller(client).fetchOrEmpty(HttpCall.get("/users"), USERS))
                    .isEmpty();
        }
    }

    @Test
    void fetchByStatus_shouldThrow_whenByStatusHoldsRedirectStatus() throws IOException {
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);

            final HttpCall call = HttpCall.get("/x");

            final Throwable thrown = catchThrowable(
                    () -> caller.fetchByStatus(call, body -> "other", Map.of(HttpStatus.FOUND, body -> "found")));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redirect");
            verify(transport, never()).execute(any());
        }
    }

    @Test
    void fetchOrEmpty_shouldThrowDescriptiveFailure_whenBodyAbsentAndNotNotFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.of(HttpMethod.GET, URI.create("https://api.example.test/x"));

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetchOrEmpty(call, User.class));

            assertThat(thrown)
                    .isInstanceOf(HttpEmptyResponseException.class)
                    .hasMessageContaining("HTTP 202")
                    .hasMessageContaining("GET https://api.example.test/x");
        }
    }

    @Test
    void fetchOrEmpty_shouldThrowEmptyResponseWithoutQuery_whenBodyAbsent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.of(HttpMethod.GET, URI.create("https://api.example.test/u?t=secret"));

            final HttpClientCaller caller = caller(client);

            final Throwable thrown = catchThrowable(() -> caller.fetchOrEmpty(call, User.class));

            assertThat(thrown)
                    .isInstanceOf(HttpEmptyResponseException.class)
                    .hasMessage("HTTP 200 response to GET https://api.example.test/u has no body to decode");
        }
    }

    @Test
    void fetchOptional_shouldDecodeBody_whenTypeHasArguments() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USERS_JSON));

        try (final HttpClient client = client()) {
            final Optional<List<User>> users = caller(client).fetchOptional(HttpCall.get("/users"), USERS);

            assertThat(users).contains(List.of(new User("octo")));
        }
    }

    @Test
    void fetchOptional_shouldBeEmpty_whenTypeHasArgumentsAndNoContent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final Optional<List<User>> users = caller(client).fetchOptional(HttpCall.get("/users"), USERS);

            assertThat(users).isEmpty();
        }
    }

    @Test
    void fetchOptional_shouldBeEmpty_whenAcceptedWithoutBody() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), null));

        try (final HttpClient client = client()) {
            final Optional<User> user = caller(client).fetchOptional(HttpCall.get("/user"), User.class);

            assertThat(user).isEmpty();
        }
    }

    @Test
    void fetchOptional_shouldDecodeBody_whenAcceptedWithBody() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final Optional<User> user = caller(client).fetchOptional(HttpCall.get("/user"), User.class);

            assertThat(user).contains(new User("octo"));
        }
    }

    @Test
    void fetchResult_shouldKeepEtagAndValue_whenOk() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.OK.code(),
                        HttpHeaders.of(ETAG, "\"v1\""),
                        HttpBody.of(USERS_JSON.getBytes(StandardCharsets.UTF_8))));

        try (final HttpClient client = client()) {
            final HttpResult<List<User>> result = caller(client).fetchResult(HttpCall.get("/users"), USERS);

            assertSoftly(softly -> {
                softly.assertThat(result.value()).contains(List.of(new User("octo")));
                softly.assertThat(result.etag()).contains("\"v1\"");
                softly.assertThat(result.isNotModified()).isFalse();
            });
        }
    }

    @Test
    void fetchResult_shouldBeNotModifiedWithoutValue_whenTypeHasArgumentsAnd304() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_MODIFIED.code(), null));

        try (final HttpClient client = client()) {
            final HttpResult<List<User>> result = caller(client).fetchResult(HttpCall.get("/users"), USERS);

            assertSoftly(softly -> {
                softly.assertThat(result.isNotModified()).isTrue();
                softly.assertThat(result.value()).isEmpty();
            });
        }
    }

    @Test
    void fetchUnless_shouldBeEmpty_whenStatusListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), null));

        try (final HttpClient client = client()) {
            final Optional<User> user =
                    caller(client).fetchUnless(HttpCall.get("/user"), User.class, HttpStatus.NOT_FOUND);

            assertThat(user).isEmpty();
        }
    }

    @Test
    void fetchUnless_shouldDecodeBody_whenTypeHasArgumentsAndStatusNotListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USERS_JSON));

        try (final HttpClient client = client()) {
            final Optional<List<User>> users =
                    caller(client).fetchUnless(HttpCall.get("/users"), USERS, HttpStatus.NOT_FOUND);

            assertThat(users).contains(List.of(new User("octo")));
        }
    }

    @Test
    void fetchUnless_shouldBeEmpty_whenTypeHasArgumentsAndBodyIsEmptyObject() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "{}"));

        try (final HttpClient client = client()) {
            final Optional<List<User>> users =
                    caller(client).fetchUnless(HttpCall.get("/users"), USERS, HttpStatus.NOT_FOUND);

            assertThat(users).isEmpty();
        }
    }

    @Test
    void fetchWhenReady_shouldReturnReady_whenOk() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final HttpComputed<User> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/user"), User.class, NO_WAIT);

            assertThat(computed).isEqualTo(new HttpComputed.Ready<>(new User("octo")));
        }
    }

    @Test
    void fetchWhenReady_shouldAskAgain_whenAcceptedBeforeData() throws IOException {
        when(transport.execute(any()))
                .thenReturn(response(HttpStatus.ACCEPTED.code(), null), response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final HttpComputed<User> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/user"), User.class, NO_WAIT);

            assertThat(computed).isEqualTo(new HttpComputed.Ready<>(new User("octo")));
            verify(transport, times(2)).execute(any());
        }
    }

    @Test
    void fetchWhenReady_shouldReturnNotReady_whenStillAcceptedAfterLastTry() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), null));

        try (final HttpClient client = client()) {
            final HttpComputed<User> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/user"), User.class, NO_WAIT);

            assertThat(computed).isInstanceOf(HttpComputed.NotReady.class);
            verify(transport, times(NO_WAIT.maxAttempts())).execute(any());
        }
    }

    @Test
    void fetchWhenReady_shouldReturnNoData_whenNoContent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final HttpComputed<User> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/user"), User.class, NO_WAIT);

            assertThat(computed).isInstanceOf(HttpComputed.NoData.class);
        }
    }

    @Test
    void fetchWhenReady_shouldReturnNoData_whenBodyIsEmptyObject() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "{}"));

        try (final HttpClient client = client()) {
            final HttpComputed<User> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/user"), User.class, NO_WAIT);

            assertThat(computed).isInstanceOf(HttpComputed.NoData.class);
        }
    }

    @Test
    void fetchWhenReady_shouldReturnReady_whenTypeHasArgumentsAndAcceptedBeforeData() throws IOException {
        when(transport.execute(any()))
                .thenReturn(response(HttpStatus.ACCEPTED.code(), null), response(HttpStatus.OK.code(), USERS_JSON));

        try (final HttpClient client = client()) {
            final HttpComputed<List<User>> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/users"), USERS, NO_WAIT);

            assertThat(computed).isEqualTo(new HttpComputed.Ready<>(List.of(new User("octo"))));
        }
    }

    @Test
    void fetchWhenReady_shouldReturnNotReady_whenTypeHasArgumentsAndStillAccepted() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), null));

        try (final HttpClient client = client()) {
            final HttpComputed<List<User>> computed =
                    caller(client).fetchWhenReady(HttpCall.get("/users"), USERS, NO_WAIT);

            assertThat(computed).isInstanceOf(HttpComputed.NotReady.class);
        }
    }

    @Test
    void fetchByStatus_shouldUseStatusMapper_whenStatusListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final String result = caller(client)
                    .fetchByStatus(
                            HttpCall.post("/user"),
                            body -> "other",
                            Map.of(
                                    HttpStatus.ACCEPTED,
                                    body -> "accepted:" + body.as(User.class).login()));

            assertThat(result).isEqualTo("accepted:octo");
        }
    }

    @Test
    void fetchByStatus_shouldUseOtherwiseMapper_whenSuccessfulStatusNotListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final String result = caller(client)
                    .fetchByStatus(
                            HttpCall.post("/user"),
                            body -> "other:" + body.as(User.class).login(),
                            Map.of(HttpStatus.ACCEPTED, body -> "accepted"));

            assertThat(result).isEqualTo("other:octo");
        }
    }

    @Test
    void fetchByStatus_shouldDecodeWithJsonType_whenMapperAsksForType() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USERS_JSON));

        try (final HttpClient client = client()) {
            final int count = caller(client)
                    .fetchByStatus(
                            HttpCall.get("/users"),
                            body -> body.as(USERS).size(),
                            Map.of(HttpStatus.ACCEPTED, body -> -1));

            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void fetchByStatus_shouldThrowEmptyResponse_whenMapperDecodesBodyOfNoContent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        final Throwable thrown;
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.post("/user");

            thrown = catchThrowable(() -> caller.fetchByStatus(
                    call, body -> body.as(User.class), Map.of(HttpStatus.NO_CONTENT, body -> body.as(User.class))));
        }

        assertThat(thrown)
                .isInstanceOf(HttpEmptyResponseException.class)
                .hasMessageContaining("204")
                .hasMessageContaining("POST");
    }

    @Test
    void fetchByStatus_shouldThrowEmptyResponse_whenMapperDecodesJsonTypeOfNoContent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        final Throwable thrown;
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.get("/users");

            thrown = catchThrowable(() -> caller.fetchByStatus(
                    call, body -> body.as(USERS), Map.of(HttpStatus.NO_CONTENT, body -> body.as(USERS))));
        }

        assertThat(thrown).isInstanceOf(HttpEmptyResponseException.class).hasMessageContaining("204");
    }

    @Test
    void fetchWhenReady_shouldThrowInterruptedIo_whenThreadIsInterruptedWhileWaiting() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.ACCEPTED.code(), null));
        final HttpAcceptedPolling slow = new HttpAcceptedPolling(3, Duration.ofSeconds(30));

        Thread.currentThread().interrupt();
        final Throwable thrown;
        final boolean isFlagRestored;
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> caller.fetchWhenReady(call, User.class, slow));
            isFlagRestored = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertThat(thrown).isInstanceOf(InterruptedIOException.class);
        assertThat(isFlagRestored).isTrue();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void download_shouldWriteBodyToStream_whenResponseIsSuccessful() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final long written;
        try (final HttpClient client = client()) {
            written = caller(client).download(HttpCall.get("/zip"), out);
        }

        assertThat(written).isEqualTo(3);
        assertThat(out.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void download_shouldReplaceFile_whenResponseIsSuccessful() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});

        final Path result;
        try (final HttpClient client = client()) {
            result = caller(client).download(HttpCall.get("/zip"), target);
        }

        assertThat(result).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void download_shouldCreateNoFile_whenResponseIsNotFound() throws IOException {
        when(transport.stream(any()))
                .thenReturn(
                        HttpStreamResponse.buffered(response(HttpStatus.NOT_FOUND.code(), "{\"message\":\"nope\"}")));
        final Path target = tempDir.resolve("out.bin");

        final Throwable thrown;
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.get("/zip");

            thrown = catchThrowable(() -> caller.download(call, target));
        }

        assertThat(thrown).isInstanceOf(HttpException.class);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void isExisting_shouldReturnTrue_whenNoContent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            assertThat(caller(client).isExisting(HttpCall.get("/user"))).isTrue();
        }
    }

    @Test
    void isExisting_shouldReturnFalse_whenNotFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), null));

        try (final HttpClient client = client()) {
            assertThat(caller(client).isExisting(HttpCall.get("/user"))).isFalse();
        }
    }

    @Test
    void json_shouldSendEncodedBodyAndContentType_whenValueGiven() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            caller.send(caller.json(HttpCall.of(HttpMethod.POST, "/user"), new User("octo")));
        }

        final HttpRequest request = capturedRequest();
        assertThat(request.headers().get(CONTENT_TYPE)).contains("application/json");
        assertThat(request.body().mapBytes(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> ""))
                .contains("octo");
    }

    @Test
    void multipart_shouldSendTheFormAndItsContentType_whenFormGiven() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));
        final MultipartFormData form =
                MultipartFormData.builder().text("password", "p4ss").build("xyz");

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            caller.send(caller.multipart(HttpCall.of(HttpMethod.POST, "/init"), form));
        }

        final HttpRequest request = capturedRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("multipart/form-data; boundary=\"xyz\"");
            softly.assertThat(request.body().mapBytes(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> ""))
                    .contains("name=\"password\"");
        });
    }

    @Test
    void multipart_shouldThrow_whenFormNull() {
        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.of(HttpMethod.POST, "/init");

            final Throwable thrown = catchThrowable(() -> caller.multipart(call, null));

            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("form");
        }
    }

    @Test
    void jsonIfPresent_shouldOmitBodyAndContentType_whenValueNull() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            caller.send(caller.jsonIfPresent(HttpCall.of(HttpMethod.PATCH, "/user"), null));
        }

        final HttpRequest request = capturedRequest();
        assertThat(request.body().isAbsent()).isTrue();
        assertThat(request.headers().get(CONTENT_TYPE)).isEmpty();
    }

    private HttpRequest capturedRequest() throws IOException {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        return captor.getValue();
    }

    private HttpClient client() {
        return HttpClient.builder(transport, HttpClientConfiguration.of(URI.create("https://api.example.test/")))
                .build();
    }

    private static HttpClientCaller caller(final HttpClient client) {
        return new HttpClientCaller(client, PlainJsonCodec.create());
    }

    public record User(String login) {}

    @Test
    void fetchOrEmpty_shouldDecodeClassType_whenFound() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final Optional<User> user = caller(client).fetchOrEmpty(HttpCall.get("/user"), User.class);

            assertThat(user).contains(new User("octo"));
        }
    }

    @Test
    void fetchUnless_shouldDecodeClassType_whenStatusNotListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final Optional<User> user =
                    caller(client).fetchUnless(HttpCall.get("/user"), User.class, HttpStatus.NOT_FOUND);

            assertThat(user).contains(new User("octo"));
        }
    }

    @Test
    void jsonIfPresent_shouldEncodeBody_whenValuePresent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);

            caller.send(caller.jsonIfPresent(HttpCall.of(HttpMethod.PATCH, "/user"), new User("octo")));

            final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(transport).execute(captor.capture());
            assertThat(captor.getValue().headers().get(CONTENT_TYPE)).contains("application/json");
        }
    }

    @Test
    void redirectLocation_shouldReturnLocation_whenRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of("Location", List.of("https://api.example.test/next"))),
                        HttpBody.absent()));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);

            final URI location = caller.redirectLocation(HttpCall.get("/tarball"));
            final Optional<URI> optionalLocation = caller.redirectLocationIfAny(HttpCall.get("/tarball"));

            assertSoftly(softly -> {
                softly.assertThat(location).isEqualTo(URI.create("https://api.example.test/next"));
                softly.assertThat(optionalLocation).contains(URI.create("https://api.example.test/next"));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"login\":\"octo\"}", "{} x"})
    void fetchUnless_shouldDecode_whenBodyIsNotAnEmptyObject(final String body) throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), body));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);

            final HttpCall call = HttpCall.get("/users");

            final Throwable thrown = catchThrowable(() -> caller.fetchUnless(call, USERS, HttpStatus.NOT_FOUND));

            assertThat(thrown).isInstanceOf(JsonDecodeException.class);
        }
    }

    @Test
    void fetchUnless_shouldDecode_whenBodyIsEmptyArray() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "[]"));

        try (final HttpClient client = client()) {
            assertThat(caller(client).fetchUnless(HttpCall.get("/users"), USERS, HttpStatus.NOT_FOUND))
                    .contains(List.of());
        }
    }

    @Test
    void fetchUnless_shouldBeEmpty_whenBodyIsEmptyObjectWithWhitespace() throws IOException {
        final String body = " \t\r\n\u000b\f{ \n}\t ";
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), body));

        try (final HttpClient client = client()) {
            assertThat(caller(client).fetchUnless(HttpCall.get("/users"), USERS, HttpStatus.NOT_FOUND))
                    .isEmpty();
        }
    }

    @Test
    void fetchByStatus_shouldUseStatusMapper_whenClientErrorStatusListed() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), USER_JSON));

        try (final HttpClient client = client()) {
            final String result = caller(client)
                    .fetchByStatus(
                            HttpCall.get("/user"), body -> "other", Map.of(HttpStatus.NOT_FOUND, body -> "missing"));

            assertThat(result).isEqualTo("missing");
        }
    }

    @Test
    void fetchUnless_shouldBeEmpty_whenBodyAbsent() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), null));

        try (final HttpClient client = client()) {
            final Optional<User> user =
                    caller(client).fetchUnless(HttpCall.get("/user"), User.class, HttpStatus.NOT_FOUND);

            assertThat(user).isEmpty();
        }
    }

    @Test
    void fetchUnless_shouldDecode_whenBodyIsBlank() throws IOException {
        when(transport.execute(any())).thenReturn(response(HttpStatus.OK.code(), "   "));

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);

            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> caller.fetchUnless(call, User.class, HttpStatus.NOT_FOUND));

            assertThat(thrown).isInstanceOf(JsonDecodeException.class);
        }
    }

    @Test
    void download_shouldWriteFile_whenBodyFitsTheLimit() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
        final Path target = tempDir.resolve("limited.bin");

        final Path result;
        try (final HttpClient client = client()) {
            result = caller(client).download(HttpCall.get("/zip"), target, 3L);
        }

        assertThat(result).hasBinaryContent(new byte[] {1, 2, 3});
    }

    @Test
    void download_shouldThrowAndCreateNoFile_whenBodyExceedsTheLimit() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
        final Path target = tempDir.resolve("limited.bin");

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> caller.download(call, target, 3L));

            assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
        }
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void download_shouldThrow_whenLimitNegative() {
        final Path target = tempDir.resolve("negative.bin");

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = caller(client);
            final HttpCall call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> caller.download(call, target, -1L));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBytes");
        }
    }
}
