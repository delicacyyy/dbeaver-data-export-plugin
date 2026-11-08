package com.janetfilter.dbeaver.export.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict JSON handling shared by the new administrative request endpoints. */
public final class JsonRequests {
    private static final int MAX_NESTING = 100;
    private static final int MAX_NUMBER_LENGTH = 128;
    private static final int MAX_NUMBER_EXPONENT = 10000;
    private static final Gson WRITER = new GsonBuilder().serializeNulls().create();

    private JsonRequests() { }

    public static Map<String, Object> parseObject(String body) {
        if (body == null) {
            throw invalidJson();
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(body));
            reader.setStrictness(Strictness.STRICT);
            Object parsed = readValue(reader, 0);
            if (!(parsed instanceof Map) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalidJson();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) parsed;
            return object;
        } catch (IOException | RuntimeException e) {
            throw invalidJson();
        }
    }

    public static String toJson(Object value) {
        return WRITER.toJson(value);
    }

    public static String string(Map<String, Object> object, String key, boolean required) {
        if (!object.containsKey(key)) {
            if (required) {
                throw invalidField();
            }
            return null;
        }
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw invalidField();
        }
        String string = (String) value;
        if (required && string.trim().isEmpty()) {
            throw invalidField();
        }
        return string;
    }

    static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        if (!object.containsKey(key)) {
            return fallback;
        }
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw invalidField();
        }
        return (Boolean) value;
    }

    public static Map<String, Object> object(
            Map<String, Object> object, String key, boolean required) {
        if (!object.containsKey(key)) {
            if (required) {
                throw invalidField();
            }
            return null;
        }
        Object value = object.get(key);
        if (!(value instanceof Map)) {
            throw invalidField();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public static void requireKeys(Map<String, Object> object, String... allowed) {
        for (String key : object.keySet()) {
            boolean found = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw invalidField();
            }
        }
    }

    private static Object readValue(JsonReader reader, int depth) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT: {
                if (depth >= MAX_NESTING) {
                    throw invalidJson();
                }
                Map<String, Object> object = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.containsKey(key)) {
                        throw invalidJson();
                    }
                    object.put(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                if (depth >= MAX_NESTING) {
                    throw invalidJson();
                }
                List<Object> values = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    values.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                return values;
            }
            case STRING:
                return reader.nextString();
            case NUMBER:
                return number(reader.nextString());
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return null;
            default:
                throw invalidJson();
        }
    }

    private static BigDecimal number(String text) {
        if (text.length() > MAX_NUMBER_LENGTH) {
            throw invalidJson();
        }
        int exponentIndex = Math.max(text.indexOf('e'), text.indexOf('E'));
        if (exponentIndex >= 0) {
            int exponent;
            try {
                exponent = Integer.parseInt(text.substring(exponentIndex + 1));
            } catch (NumberFormatException e) {
                throw invalidJson();
            }
            if (exponent < -MAX_NUMBER_EXPONENT || exponent > MAX_NUMBER_EXPONENT) {
                throw invalidJson();
            }
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw invalidJson();
        }
    }

    private static IllegalArgumentException invalidJson() {
        return new IllegalArgumentException("invalid JSON object");
    }

    private static IllegalArgumentException invalidField() {
        return new IllegalArgumentException("invalid request field");
    }
}
