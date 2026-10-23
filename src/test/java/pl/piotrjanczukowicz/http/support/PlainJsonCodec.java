package pl.piotrjanczukowicz.http.support;

import pl.piotrjanczukowicz.json.JsonCodec;
import pl.piotrjanczukowicz.json.JsonDecodeException;
import pl.piotrjanczukowicz.json.JsonEncodeException;
import pl.piotrjanczukowicz.json.JsonType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A neutral {@link JsonCodec} for tests of the generic HTTP layer: plain Jackson, unknown properties ignored, and no
 * service-specific conventions such as snake_case names.
 */
public final class PlainJsonCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private PlainJsonCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Codec with plain Jackson defaults.
     *
     * @return the codec
     */
    public static JsonCodec create() {
        return new Codec();
    }

    private static final class Codec implements JsonCodec {

        @Override
        public <T> T decode(final byte[] json, final Class<T> type) {
            try {
                return requireValue(MAPPER.readValue(json, type), type.getName());
            } catch (final JacksonException e) {
                throw new JsonDecodeException("cannot decode " + type.getName(), e);
            }
        }

        @Override
        public <T> T decode(final byte[] json, final JsonType<T> type) {
            try {
                final String name = type.javaType().getTypeName();
                return requireValue(MAPPER.readValue(json, MAPPER.constructType(type.javaType())), name);
            } catch (final JacksonException e) {
                throw new JsonDecodeException("cannot decode " + type.javaType().getTypeName(), e);
            }
        }

        // The JSON literal null has no value to return, as the codec contract says.
        private static <T> T requireValue(final T value, final String name) {
            if (value == null) {
                throw new IllegalStateException("JSON null has no value to decode as " + name);
            }
            return value;
        }

        @Override
        public byte[] encode(final Object value) {
            try {
                return MAPPER.writeValueAsBytes(value);
            } catch (final JacksonException e) {
                throw new JsonEncodeException(
                        "cannot encode " + value.getClass().getName(), e);
            }
        }
    }
}
