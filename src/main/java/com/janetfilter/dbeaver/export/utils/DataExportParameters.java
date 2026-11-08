package com.janetfilter.dbeaver.export.utils;

import java.util.Map;

/** Shared numeric request bounds for row reads and SQL execution. */
public final class DataExportParameters {
    public static final int DEFAULT_ROWS_LIMIT = 100;
    public static final int MAX_ROWS_LIMIT = 1000;

    private DataExportParameters() { }

    public static int boundedIntParam(Map<String, String> query, String key,
            int defaultValue, int minimum, int maximum) {
        String raw = query.get(key);
        if (raw == null || raw.isEmpty()) return defaultValue;
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    public static long nonNegativeLongParam(Map<String, String> query, String key,
            long defaultValue) {
        String raw = query.get(key);
        if (raw == null || raw.isEmpty()) return defaultValue;
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
        return value;
    }
}
