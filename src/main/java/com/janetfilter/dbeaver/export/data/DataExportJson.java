package com.janetfilter.dbeaver.export.data;

import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.arr;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.c;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.call;

/** JSON representation of exported provider values and shared string escaping. */
public final class DataExportJson {
    private DataExportJson() { }

    static String jsonValue(Object value, Object monitor, boolean extendedJson)
            throws Exception {
        return jsonValue(value, monitor, extendedJson, 0,
                new java.util.IdentityHashMap<Object,Boolean>());
    }

    private static String jsonValue(Object value, Object monitor, boolean extendedJson,
            int depth, java.util.IdentityHashMap<Object,Boolean> seen) throws Exception {
        if (value == null) return "null";
        if (depth > 32) return q("[maximum depth reached]");
        String className = value.getClass().getName();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) {
            return value.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? value.toString() : q(value);
        }
        if (value instanceof CharSequence || value instanceof Character
                || value instanceof java.util.UUID || value instanceof Enum) {
            return q(value);
        }
        if ("java.sql.Timestamp".equals(className)) {
            String instant = String.valueOf(call(value, "toInstant"));
            return extendedJson ? dateJson(instant) : q(instant);
        }
        if ("java.sql.Date".equals(className) || "java.sql.Time".equals(className)) {
            return q(value);
        }
        if (value instanceof java.util.Date) {
            String instant = ((java.util.Date) value).toInstant().toString();
            return extendedJson ? dateJson(instant) : q(instant);
        }
        if (value.getClass().getName().startsWith("java.time.")) return q(value);
        if (value instanceof byte[]) return binaryJson((byte[]) value, (byte) 0);

        if ("org.bson.types.ObjectId".equals(className)) {
            return "{\"$oid\":" + q(call(value, "toHexString")) + "}";
        }
        if ("org.bson.types.Decimal128".equals(className)) {
            return "{\"$numberDecimal\":" + q(value) + "}";
        }
        if ("org.bson.types.Binary".equals(className)) {
            byte[] data = (byte[]) call(value, "getData");
            Object subtype = call(value, "getType");
            byte type = subtype instanceof Number ? ((Number) subtype).byteValue() : (byte) 0;
            return binaryJson(data, type);
        }

        Class<?> documentClass = c("org.jkiss.dbeaver.model.data.DBDDocument");
        if (documentClass.isInstance(value)) {
            java.io.StringWriter writer = new java.io.StringWriter();
            call(value, "serializeDocument", arr(monitor, writer));
            String serialized = writer.toString().trim();
            return looksLikeJson(serialized) ? serialized : q(serialized);
        }

        Class<?> dbdValueClass = c("org.jkiss.dbeaver.model.data.DBDValue");
        if (dbdValueClass.isInstance(value)) {
            Object raw = call(value, "getRawValue");
            if (raw == null) return "null";
            if (raw != value) return jsonValue(raw, monitor, extendedJson, depth + 1, seen);
        }

        boolean trackIdentity = value instanceof java.util.Map
                || value instanceof java.util.Collection
                || value instanceof Iterable
                || value.getClass().isArray();
        if (trackIdentity && seen.put(value, Boolean.TRUE) != null) {
            return q("[circular reference]");
        }
        try {
            if (value instanceof java.util.Map) {
                StringBuilder json = new StringBuilder("{");
                boolean first = true;
                for (Object entryValue : ((java.util.Map<?,?>) value).entrySet()) {
                    java.util.Map.Entry<?,?> entry = (java.util.Map.Entry<?,?>) entryValue;
                    if (!first) json.append(",");
                    first = false;
                    json.append(q(String.valueOf(entry.getKey())))
                            .append(":")
                            .append(jsonValue(entry.getValue(), monitor,
                                    extendedJson, depth + 1, seen));
                }
                return json.append("}").toString();
            }
            if (value instanceof Iterable) {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) json.append(",");
                    first = false;
                    json.append(jsonValue(item, monitor, extendedJson, depth + 1, seen));
                }
                return json.append("]").toString();
            }
            if (value.getClass().isArray()) {
                StringBuilder json = new StringBuilder("[");
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    if (i > 0) json.append(",");
                    json.append(jsonValue(java.lang.reflect.Array.get(value, i),
                            monitor, extendedJson, depth + 1, seen));
                }
                return json.append("]").toString();
            }
        } finally {
            if (trackIdentity) seen.remove(value);
        }
        return q(value);
    }

    private static String dateJson(String instant) {
        return "{\"$date\":" + q(instant) + "}";
    }

    private static String binaryJson(byte[] value, byte subtype) {
        String base64 = java.util.Base64.getEncoder().encodeToString(value);
        String hexSubtype = String.format(java.util.Locale.ROOT, "%02x", subtype & 0xff);
        return "{\"$binary\":{\"base64\":" + q(base64)
                + ",\"subType\":" + q(hexSubtype) + "}}";
    }

    private static boolean looksLikeJson(String value) {
        if (value == null || value.isEmpty()) return false;
        char first = value.charAt(0);
        return first == '{' || first == '[' || first == '"'
                || first == '-' || Character.isDigit(first)
                || "true".equals(value) || "false".equals(value)
                || "null".equals(value);
    }

    public static String q(Object value) {
        if (value == null) return "null";
        String text = value.toString();
        StringBuilder escaped = new StringBuilder(text.length() + 2).append("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format(
                                java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
            }
        }
        return escaped.append("\"").toString();
    }

    static String err(String message) {
        return "{\"error\":" + q(message) + "}";
    }
}
