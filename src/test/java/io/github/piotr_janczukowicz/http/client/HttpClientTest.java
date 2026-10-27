package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
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
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@UnitTest
class HttpClientTest {

    private static final URI BASE = URI.create("https://api.example.test/");

    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldMergeHeaders_whenDefaultAndOverlayPresent() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            client.execute(HttpCall.get("/user").withHeaders(HttpHeaders.of("X-Overlay", "1")));
        }

        final HttpRequest sent = capture();
        assertThat(sent.headers().get(ACCEPT)).contains("application/json");
        assertThat(sent.headers().get("X-Overlay")).contains("1");
        assertThat(sent.uri()).isEqualTo(URI.create("https://api.example.test/user"));
    }

    @Test
    void execute_shouldKeepAuthHeader_whenDefaultHeaderHasSameName() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));
        final HttpClientConfiguration configuration = HttpClientConfiguration.builder(BASE)
                .defaultHeaders(HttpHeaders.of("authorization", "Bearer default"))
                .build();

        try (final HttpClient client = HttpClient.builder(transport, configuration)
                .auth(AuthenticationProviders.bearer("secret"))
                .build()) {
            client.execute(HttpCall.get("/user"));
        }

        assertThat(capture().headers().get(AUTHORIZATION)).contains("Bearer secret");
    }

    @Test
    void execute_shouldLetOverlayReplaceAuthHeader_whenSetExplicitlyOnCall() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = HttpClient.builder(transport, config())
                .auth(AuthenticationProviders.bearer("secret"))
                .build()) {
            client.execute(HttpCall.get("/user").withHeaders(HttpHeaders.of(AUTHORIZATION, "Bearer once")));
        }

        assertThat(capture().headers().get(AUTHORIZATION)).contains("Bearer once");
    }

    @Test
    void execute_shouldSendNoCredentialId_whenCallSetsItsOwnAuthorization() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = HttpClient.builder(transport, config())
                .auth(AuthenticationProviders.bearer("secret"))
                .build()) {
            client.execute(HttpCall.get("/user"));
            client.execute(HttpCall.get("/user").withHeaders(HttpHeaders.of(AUTHORIZATION, "Bearer once")));
        }

        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(sent.capture());
        assertSoftly(softly -> {
            softly.assertThat(sent.getAllValues().getFirst().credentialId()).isNotNull();
            softly.assertThat(sent.getAllValues().getLast().credentialId()).isNull();
        });
    }

    @Test
    void execute_shouldThrowCheckedCause_whenAuthFailsWithUncheckedIoException() throws IOException {
        final IOException failure = new SocketTimeoutException("token endpoint timed out");

        try (final HttpClient client = HttpClient.builder(transport, config())
                .auth(AuthenticationProviders.bearer(() -> {
                    throw new UncheckedIOException("could not fetch an installation token", failure);
                }))
                .build()) {
            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isSameAs(failure);
            verify(transport, never()).execute(any());
        }
    }

    @Test
    void stream_shouldReturnResult_whenClosingTheResponseFailsAfterTheHandler() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), failingToClose(new IOException("close failed"))));

        try (final HttpClient client = client()) {
            final String result = client.stream(HttpCall.get("/zip"), response -> "handled");

            assertThat(result).isEqualTo("handled");
        }
    }

    @Test
    void stream_shouldKeepHandlerFailureAndSuppressCloseFailure_whenBothFail() throws IOException {
        final IOException closeFailure = new IOException("close failed");
        final IllegalStateException handlerFailure = new IllegalStateException("handler failed");
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), failingToClose(closeFailure)));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, response -> {
                throw handlerFailure;
            }));

            assertThat(thrown).isSameAs(handlerFailure);
            assertThat(thrown.getSuppressed()).containsExactly(closeFailure);
        }
    }

    @Test
    void execute_shouldNameTheArgument_whenExpectedStatusesHoldNull() {
        final Set<Integer> statuses = new HashSet<>();
        statuses.add(null);

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call, statuses, true));

            assertThat(thrown)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("expectedStatuses element");
        }
    }

    @Test
    void stream_shouldThrowCheckedCause_whenAuthFailsWithUncheckedIoException() {
        final IOException failure = new SocketTimeoutException("token endpoint timed out");

        try (final HttpClient client = HttpClient.builder(transport, config())
                .auth(AuthenticationProviders.bearer(() -> {
                    throw new UncheckedIOException("could not fetch an installation token", failure);
                }))
                .build()) {
            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.stream(call, HttpStreamResponse::statusCode));

            assertThat(thrown).isSameAs(failure);
        }
    }

    @Test
    void execute_shouldReturnResponse_whenExpectedStatus() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            final HttpResponse response = client.execute(HttpCall.get("/missing"), Set.of(HttpStatus.NOT_FOUND.code()));

            assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.code());
        }
    }

    @Test
    void execute_shouldThrowHttpException_whenUnsuccessful() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/missing");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }
    }

    @Test
    void close_shouldCloseTransport_whenClientClosed() {
        final HttpClient client = client();

        client.close();

        verify(transport).close();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/users/octo cat",
                "/a|b",
                "/a{b}",
                "/zażółć",
                "/a%2e",
                "/q%20r",
                "/a\\b",
                "/a;b=c",
            })
    void execute_shouldSendTheSameUrlAsBefore_whenPathHasSpecialCharacters(final String path) throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));
        final Map<String, List<String>> query = Map.of("q", List.of("a b", "c|d", "{e}", "zażółć", "%2e"));

        try (final HttpClient client = client()) {
            client.execute(HttpCall.get(path).withQueryValues(query));
        }

        final HttpRequest sent = capture();
        assertThat(sent.uri()).isEqualTo(HttpUris.resolveApiPath(BASE, path, query));
    }

    @Test
    void execute_shouldAppendQuery_whenTargetIsAbsoluteUri() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            client.execute(HttpCall.get(URI.create("https://api.example.test/page?x=1"))
                    .withQueryValues(Map.of("page", List.of("2"))));
        }

        assertThat(capture().uri()).isEqualTo(URI.create("https://api.example.test/page?x=1&page=2"));
    }

    @Test
    void execute_shouldReject_whenAbsoluteUriTargetHasDotSegments() {
        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get(URI.create("https://api.example.test/a/%2e%2e/b"));

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
        }
    }

    @Test
    void execute_shouldReject_whenAbsoluteUriTargetIsOnAnotherOrigin() {
        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get(URI.create("https://evil.test/x"));

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
        }
    }

    @Test
    void execute_shouldRejectNonHttpAbsoluteUriTarget_whenQueryIsAbsent() {
        try (final HttpClient client = client()) {
            final HttpCall withoutQuery = HttpCall.get(URI.create("ftp://api.example.test/x"));

            final Throwable thrown = catchThrowable(() -> client.execute(withoutQuery));

            assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
        }
    }

    @Test
    void execute_shouldRejectNonHttpAbsoluteUriTarget_whenQueryIsPresent() {
        try (final HttpClient client = client()) {
            final HttpCall withQuery =
                    HttpCall.get(URI.create("ftp://api.example.test/x")).withQueryValues(Map.of("a", List.of("b")));

            final Throwable thrown = catchThrowable(() -> client.execute(withQuery));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uri");
        }
    }

    private HttpRequest capture() throws IOException {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        return captor.getValue();
    }

    private static InputStream failingToClose(final IOException failure) {
        return new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                throw failure;
            }
        };
    }

    private HttpClient client() {
        return HttpClient.builder(transport, config()).build();
    }

    private static HttpClientConfiguration config() {
        return HttpClientConfiguration.builder(BASE)
                .defaultHeaders(HttpHeaders.of(ACCEPT, "application/json"))
                .build();
    }

    @Test
    void redirectLocation_shouldThrowIllegalState_whenResponseIsOk() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/tarball").withQuery(Map.of("token", "secret"));

            final Throwable thrown = catchThrowable(() -> client.redirectLocation(call));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected a redirect but got HTTP 200")
                    .hasMessageContaining("https://api.example.test/tarball")
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void redirectLocation_shouldMapError_whenResponseIsNotFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/missing");

            final Throwable thrown = catchThrowable(() -> client.redirectLocation(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }
    }

    @Test
    void redirectLocationIfAny_shouldReturnEmpty_whenResponseIsOk() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));

        try (final HttpClient client = client()) {
            final Optional<URI> location = client.redirectLocationIfAny(HttpCall.get("/contents/readme"));

            assertThat(location).isEmpty();
        }
    }

    @Test
    void redirectLocationIfAny_shouldReturnEmpty_whenResponseIsAccepted() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.ACCEPTED.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (final HttpClient client = client()) {
            final Optional<URI> location = client.redirectLocationIfAny(HttpCall.get("/sbom"));

            assertThat(location).isEmpty();
        }
    }

    @Test
    void redirectLocationIfAny_shouldReturnLocation_whenResponseIsFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.of(LOCATION, "https://download.example.test/x"),
                        HttpBody.absent()));

        try (final HttpClient client = client()) {
            final Optional<URI> location = client.redirectLocationIfAny(HttpCall.get("/tarball"));

            assertThat(location).contains(URI.create("https://download.example.test/x"));
        }
    }

    @Test
    void redirectLocationIfAny_shouldMapError_whenResponseIsNotFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/missing");

            final Throwable thrown = catchThrowable(() -> client.redirectLocationIfAny(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }
    }

    @Test
    void redirectLocationIfAny_shouldThrow_whenCallNull() {
        try (final HttpClient client = client()) {
            final Throwable thrown = catchThrowable(() -> client.redirectLocationIfAny(null));

            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("call");
        }
    }

    @Test
    void redirectLocation_shouldReturnLocation_whenResponseIsFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.of(LOCATION, "https://download.example.test/x"),
                        HttpBody.absent()));

        try (final HttpClient client = client()) {
            final URI location = client.redirectLocation(HttpCall.get("/tarball"));

            assertThat(location).hasToString("https://download.example.test/x");
        }
    }

    @Test
    void build_shouldMapErrorsWithTheErrorMapper_whenOneIsGiven() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR.code(), HttpHeaders.empty(), null));
        try (final HttpClient client = HttpClient.builder(transport, config())
                .errorMapper((method, response, uri) -> new IllegalStateException("mapped"))
                .build()) {
            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("mapped");
        }
    }

    @Test
    void build_shouldAcceptOtherOrigin_whenOriginGuardIsGiven() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));
        final HttpOriginGuard guard = new HttpOriginGuard(URI.create("https://other.example.test/"));
        try (final HttpClient client =
                HttpClient.builder(transport, config()).originGuard(guard).build()) {
            final HttpCall call = HttpCall.of(HttpMethod.GET, URI.create("https://other.example.test/user"));

            final HttpResponse response = client.execute(call);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        }
    }

    @Test
    void build_shouldLimitRedirects_whenRedirectPolicyIsGiven() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of("Location", List.of("https://api.example.test/next"))),
                        null));
        final RedirectPolicy policy = new RedirectPolicy(1, Set.of(HttpMethod.GET), Set.of());
        try (final HttpClient client =
                HttpClient.builder(transport, config()).redirectPolicy(policy).build()) {
            final HttpCall call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).hasMessageContaining("hop limit");
        }
    }

    @Test
    void stream_shouldRethrow_whenHandlerFailsWithIoException() throws IOException {
        final IOException failure = new IOException("handler failed");
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[0])));
        try (final HttpClient client = client()) {
            final HttpCall call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, response -> {
                throw failure;
            }));

            assertThat(thrown).isSameAs(failure);
        }
    }

    @Test
    void stream_shouldSuppressCloseFailure_whenRenewalCheckFails() throws IOException {
        final IOException closeFailure = new IOException("close failed");
        final IllegalStateException renewalFailure = new IllegalStateException("renewal failed");
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.UNAUTHORIZED.code(), HttpHeaders.empty(), failingToClose(closeFailure)));
        final AuthenticationProvider auth = new AuthenticationProvider() {
            @Override
            public Optional<Authentication> authentication() {
                return Optional.of(Authentication.ofBearer("token"));
            }

            @Override
            public boolean isRenewableAfterRejection(final Authentication rejected) {
                throw renewalFailure;
            }
        };
        try (final HttpClient client =
                HttpClient.builder(transport, config()).auth(auth).build()) {
            final HttpCall call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, response -> "handled"));

            assertSoftly(softly -> {
                softly.assertThat(thrown).isSameAs(renewalFailure);
                softly.assertThat(thrown.getSuppressed()).containsExactly(closeFailure);
            });
        }
    }
}
