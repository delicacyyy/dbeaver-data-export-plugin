package com.janetfilter.dbeaver.export.runtime.evaluation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON results and phase-specific failures from a runtime evaluation. */
public final class RuntimeEvaluationResult {
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_RESULT_DEPTH = 100;
    private static final int MAX_DIAGNOSTICS = 20;
    private static final int SNIPPET_BODY_FIRST_LINE = 5;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public final int status;
    public final String json;

    RuntimeEvaluationResult(int status, String json) {
        this.status = status;
        this.json = json;
    }

    static RuntimeEvaluationResult success(Object value) {
        return new RuntimeEvaluationResult(200, successJson(value));
    }

    static List<Object> diagnostics(String[][] compilerDiagnostics) {
        List<Object> result = new ArrayList<>();
        if (compilerDiagnostics == null) {
            return result;
        }
        for (int i = 0; i < compilerDiagnostics.length && i < MAX_DIAGNOSTICS; i++) {
            String[] diagnostic = compilerDiagnostics[i];
            if (diagnostic == null || diagnostic.length < 4) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", snippetLine(parseLong(diagnostic[0])));
            item.put("column", Math.max(1L, parseLong(diagnostic[1])));
            item.put("code", diagnostic[2]);
            item.put("message", diagnostic[3]);
            result.add(item);
        }
        return result;
    }

    private static long snippetLine(long generatedLine) {
        return generatedLine <= 0 ? 1 : Math.max(1, generatedLine - (SNIPPET_BODY_FIRST_LINE - 1));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String boundedString(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(maxChars, value.length()));
        for (int i = 0; i < value.length() && result.length() < maxChars; i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    if (result.length() + 2 > maxChars) {
                        break;
                    }
                    result.append(current).append(value.charAt(++i));
                } else {
                    result.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(current)) {
                result.append('\uFFFD');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    static RuntimeEvaluationResult error(
            int status, String message, String phase, List<Object> diagnostics, String exceptionType) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", message);
        response.put("phase", phase);
        if (diagnostics != null && !diagnostics.isEmpty()) {
            response.put("diagnostics", diagnostics);
        }
        if (exceptionType != null) {
            response.put("exceptionType", exceptionType);
        }
        return new RuntimeEvaluationResult(status, json(response));
    }

    static String boundedTypeName(Throwable failure) {
        String name = failure == null ? "java.lang.Exception" : failure.getClass().getName();
        return boundedString(name, 200);
    }

    private static String successJson(Object value) {
        BoundedJsonOutput output = new BoundedJsonOutput();
        output.ascii("{\"result\":");
        output.value(value, 1, new IdentityHashMap<>());
        output.ascii("}");
        return output.toString();
    }

    private static String json(Object value) {
        BoundedJsonOutput output = new BoundedJsonOutput();
        output.value(value, 0, new IdentityHashMap<>());
        return output.toString();
    }

    private static final class ResultContractException extends RuntimeException {
        private ResultContractException(String message) {
            super(message);
        }
    }

    private static final class BoundedJsonOutput {
        private final StringBuilder output = new StringBuilder();
        private int utf8Bytes;

        private void value(Object value, int depth, IdentityHashMap<Object, Boolean> path) {
            if (value == null) {
                ascii("null");
            } else if (value instanceof String) {
                quoted((String) value);
            } else if (value instanceof Boolean) {
                ascii(((Boolean) value) ? "true" : "false");
            } else if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                ascii(value.toString());
            } else if (value.getClass() == Float.class) {
                float number = (Float) value;
                if (!Float.isFinite(number)) {
                    throw new ResultContractException("result contains a non-finite number");
                }
                ascii(Float.toString(number));
            } else if (value.getClass() == Double.class) {
                double number = (Double) value;
                if (!Double.isFinite(number)) {
                    throw new ResultContractException("result contains a non-finite number");
                }
                ascii(Double.toString(number));
            } else if (value.getClass() == BigInteger.class) {
                BigInteger number = (BigInteger) value;
                if (number.bitLength() > (long) MAX_JSON_BYTES * 4L) {
                    throw new ResultContractException("result number exceeds the response limit");
                }
                ascii(number.toString());
            } else if (value.getClass() == BigDecimal.class) {
                BigDecimal number = (BigDecimal) value;
                if (number.precision() > MAX_JSON_BYTES) {
                    throw new ResultContractException("result number exceeds the response limit");
                }
                ascii(number.toString());
            } else if (value instanceof List<?>) {
                container(value, depth, path);
                try {
                    list((List<?>) value, depth + 1, path);
                } finally {
                    path.remove(value);
                }
            } else if (value instanceof Map<?, ?>) {
                container(value, depth, path);
                try {
                    map((Map<?, ?>) value, depth + 1, path);
                } finally {
                    path.remove(value);
                }
            } else {
                throw new ResultContractException("result contains an unsupported value");
            }
        }

        private void container(Object value, int depth, IdentityHashMap<Object, Boolean> path) {
            if (depth >= MAX_RESULT_DEPTH) {
                throw new ResultContractException("result is too deeply nested");
            }
            if (path.put(value, Boolean.TRUE) != null) {
                throw new ResultContractException("result contains a cycle");
            }
        }

        private void list(List<?> values, int depth, IdentityHashMap<Object, Boolean> path) {
            ascii("[");
            boolean first = true;
            for (Object item : values) {
                if (!first) {
                    ascii(",");
                }
                first = false;
                value(item, depth, path);
            }
            ascii("]");
        }

        private void map(Map<?, ?> values, int depth, IdentityHashMap<Object, Boolean> path) {
            ascii("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new ResultContractException("result object keys must be strings");
                }
                if (!first) {
                    ascii(",");
                }
                first = false;
                quoted((String) entry.getKey());
                ascii(":");
                value(entry.getValue(), depth, path);
            }
            ascii("}");
        }

