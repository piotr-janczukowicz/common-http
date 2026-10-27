package io.github.piotr_janczukowicz.http.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@UnitTest
class HttpTransportMocksTest {

    @Test
    void mock_shouldReturnTransport_whenCalled() {
        final HttpTransport transport = HttpTransportMocks.mock();

        assertThat(transport).isNotNull();
    }

    @Test
    void mock_shouldReturnDistinctInstances_whenCalledTwice() {
        final HttpTransport first = HttpTransportMocks.mock();
        final HttpTransport second = HttpTransportMocks.mock();

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void mock_shouldAnswerCalls_whenAnswerProvided() {
        final AtomicBoolean answered = new AtomicBoolean();
        final HttpTransport transport = HttpTransportMocks.mock(invocation -> {
            answered.set(true);
            return null;
        });

        transport.close();

        assertThat(answered.get()).isTrue();
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<HttpTransportMocks> constructor = HttpTransportMocks.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
