package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static pl.piotrjanczukowicz.http.HttpHeaderNames.LOCATION;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.piotrjanczukowicz.http.HttpBody;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.HttpStreamResponse;
import pl.piotrjanczukowicz.http.client.authentication.Authentication;
import pl.piotrjanczukowicz.http.client.authentication.AuthenticationProvider;
import pl.piotrjanczukowicz.http.client.authentication.AuthenticationProviders;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransportMocks;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class HttpClientAuthRenewalTest {

    private static final URI BASE = URI.create("https://api.example.test/");

    private HttpTransport transport;

    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicInteger generation = new AtomicInteger();

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldSendAgainWithNewCredentials_whenAuthRenewsAfter401() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized()).thenReturn(ok());

        try (HttpClient client = client(true)) {
            final HttpResponse response = client.execute(HttpCall.get("/user"));

            assertThat(response.statusCode()).isEqualTo(200);
        }

        assertThat(rejections).hasValue(1);
        verify(transport, times(2)).execute(any());
    }

    @Test
    void execute_shouldSurfaceTheSecond401_whenRenewedCredentialsAreRejectedToo() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized());

        try (HttpClient client = client(true)) {
            final var call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        verify(transport, times(2)).execute(any());
    }

    @Test
    void execute_shouldNotSendAgain_whenAuthDoesNotRenew() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized());

        try (HttpClient client = client(false)) {
            final var call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        assertThat(rejections).hasValue(1);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldNotAskAuth_whenThe401CameFromAnotherOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://cdn.example.test/file"))),
                        HttpBody.absent()))
                .thenReturn(unauthorized());

        try (HttpClient client = client(true)) {
            final var call = HttpCall.get("/file");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        assertThat(rejections).hasValue(0);
        verify(transport, times(2)).execute(any());
    }

    @Test
    void execute_shouldNotAskAuth_whenCallSetsItsOwnAuthorization() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized());
        final HttpCall call = HttpCall.get("/user").withHeader(AUTHORIZATION, "Bearer own");

        final Throwable thrown;
        try (HttpClient client = client(true)) {
            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(HttpException.class);
        assertThat(rejections).hasValue(0);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldNotAskForCredentials_whenCallSetsItsOwnAuthorization() throws IOException {
        when(transport.execute(any())).thenReturn(ok());
        final AtomicInteger asked = new AtomicInteger();
        final AuthenticationProvider auth = AuthenticationProviders.bearer(
                () -> {
                    asked.incrementAndGet();
                    return "client";
                },
                rejected -> false);
        final HttpCall call = HttpCall.get("/user").withHeader(AUTHORIZATION, "Bearer own");

        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(auth)
                .build()) {
            client.execute(call);
        }

        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(sent.capture());
        assertSoftly(softly -> {
            softly.assertThat(asked).hasValue(0);
            softly.assertThat(sent.getValue().headers().get(AUTHORIZATION)).contains("Bearer own");
            softly.assertThat(sent.getValue().credentialId()).isNull();
        });
    }

    @Test
    void execute_shouldSendCall_whenAuthFailsAndCallSetsItsOwnAuthorization() throws IOException {
        when(transport.execute(any())).thenReturn(ok());
        final AuthenticationProvider failing = AuthenticationProviders.bearer(
                () -> {
                    throw new IllegalStateException("token unavailable");
                },
                rejected -> false);
        final HttpCall call = HttpCall.get("/user").withHeader(AUTHORIZATION, "Bearer own");

        final HttpResponse response;
        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(failing)
                .build()) {
            response = client.execute(call);
        }

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void execute_shouldNotAskAuth_whenStatusIsNot401() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.FORBIDDEN.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (HttpClient client = client(true)) {
            final var call = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(call));

            assertThat(thrown).isInstanceOf(HttpException.class);
        }

        assertThat(rejections).hasValue(0);
    }

    @Test
    void execute_shouldFollowRedirectWithNewCredentials_whenAuthRenewsAfter401() throws IOException {
        when(transport.execute(any()))
                .thenReturn(unauthorized())
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.example.test/next"))),
                        HttpBody.absent()))
                .thenReturn(ok());

        try (HttpClient client = client(true)) {
            final HttpResponse response = client.execute(HttpCall.get("/user"));

            assertThat(response.statusCode()).isEqualTo(200);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).execute(captor.capture());
        assertThat(rejections).hasValue(1);
        assertThat(captor.getAllValues().getLast().headers().get(AUTHORIZATION)).contains("Bearer t1");
    }

    @Test
    void stream_shouldFollowRedirectWithNewCredentials_whenAuthRenewsAfter401() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.buffered(unauthorized()))
                .thenReturn(HttpStreamResponse.buffered(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.example.test/next"))),
                        HttpBody.absent())))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1})));

        try (HttpClient client = client(true)) {
            final int status = client.stream(HttpCall.get("/zip"), HttpStreamResponse::statusCode);

            assertThat(status).isEqualTo(200);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).stream(captor.capture());
        assertThat(rejections).hasValue(1);
        assertThat(captor.getAllValues().getLast().headers().get(AUTHORIZATION)).contains("Bearer t1");
    }

    @Test
    void stream_shouldSendAgainWithNewCredentials_whenAuthRenewsAfter401() throws IOException {
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.buffered(unauthorized()))
                .thenReturn(HttpStreamResponse.streaming(
                        HttpStatus.OK.code(), HttpHeaders.empty(), new ByteArrayInputStream(new byte[] {1})));

        try (HttpClient client = client(true)) {
            final int status = client.stream(HttpCall.get("/zip"), HttpStreamResponse::statusCode);

            assertThat(status).isEqualTo(200);
        }

        assertThat(rejections).hasValue(1);
        verify(transport, times(2)).stream(any());
    }

    @Test
    void stream_shouldCloseTheResponse_whenAuthFailsWhileDecidingToRenew() throws IOException {
        final AtomicBoolean isClosed = new AtomicBoolean();
        final ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                isClosed.set(true);
            }
        };
        when(transport.stream(any()))
                .thenReturn(HttpStreamResponse.streaming(HttpStatus.UNAUTHORIZED.code(), HttpHeaders.empty(), body));
        final AuthenticationProvider failing = AuthenticationProviders.bearer(() -> "t", rejected -> {
            throw new IllegalStateException("auth failed");
        });

        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(failing)
                .build()) {
            final var call = HttpCall.get("/zip");

            final Throwable thrown = catchThrowable(() -> client.stream(call, HttpStreamResponse::statusCode));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("auth failed");
        }

        assertThat(isClosed).isTrue();
    }

    @Test
    void isRenewableAfterRejection_shouldBeFalse_whenAuthDoesNotOverrideIt() {
        assertThat(AuthenticationProviders.none().isRenewableAfterRejection(Authentication.ofBearer("x")))
                .isFalse();
    }

    @Test
    void execute_shouldRenewBearerToken_whenPredicateAcceptsRejectedToken() throws IOException {
        final List<String> rejectedTokens = new ArrayList<>();
        final AtomicInteger issued = new AtomicInteger();
        when(transport.execute(any())).thenReturn(unauthorized()).thenReturn(ok());
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "token-" + issued.get(), rejected -> {
            rejectedTokens.add(rejected);
            issued.incrementAndGet();
            return true;
        });

        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(auth)
                .build()) {
            client.execute(HttpCall.get("/user"));
        }

        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(sent.capture());
        assertSoftly(softly -> {
            softly.assertThat(rejectedTokens).containsExactly("token-0");
            softly.assertThat(sent.getAllValues().getFirst().headers().get(AUTHORIZATION))
                    .contains("Bearer token-0");
            softly.assertThat(sent.getAllValues().get(1).headers().get(AUTHORIZATION))
                    .contains("Bearer token-1");
        });
    }

    @Test
    void execute_shouldNotRenewBearerToken_whenPredicateRejectsRejectedToken() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized());
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "token", rejected -> false);

        final Throwable thrown;
        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(auth)
                .build()) {
            final var call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(HttpException.class);
        verify(transport, times(1)).execute(any());
    }

    private static HttpResponse unauthorized() {
        return new HttpResponse(HttpStatus.UNAUTHORIZED.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    private static HttpResponse ok() {
        return new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null);
    }

    @Test
    void execute_shouldSendRequestWithoutCredentialIdAndHeader_whenNoAuthentication() throws IOException {
        when(transport.execute(any())).thenReturn(ok());

        try (HttpClient client = HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(AuthenticationProviders.none())
                .build()) {
            client.execute(HttpCall.get("/user"));
        }

        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(sent.capture());
        assertSoftly(softly -> {
            softly.assertThat(sent.getValue().credentialId()).isNull();
            softly.assertThat(sent.getValue().headers().get(AUTHORIZATION)).isEmpty();
        });
    }

    @Test
    void execute_shouldThrowRenewalFailureWithThe401Attached_whenRenewingCredentialsFails() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorized());
        final HttpClient client = failingRenewalClient();

        final var call = HttpCall.get("/user");

        final Throwable thrown = catchThrowable(() -> client.execute(call));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("token unavailable");
            softly.assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(HttpException.class);
        });
        verify(transport, times(1)).execute(any());
    }

    @Test
    void stream_shouldThrowRenewalFailureWithThe401Attached_whenRenewingCredentialsFails() throws IOException {
        when(transport.stream(any())).thenReturn(HttpStreamResponse.buffered(unauthorized()));
        final HttpClient client = failingRenewalClient();

        final var call = HttpCall.get("/zip");

        final Throwable thrown = catchThrowable(() -> client.stream(call, HttpStreamResponse::statusCode));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("token unavailable");
            softly.assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(HttpException.class);
        });
        verify(transport, times(1)).stream(any());
    }

    private HttpClient failingRenewalClient() {
        final AtomicInteger issued = new AtomicInteger();
        final AuthenticationProvider auth = AuthenticationProviders.bearer(
                () -> {
                    if (issued.incrementAndGet() > 1) {
                        throw new IllegalStateException("token unavailable");
                    }
                    return "t0";
                },
                rejected -> true);
        return HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(auth)
                .build();
    }

    private HttpClient client(final boolean isRenewable) {
        final AuthenticationProvider auth = AuthenticationProviders.bearer(() -> "t" + generation.get(), rejected -> {
            rejections.incrementAndGet();
            generation.incrementAndGet();
            return isRenewable;
        });
        return HttpClient.builder(
                        transport, HttpClientConfiguration.builder(BASE).build())
                .auth(auth)
                .build();
    }
}
