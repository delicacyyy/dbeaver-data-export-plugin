package com.janetfilter.dbeaver.export.data;

/** Immutable column metadata shared by row and SQL results. */
final class DataField {
    final String name;
    final String label;
    final String type;
    final String jsonKey;

    DataField(String name, String label, String type, String jsonKey) {
        this.name = name;
        this.label = label;
        this.type = type;
        this.jsonKey = jsonKey;
    }
}
