package io.github.piotr_janczukowicz.http.client;

import io.github.piotr_janczukowicz.json.JsonCodec;

/**
 * The caller for a generic {@link HttpClient}: the operations of {@link AbstractHttpCaller} and nothing else. A caller
 * with operations of its own extends {@link AbstractHttpCaller} instead.
 *
 * <p>This generic caller accepts any {@link JsonCodec}. A caller that fixes its codec, such as a service-specific one,
 * supplies its own default.
 */
public final class HttpClientCaller extends AbstractHttpCaller {

    /**
     * Caller over {@code executor}.
     *
     * @param executor sends the calls
     * @param jsonCodec encodes request bodies and decodes responses
     * @throws NullPointerException if an argument is {@code null}
     */
    public HttpClientCaller(final HttpClientExecutor executor, final JsonCodec jsonCodec) {
        super(executor, jsonCodec);
    }
}
