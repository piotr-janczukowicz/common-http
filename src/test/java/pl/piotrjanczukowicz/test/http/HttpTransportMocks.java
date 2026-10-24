package pl.piotrjanczukowicz.test.http;

import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import pl.piotrjanczukowicz.http.transport.ConcurrencyLimitingHttpTransport;
import pl.piotrjanczukowicz.http.transport.HttpTransport;

public final class HttpTransportMocks {

    private HttpTransportMocks() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static HttpTransport mock() {
        return mock(Answers.RETURNS_DEFAULTS);
    }

    public static HttpTransport mock(final Answer<?> answer) {
        return Mockito.mock(ConcurrencyLimitingHttpTransport.class, answer);
    }
}
