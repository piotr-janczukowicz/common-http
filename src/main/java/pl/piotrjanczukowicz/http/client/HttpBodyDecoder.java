package pl.piotrjanczukowicz.http.client;

import pl.piotrjanczukowicz.json.JsonType;

/**
 * Decodes the body of one response, handed to the functions of {@link AbstractHttpCaller#fetchByStatus(HttpCall,
 * java.util.function.Function, java.util.Map)}. It is bound to that response and to the JSON codec of the caller, so
 * the code that picks a result never sees either.
 */
public interface HttpBodyDecoder {

    /**
     * Decodes the body as {@code type}.
     *
     * @param type target class
     * @param <T> result type
     * @return decoded value
     * @throws HttpEmptyResponseException if the response has no body, such as HTTP 202 or 204; the message names the
     *     status, method, and target
     */
    <T> T as(Class<T> type);

    /**
     * Decodes the body as {@code type}.
     *
     * @param type target type, including type arguments
     * @param <T> result type
     * @return decoded value
     * @throws HttpEmptyResponseException if the response has no body, such as HTTP 202 or 204; the message names the
     *     status, method, and target
     */
    <T> T as(JsonType<T> type);
}
