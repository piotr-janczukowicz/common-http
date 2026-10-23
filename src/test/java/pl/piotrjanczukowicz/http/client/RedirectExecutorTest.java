package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.ACCEPT;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_ENCODING;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LOCATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.USER_AGENT;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransportMocks;
import pl.piotrjanczukowicz.http.uri.ParsedUrl;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class RedirectExecutorTest {
    private static final String VERSION_HEADER = "X-Api-Version";

    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldReturnFirstResponse_whenFollowIsFalse() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/next"));
        final HttpRequest request = get("https://api.example.test/start");

        final RedirectExecutor.Exchange exchange =
                follower().execute(request, RedirectPolicy.defaults().maxHops(), false);

        assertThat(exchange.response().statusCode()).isEqualTo(HttpStatus.FOUND.code());
        verify(transport, times(1)).execute(any());
    }

    @ParameterizedTest
    @CsvSource({
        "POST,MOVED_PERMANENTLY",
        "POST,FOUND",
        "POST,SEE_OTHER",
        "PUT,SEE_OTHER",
        "PATCH,SEE_OTHER",
        "DELETE,SEE_OTHER"
    })
    void execute_shouldRefuse_whenWriteMethodIsRedirectedAsGet(final HttpMethod method, final HttpStatus status)
            throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(status, "https://api.example.test/next?token=signed"))
                .thenReturn(ok());
        final HttpRequest request = new HttpRequest(
                method, URI.create("https://api.example.test/start?code=secret"), headers(), HttpBody.of(new byte[] {1
                }));

        final Throwable thrown = catchThrowable(
                () -> follower().execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown).isInstanceOf(HttpException.class);
        final HttpException error = (HttpException) thrown;
        assertSoftly(softly -> {
            softly.assertThat(error.statusCode()).isEqualTo(status.code());
            softly.assertThat(error.method()).contains(method);
            softly.assertThat(error.getMessage()).contains(method.name()).doesNotContain("token");
            softly.assertThat(error.rawBody()).contains("https://api.example.test/next");
            softly.assertThat(error.uri()).contains(URI.create("https://api.example.test/start"));
            softly.assertThat(error.headers().get(LOCATION)).isEmpty();
        });
        verify(transport, times(1)).execute(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpStatus.class,
            names = {"MOVED_PERMANENTLY", "FOUND", "SEE_OTHER"})
    void execute_shouldReturnRedirectResponse_whenWriteMethodAndFollowIsFalse(final HttpStatus status)
            throws IOException {
        when(transport.execute(any())).thenReturn(redirect(status, "https://api.example.test/next"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST, URI.create("https://api.example.test/start"), headers(), HttpBody.of(new byte[] {1}));

        final RedirectExecutor.Exchange exchange =
                follower().execute(request, RedirectPolicy.defaults().maxHops(), false);

        assertThat(exchange.response().statusCode()).isEqualTo(status.code());
        verify(transport, times(1)).execute(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpStatus.class,
            names = {"MOVED_PERMANENTLY", "FOUND", "SEE_OTHER"})
    void execute_shouldFollowAsGet_whenGetOrHeadIsRedirected(final HttpStatus status) throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(status, "https://api.example.test/next"))
                .thenReturn(ok());

        follower()
                .execute(
                        get("https://api.example.test/start"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        final HttpRequest second = secondExecutedRequest();
        assertThat(second.method()).isEqualTo(HttpMethod.GET);
    }

    @ParameterizedTest
    @CsvSource({
        "PUT,MOVED_PERMANENTLY",
        "PUT,FOUND",
        "PATCH,MOVED_PERMANENTLY",
        "PATCH,FOUND",
        "DELETE,MOVED_PERMANENTLY",
        "DELETE,FOUND"
    })
    void execute_shouldKeepMethodAndBody_whenIdempotentWriteIsMovedOrFound(
            final HttpMethod method, final HttpStatus status) throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(status, "https://api.example.test/renamed"))
                .thenReturn(ok());
        final HttpRequest request = new HttpRequest(
                method, URI.create("https://api.example.test/start"), headers(), HttpBody.of(new byte[] {9}));

        follower().execute(request, RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.method()).isEqualTo(method);
            softly.assertThat(second.uri()).isEqualTo(URI.create("https://api.example.test/renamed"));
            softly.assertThat(second.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(9);
        });
    }

    @ParameterizedTest
    @CsvSource({"PUT,FOUND", "PATCH,MOVED_PERMANENTLY", "DELETE,FOUND"})
    void execute_shouldKeepContentTypeAndReopenStreamBody_whenIdempotentWriteIsMovedOrFound(
            final HttpMethod method, final HttpStatus status) throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(status, "https://api.example.test/renamed"))
                .thenReturn(ok());
        final HttpBody body = HttpBody.ofStream(() -> new ByteArrayInputStream(new byte[] {9}), 1);
        final HttpRequest request = new HttpRequest(
                method,
                URI.create("https://api.example.test/start"),
                HttpHeaders.empty().merge(headers()).with(CONTENT_TYPE, "application/json"),
                body);

        follower().execute(request, RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(second.body()).isSameAs(body);
        });
    }

    @Test
    void execute_shouldKeepEntityHeadersButNotCredentials_whenBodyFollowsAcrossOrigins() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://other.example.test/next"))
                .thenReturn(ok());
        final RedirectPolicy policy =
                new RedirectPolicy(5, Set.of(HttpMethod.GET, HttpMethod.PUT), Set.of(ACCEPT, USER_AGENT));
        final RedirectExecutor executor = new RedirectExecutor(
                transport,
                policy,
                (method, response, uri) -> HttpException.from(method, response, uri),
                ParsedUrl.of(URI.create("https://api.example.test/")));
        final HttpRequest request = new HttpRequest(
                HttpMethod.PUT,
                URI.create("https://api.example.test/start"),
                HttpHeaders.empty().merge(headers()).with(CONTENT_TYPE, "application/json"),
                HttpBody.of(new byte[] {9}));

        executor.execute(request, policy.maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
        });
    }

    @Test
    void execute_shouldKeepMethodAndBody_whenTemporaryRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://api.example.test/next"))
                .thenReturn(ok());
        final HttpBody body = HttpBody.of(new byte[] {9});
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, URI.create("https://api.example.test/start"), headers(), body);

        follower().execute(request, RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(second.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(9);
        });
    }

    @Test
    void execute_shouldStripAuth_whenCrossOriginGet() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://example.test/user"))
                .thenReturn(ok());

        follower()
                .execute(
                        get("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
            softly.assertThat(second.headers().asMap().keySet())
                    .containsExactlyInAnyOrder(ACCEPT, USER_AGENT, VERSION_HEADER);
        });
    }

    @Test
    void execute_shouldKeepCredentialId_whenSameOriginRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/next"))
                .thenReturn(ok());

        follower()
                .execute(
                        withCredentialId("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        assertThat(secondExecutedRequest().credentialId()).isEqualTo("app:1/installation:42");
    }

    @Test
    void execute_shouldDropCredentialAndAuth_whenSameOriginRedirectLeavesApiBase() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://host.example.test/archive/main.tar.gz"))
                .thenReturn(ok());

        follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/")))
                .execute(
                        withCredentialId("https://host.example.test/svc/v1/repos/o/r/tarball"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        final HttpRequest second = secondExecutedRequest();
        assertThat(second.credentialId()).isNull();
        assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void execute_shouldDropCredentialAndAuth_whenCallStartedOutsideApiBaseAndRedirectStaysOutside() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://host.example.test/api/uploads/b"))
                .thenReturn(ok());

        follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/")))
                .execute(
                        withCredentialId("https://host.example.test/api/uploads/a"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        final HttpRequest second = secondExecutedRequest();
        assertThat(second.credentialId()).isNull();
        assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void execute_shouldRefusePost_whenCallStartedOutsideApiBaseAndRedirectStaysOutside() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://host.example.test/api/uploads/b"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST,
                URI.create("https://host.example.test/api/uploads/a"),
                headers(),
                HttpBody.of(new byte[] {1}));

        final Throwable thrown =
                catchThrowable(() -> follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/")))
                        .execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown)
                .isInstanceOf(HttpException.class)
                .hasMessage("redirect outside the API base path not followed for POST");
    }

    @Test
    void execute_shouldKeepCredentialId_whenSameOriginRedirectStaysUnderApiBase() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://host.example.test/svc/v1/repositories/1"))
                .thenReturn(ok());

        follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/")))
                .execute(
                        withCredentialId("https://host.example.test/svc/v1/repos/o/r"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        assertThat(secondExecutedRequest().credentialId()).isEqualTo("app:1/installation:42");
    }

    @Test
    void execute_shouldRefusePost_whenSameOriginRedirectLeavesApiBase() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://host.example.test/other-app/x"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST,
                URI.create("https://host.example.test/svc/v1/user"),
                headers(),
                HttpBody.of(new byte[] {1}));

        final Throwable thrown =
                catchThrowable(() -> follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/")))
                        .execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown)
                .isInstanceOf(HttpException.class)
                .hasMessage("redirect outside the API base path not followed for POST");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldDropCredentialId_whenCrossOriginRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://example.test/user"))
                .thenReturn(ok());

        follower()
                .execute(
                        withCredentialId("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true);

        assertThat(secondExecutedRequest().credentialId()).isNull();
    }

    @Test
    void execute_shouldRefuse_whenHttpsRedirectsToHttp() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "http://api.example.test/user"));

        final Throwable thrown = catchThrowable(() -> follower()
                .execute(
                        get("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true));

        assertThat(thrown).isInstanceOf(HttpException.class).hasMessage("https to http redirect is not followed");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldRefusePost_whenCrossOrigin() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://example.test/user"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST, URI.create("https://api.example.test/user"), headers(), HttpBody.of(new byte[] {1}));

        final Throwable thrown = catchThrowable(
                () -> follower().execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown).isInstanceOf(HttpException.class).hasMessage("cross-origin redirect not followed for POST");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldThrow_whenHopLimitExceeded() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/next"));

        final Throwable thrown = catchThrowable(() -> follower()
                .execute(
                        get("https://api.example.test/start"),
                        RedirectPolicy.defaults().maxHops(),
                        true));

        assertThat(thrown).isInstanceOf(HttpException.class).hasMessageContaining("hop limit");
        verify(transport, times(RedirectPolicy.defaults().maxHops() + 1)).execute(any());
    }

    @Test
    void requireRedirectLocation_shouldResolveLocation_whenStatusIsRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://download.example.test/alice/hello-world/tar.gz/main"));
        final HttpRequest request = get("https://api.example.test/repos/alice/hello-world/tarball/main");
        final RedirectExecutor follower = follower();
        final RedirectExecutor.Exchange exchange =
                follower.execute(request, RedirectPolicy.defaults().maxHops(), false);

        final URI location = follower.requireRedirectLocation(exchange, HttpMethod.GET);

        assertThat(location).isEqualTo(URI.create("https://download.example.test/alice/hello-world/tar.gz/main"));
    }

    @Test
    void requireRedirectLocation_shouldThrow_whenStatusIsNotRedirect() {
        final RedirectExecutor.Exchange exchange =
                new RedirectExecutor.Exchange(ok(), ParsedUrl.of(URI.create("https://api.example.test/user")), false);

        final Throwable thrown = catchThrowable(() -> follower().requireRedirectLocation(exchange, HttpMethod.GET));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("response is not a redirect");
    }

    @Test
    void execute_shouldKeepBodyHeaders_whenTemporaryRedirectKeepsBody() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.TEMPORARY_REDIRECT, "https://api.example.test/next"))
                .thenReturn(ok());

        follower().execute(postWithJsonBody(), RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertThat(second.headers().get(CONTENT_TYPE)).contains("application/json");
    }

    @Test
    void execute_shouldDropBodyHeaders_whenRedirectDropsBody() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/next"))
                .thenReturn(ok());
        final HttpRequest request = new HttpRequest(
                HttpMethod.GET,
                URI.create("https://api.example.test/start"),
                HttpHeaders.empty()
                        .merge(headers())
                        .with(CONTENT_TYPE, "application/json")
                        .with(CONTENT_ENCODING, "identity"),
                HttpBody.absent());

        follower().execute(request, RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(CONTENT_TYPE)).isEmpty();
            softly.assertThat(second.headers().get(CONTENT_ENCODING)).isEmpty();
            softly.assertThat(second.headers().get(ACCEPT)).contains("application/vnd.example+json");
        });
    }

    @Test
    void execute_shouldNotExposeQueryOrLocationHeader_whenRedirectIsRefused() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "http://user:pw@api.example.test/user?token=signed#frag"));
        final HttpRequest request = get("https://api.example.test/user?code=secret");

        final Throwable thrown = catchThrowable(
                () -> follower().execute(request, RedirectPolicy.defaults().maxHops(), true));

        final HttpException error = (HttpException) thrown;
        assertSoftly(softly -> {
            softly.assertThat(error.uri()).contains(URI.create("https://api.example.test/user"));
            softly.assertThat(error.headers().get(LOCATION)).isEmpty();
            softly.assertThat(error.getMessage()).doesNotContain("token").doesNotContain("secret");
        });
    }

    @Test
    void execute_shouldReportLocationWithoutQuery_whenSchemeIsUnsupported() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "ftp://files.example/a/b?token=signed"));

        final Throwable thrown = catchThrowable(() -> follower()
                .execute(
                        get("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true));

        assertThat(thrown)
                .isInstanceOf(HttpException.class)
                .hasMessage("redirect to unsupported scheme")
                .extracting(error -> ((HttpException) error).rawBody().orElseThrow())
                .isEqualTo("ftp://files.example/a/b");
    }

    @Test
    void execute_shouldOmitLocation_whenLocationCannotBeParsed() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "http://[bad?token=signed"));

        final Throwable thrown = catchThrowable(() -> follower()
                .execute(
                        get("https://api.example.test/user"),
                        RedirectPolicy.defaults().maxHops(),
                        true));

        assertThat(thrown)
                .isInstanceOf(HttpException.class)
                .hasMessage("redirect Location is malformed")
                .extracting(error -> ((HttpException) error).rawBody())
                .isEqualTo(Optional.empty());
    }

    @Test
    void stream_shouldFollowRedirectAndKeepFinalBodyOpen_whenFound() throws IOException {
        final HttpStreamResponse redirect =
                HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://codeload.example/zip"));
        final HttpStreamResponse ok = HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1, 2}));
        when(transport.stream(any())).thenReturn(redirect, ok);

        final RedirectExecutor.StreamExchange exchange = follower().stream(
                get("https://api.example.test/zipball"),
                RedirectPolicy.defaults().maxHops(),
                true);

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).stream(captor.capture());
        final HttpRequest second = captor.getAllValues().get(1);
        assertSoftly(softly -> {
            softly.assertThat(exchange.response()).isSameAs(ok);
            softly.assertThat(second.uri()).isEqualTo(URI.create("https://codeload.example/zip"));
            softly.assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
            softly.assertThat(second.headers().get(USER_AGENT)).contains("test-agent");
        });
    }

    @Test
    void stream_shouldDropCredentialAndAuth_whenSameOriginRedirectLeavesApiBase() throws IOException {
        final HttpStreamResponse redirect = HttpStreamResponse.buffered(
                redirect(HttpStatus.FOUND, "https://host.example.test/archive/main.tar.gz"));
        final HttpStreamResponse ok = HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1}));
        when(transport.stream(any())).thenReturn(redirect, ok);

        follower(ParsedUrl.of(URI.create("https://host.example.test/svc/v1/"))).stream(
                withCredentialId("https://host.example.test/svc/v1/repos/o/r/tarball"),
                RedirectPolicy.defaults().maxHops(),
                true);

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).stream(captor.capture());
        final HttpRequest second = captor.getAllValues().get(1);
        assertThat(second.credentialId()).isNull();
        assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void stream_shouldStripAuth_whenCrossOriginGet() throws IOException {
        final HttpStreamResponse redirect =
                HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://example.test/user"));
        final HttpStreamResponse ok = HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1}));
        when(transport.stream(any())).thenReturn(redirect, ok);

        follower().stream(
                get("https://api.example.test/user"), RedirectPolicy.defaults().maxHops(), true);

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).stream(captor.capture());
        final HttpRequest second = captor.getAllValues().get(1);
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
            softly.assertThat(second.headers().asMap().keySet())
                    .containsExactlyInAnyOrder(ACCEPT, USER_AGENT, VERSION_HEADER);
        });
    }

    @Test
    void stream_shouldKeepOnlyOriginAndPathOfFinalUri_whenRedirectIsCrossOrigin() throws IOException {
        final HttpStreamResponse redirect =
                HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://codeload.example/zip?token=signed"));
        final HttpStreamResponse ok = HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1}));
        when(transport.stream(any())).thenReturn(redirect, ok);

        final RedirectExecutor.StreamExchange exchange = follower().stream(
                get("https://api.example.test/zipball"),
                RedirectPolicy.defaults().maxHops(),
                true);

        assertThat(exchange.uri()).isEqualTo(URI.create("https://codeload.example/zip"));
    }

    @Test
    void stream_shouldKeepQueryOfFinalUri_whenRedirectIsSameOrigin() throws IOException {
        final HttpStreamResponse redirect =
                HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://api.example.test/next?page=2"));
        final HttpStreamResponse ok = HttpStreamResponse.streaming(
                HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1}));
        when(transport.stream(any())).thenReturn(redirect, ok);

        final RedirectExecutor.StreamExchange exchange = follower().stream(
                get("https://api.example.test/zipball"),
                RedirectPolicy.defaults().maxHops(),
                true);

        assertThat(exchange.uri()).isEqualTo(URI.create("https://api.example.test/next?page=2"));
    }

    @Test
    void stream_shouldReturnRedirect_whenFollowIsFalse() throws IOException {
        final HttpStreamResponse redirect =
                HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://codeload.example/zip"));
        when(transport.stream(any())).thenReturn(redirect);

        final RedirectExecutor.StreamExchange exchange = follower().stream(
                get("https://api.example.test/zipball"),
                RedirectPolicy.defaults().maxHops(),
                false);

        assertThat(exchange.response()).isSameAs(redirect);
        verify(transport, times(1)).stream(any());
    }

    @Test
    void stream_shouldRefuseWriteMethod_whenFoundWouldDowngradeToGet() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.buffered(redirect(HttpStatus.FOUND, "https://api.example.test/next")));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST, URI.create("https://api.example.test/start"), headers(), HttpBody.of(new byte[] {1}));

        final Throwable thrown = catchThrowable(
                () -> follower().stream(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown).isInstanceOf(HttpException.class).hasMessageContaining("would be sent as GET");
        verify(transport, times(1)).stream(any());
    }

    private static HttpRequest postWithJsonBody() {
        return new HttpRequest(
                HttpMethod.POST,
                URI.create("https://api.example.test/start"),
                HttpHeaders.empty()
                        .with(AUTHORIZATION, "Bearer gh-token")
                        .with(CONTENT_TYPE, "application/json")
                        .with(CONTENT_ENCODING, "identity"),
                HttpBody.of(new byte[] {1}));
    }

    private RedirectExecutor follower() {
        return follower(ParsedUrl.of(URI.create("https://api.example.test/")));
    }

    private RedirectExecutor follower(final ParsedUrl apiBase) {
        final RedirectPolicy policy = new RedirectPolicy(
                5, Set.of(HttpMethod.GET, HttpMethod.HEAD), Set.of(ACCEPT, USER_AGENT, VERSION_HEADER));
        return new RedirectExecutor(
                transport, policy, (method, response, uri) -> HttpException.from(method, response, uri), apiBase);
    }

    private static HttpRequest withCredentialId(final String uri) {
        return new HttpRequest(
                HttpMethod.GET, ParsedUrl.of(URI.create(uri)), headers(), HttpBody.absent(), "app:1/installation:42");
    }

    private static HttpRequest get(final String uri) {
        return new HttpRequest(HttpMethod.GET, URI.create(uri), headers(), HttpBody.absent());
    }

    private static HttpHeaders headers() {
        return HttpHeaders.empty()
                .with(AUTHORIZATION, "Bearer gh-token")
                .with(ACCEPT, "application/vnd.example+json")
                .with(USER_AGENT, "test-agent")
                .with(VERSION_HEADER, "2026-03-10")
                .with("X-Custom", "secret");
    }

    private static HttpResponse redirect(final HttpStatus status, final String location) {
        return new HttpResponse(
                status.code(), HttpHeaders.fromWire(Map.of(LOCATION, List.of(location))), HttpBody.absent());
    }

    private HttpRequest secondExecutedRequest() throws IOException {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        return captor.getAllValues().get(1);
    }

    private static HttpResponse ok() {
        return new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpStatus.class,
            names = {"TEMPORARY_REDIRECT", "PERMANENT_REDIRECT"})
    void execute_shouldForwardSameStreamBodyUnread_whenKeepAliveRedirect(final HttpStatus status) throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(status, "https://api.example.test/next"))
                .thenReturn(ok());
        final int[] opened = {0};
        final HttpBody stream = HttpBody.ofStream(
                () -> {
                    opened[0]++;
                    return new ByteArrayInputStream(new byte[] {1});
                },
                1);
        final HttpRequest request =
                new HttpRequest(HttpMethod.POST, URI.create("https://api.example.test/start"), headers(), stream);

        follower().execute(request, RedirectPolicy.defaults().maxHops(), true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(second.body()).isSameAs(stream);
            softly.assertThat(opened[0]).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/next?token=signed", "http://[bad,", "https://codeload.example/x?token=signed"})
    void execute_shouldReportSafeLocation_whenWriteMethodRedirectLocationIsOfAnyForm(final String location)
            throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, location));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST,
                URI.create("https://api.example.test/start?code=secret"),
                headers(),
                HttpBody.of(new byte[] {1}));

        final Throwable thrown = catchThrowable(
                () -> follower().execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown).isInstanceOf(HttpException.class);
        assertThat(thrown.getMessage()).doesNotContain("token").doesNotContain("secret");
    }

    @Test
    void execute_shouldNotQuoteTheRequestQuery_whenTheRequestHostIsNotAValidUriHost() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.SEE_OTHER, "/next?token=signed"));
        final HttpRequest request = new HttpRequest(
                HttpMethod.POST, URI.create("https://my_host/start?code=secret"), headers(), HttpBody.of(new byte[] {1
                }));

        final Throwable thrown = catchThrowable(
                () -> follower().execute(request, RedirectPolicy.defaults().maxHops(), true));

        assertThat(thrown).isInstanceOf(HttpException.class);
        assertThat(thrown.getMessage()).doesNotContain("token").doesNotContain("secret");
    }

    @Test
    void execute_shouldThrow_whenRedirectHasNoLocation() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));
        final HttpRequest request = get("https://api.example.test/start");
        final RedirectExecutor executor = follower();

        final Throwable thrown = catchThrowable(() -> executor.execute(request, 5, true));

        assertThat(thrown).hasMessageContaining("missing Location");
    }

    @ParameterizedTest
    @CsvSource({"ftp://files.example.test/x,unsupported scheme", "http://[bad,malformed", "//:80,malformed"})
    void execute_shouldThrow_whenLocationIsNotFollowable(final String location, final String message)
            throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, location));
        final HttpRequest request = get("https://api.example.test/start");
        final RedirectExecutor executor = follower();

        final Throwable thrown = catchThrowable(() -> executor.execute(request, 5, true));

        assertThat(thrown).hasMessageContaining(message);
    }

    @Test
    void execute_shouldFollowBothHops_whenSecondHopIsAlsoCrossOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://one.example.test/a"))
                .thenReturn(redirect(HttpStatus.FOUND, "https://two.example.test/b"))
                .thenReturn(ok());
        final HttpRequest request = get("https://api.example.test/start");
        final RedirectExecutor executor = follower();

        final RedirectExecutor.Exchange exchange = executor.execute(request, 5, true);

        assertThat(exchange.response().statusCode()).isEqualTo(HttpStatus.OK.code());
    }

    @Test
    void execute_shouldDropCredentials_whenSameOriginHopLeavesApiPath() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/download/file"))
                .thenReturn(ok());
        final HttpRequest request = get("https://api.example.test/v1/file");
        final RedirectExecutor executor = follower(ParsedUrl.of(URI.create("https://api.example.test/v1/")));

        executor.execute(request, 5, true);

        assertThat(secondExecutedRequest().headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void execute_shouldKeepCredentials_whenSameOriginHopStaysUnderApiPath() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://api.example.test/v1/other"))
                .thenReturn(ok());
        final HttpRequest request = get("https://api.example.test/v1/file");
        final RedirectExecutor executor = follower(ParsedUrl.of(URI.create("https://api.example.test/v1/")));

        executor.execute(request, 5, true);

        assertThat(secondExecutedRequest().headers().get(AUTHORIZATION)).isPresent();
    }

    @Test
    void execute_shouldKeepBodyHeaders_whenWriteMethodIsRedirectedAcrossOrigins() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.MOVED_PERMANENTLY, "https://other.example.test/upload"))
                .thenReturn(ok());
        final RedirectPolicy policy =
                new RedirectPolicy(5, Set.of(HttpMethod.GET, HttpMethod.PUT), Set.of(ACCEPT, USER_AGENT));
        final RedirectExecutor executor = new RedirectExecutor(
                transport,
                policy,
                (method, response, uri) -> HttpException.from(method, response, uri),
                ParsedUrl.of(URI.create("https://api.example.test/")));
        final HttpRequest request = new HttpRequest(
                HttpMethod.PUT,
                URI.create("https://api.example.test/upload"),
                headers().with(CONTENT_TYPE, "text/plain").with(CONTENT_ENCODING, "gzip"),
                HttpBody.of(new byte[] {1}));

        executor.execute(request, 5, true);

        final HttpRequest second = secondExecutedRequest();
        assertSoftly(softly -> {
            softly.assertThat(second.headers().get(CONTENT_TYPE)).contains("text/plain");
            softly.assertThat(second.headers().get(CONTENT_ENCODING)).contains("gzip");
            softly.assertThat(second.headers().get(AUTHORIZATION)).isEmpty();
        });
    }

    @Test
    void execute_shouldThrow_whenLocationHasHttpSchemeButNoHost() throws IOException {
        when(transport.execute(any())).thenReturn(redirect(HttpStatus.FOUND, "http://:80"));
        final HttpRequest request = get("https://api.example.test/start");
        final RedirectExecutor executor = follower();

        final Throwable thrown = catchThrowable(() -> executor.execute(request, 5, true));

        assertThat(thrown).hasMessageContaining("malformed");
    }

    @Test
    void execute_shouldKeepCredentials_whenSameOriginHopIsOnAnotherOriginThanTheApi() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.FOUND, "https://other.example.test/b"))
                .thenReturn(redirect(HttpStatus.FOUND, "https://other.example.test/c"))
                .thenReturn(ok());
        final HttpRequest request = get("https://other.example.test/a");
        final RedirectExecutor executor = follower();

        final RedirectExecutor.Exchange exchange = executor.execute(request, 5, true);

        assertThat(exchange.response().statusCode()).isEqualTo(HttpStatus.OK.code());
    }

    @Test
    void execute_shouldFollowWithoutBody_whenHeadIsRedirectedPermanently() throws IOException {
        when(transport.execute(any()))
                .thenReturn(redirect(HttpStatus.MOVED_PERMANENTLY, "https://api.example.test/next"))
                .thenReturn(ok());
        final HttpRequest request = new HttpRequest(
                HttpMethod.HEAD, URI.create("https://api.example.test/start"), headers(), HttpBody.absent());
        final RedirectExecutor executor = follower();

        executor.execute(request, 5, true);

        assertThat(secondExecutedRequest().method()).isEqualTo(HttpMethod.HEAD);
    }
}
