package io.github.piotr_janczukowicz.test.http;

import io.github.piotr_janczukowicz.http.transport.ConcurrencyLimitingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

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
