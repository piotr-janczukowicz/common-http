package pl.piotrjanczukowicz.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.piotrjanczukowicz.http.HttpHeaders;
import pl.piotrjanczukowicz.http.HttpMethod;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.HttpResponse;
import pl.piotrjanczukowicz.http.HttpStatus;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.test.http.HttpTransportMocks;
import pl.piotrjanczukowicz.test.http.PlainJsonCodec;
import pl.piotrjanczukowicz.testsupport.UnitTest;
import pl.piotrjanczukowicz.uri.PathTemplate;

@UnitTest
class HttpResourceApiTest {

    private static final PathTemplate ITEM = PathTemplate.of("/items/{id}");

    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void verbs_shouldSendExpandedPathWithMethod() throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        final ItemApi api = new ItemApi();

        try (final HttpClient client = client()) {
            final HttpClientCaller caller = new HttpClientCaller(client, PlainJsonCodec.create());
            caller.send(api.get(ITEM, 1));
            caller.send(api.post(ITEM, 2));
            caller.send(api.put(ITEM, 3));
            caller.send(api.patch(ITEM, 4));
            caller.send(api.delete(ITEM, 5));
            caller.send(api.post(URI.create("https://api.example.test/upload")));
            caller.send(HttpCall.of(HttpMethod.GET, "/raw"));
        }

        verify(transport, times(7)).execute(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(HttpRequest::method, request -> request.uri().getPath())
                .containsExactly(
                        tuple(HttpMethod.GET, "/items/1"),
                        tuple(HttpMethod.POST, "/items/2"),
                        tuple(HttpMethod.PUT, "/items/3"),
                        tuple(HttpMethod.PATCH, "/items/4"),
                        tuple(HttpMethod.DELETE, "/items/5"),
                        tuple(HttpMethod.POST, "/upload"),
                        tuple(HttpMethod.GET, "/raw"));
    }

    @Test
    void of_shouldThrow_whenPathIsNull() {
        final Throwable thrown = catchThrowable(() -> HttpCall.of(HttpMethod.GET, (String) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("path");
    }

    private HttpClient client() {
        return HttpClient.builder(transport, HttpClientConfiguration.of(URI.create("https://api.example.test/")))
                .build();
    }

    private static final class ItemApi extends HttpResourceApi {

        @Override
        public HttpCall get(final PathTemplate path, final Object... values) {
            return super.get(path, values);
        }

        @Override
        public HttpCall post(final PathTemplate path, final Object... values) {
            return super.post(path, values);
        }

        @Override
        public HttpCall post(final URI uri) {
            return super.post(uri);
        }

        @Override
        public HttpCall put(final PathTemplate path, final Object... values) {
            return super.put(path, values);
        }

        @Override
        public HttpCall patch(final PathTemplate path, final Object... values) {
            return super.patch(path, values);
        }

        @Override
        public HttpCall delete(final PathTemplate path, final Object... values) {
            return super.delete(path, values);
        }
    }
}