        private void quoted(String value) {
            ascii("\"");
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                switch (current) {
                    case '"': ascii("\\\""); break;
                    case '\\': ascii("\\\\"); break;
                    case '\b': ascii("\\b"); break;
                    case '\f': ascii("\\f"); break;
                    case '\n': ascii("\\n"); break;
                    case '\r': ascii("\\r"); break;
                    case '\t': ascii("\\t"); break;
                    default:
                        if (current < 0x20) {
                            ascii("\\u00");
                            asciiChar(HEX[(current >>> 4) & 0xf]);
                            asciiChar(HEX[current & 0xf]);
                        } else if (Character.isHighSurrogate(current)) {
                            if (i + 1 >= value.length()
                                    || !Character.isLowSurrogate(value.charAt(i + 1))) {
                                throw new ResultContractException("result contains invalid UTF-16");
                            }
                            ensureBytes(4);
                            output.append(current).append(value.charAt(++i));
                            utf8Bytes += 4;
                        } else if (Character.isLowSurrogate(current)) {
                            throw new ResultContractException("result contains invalid UTF-16");
                        } else {
                            appendRaw(current);
                        }
                }
            }
            ascii("\"");
        }

        private void ascii(String value) {
            for (int i = 0; i < value.length(); i++) {
                asciiChar(value.charAt(i));
            }
        }

        private void asciiChar(char value) {
            if (value > 0x7f) {
                throw new ResultContractException("invalid JSON token");
            }
            ensureBytes(1);
            output.append(value);
            utf8Bytes++;
        }

        private void appendRaw(char value) {
            int bytes = value <= 0x7f ? 1 : value <= 0x7ff ? 2 : 3;
            ensureBytes(bytes);
            output.append(value);
            utf8Bytes += bytes;
        }

        private void ensureBytes(int added) {
            if ((long) utf8Bytes + added > MAX_JSON_BYTES) {
                throw new ResultContractException("result exceeds the response limit");
            }
        }

        @Override
        public String toString() {
            return output.toString();
        }
    }
}
