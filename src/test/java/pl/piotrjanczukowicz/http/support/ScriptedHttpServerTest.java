package pl.piotrjanczukowicz.http.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import pl.piotrjanczukowicz.http.HttpRequest;
import pl.piotrjanczukowicz.http.transport.HttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransports;
import pl.piotrjanczukowicz.test.UnitTest;

@UnitTest
class ScriptedHttpServerTest {

    @Test
    void close_shouldRethrowAssertionError_whenScriptFailedAnAssertion() throws IOException {
        final ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            throw new AssertionError("script expectation");
        });
        final HttpTransport transport = HttpTransports.okHttp();
        final HttpRequest request = server.request();
        catchThrowable(() -> transport.execute(request));

        final Throwable thrown = catchThrowable(server::close);

        assertThat(thrown).isInstanceOf(AssertionError.class).hasMessageContaining("script expectation");
    }

    @Test
    void close_shouldWrapFailure_whenScriptThrewUnexpectedException() throws IOException {
        final ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            throw new IllegalStateException("broken script");
        });
        final HttpTransport transport = HttpTransports.okHttp();
        final HttpRequest request = server.request();
        catchThrowable(() -> transport.execute(request));

        final Throwable thrown = catchThrowable(server::close);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void close_shouldNotThrow_whenClientClosedTheConnection() throws IOException {
        final ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            throw new IOException("client gone");
        });
        final HttpTransport transport = HttpTransports.okHttp();
        final HttpRequest request = server.request();
        catchThrowable(() -> transport.execute(request));

        final Throwable thrown = catchThrowable(server::close);

        assertThat(thrown).isNull();
        assertThat(server.disconnects()).isEqualTo(1);
    }
}
