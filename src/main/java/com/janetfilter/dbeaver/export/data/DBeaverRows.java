package com.janetfilter.dbeaver.export.data;

import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.janetfilter.dbeaver.export.connection.DBeaverConnections.ensureConnected;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.arr;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.c;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.call;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.createMonitorProxy;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.primitiveDefault;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.stringMethod;
import static com.janetfilter.dbeaver.export.data.DataExportJson.jsonValue;
import static com.janetfilter.dbeaver.export.data.DataExportJson.q;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.DEFAULT_ROWS_LIMIT;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.MAX_ROWS_LIMIT;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.boundedIntParam;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.nonNegativeLongParam;

public final class DBeaverRows {
    private static final String ROWS_TIMEOUT_PROPERTY =
            "janf.dataExport.rowsTimeoutSeconds";
    private static final int DEFAULT_ROWS_TIMEOUT_SECONDS = 30;

    private DBeaverRows() { }

    public static String read(Map<String, String> q) throws Exception {
        String nodeId = q.get("node");
        if (nodeId == null || nodeId.isEmpty()) {
            throw new IllegalArgumentException("need ?node=<table-node-id>");
        }
        int limit = boundedIntParam(
                q, "limit", DEFAULT_ROWS_LIMIT, 1, MAX_ROWS_LIMIT);
        long offset = nonNegativeLongParam(q, "offset", 0);

        SchemaPath path = SchemaPath.decode(nodeId);
        SchemaPath.Segment targetSegment =
                path.segments.get(path.segments.size() - 1);
        if (!"table".equals(targetSegment.kind)
                && !"view".equals(targetSegment.kind)) {
            throw new IllegalArgumentException(
                    "node must identify a table, view, or collection");
        }

        Object container = DBeaverWorkspace.requireDatasource(
                DBeaverWorkspace.requireProject(path.projectId), path.datasourceId);
        ensureConnected(container);
        Object dataSource = call(container, "getDataSource");
        if (dataSource == null) {
            throw new HttpException(500, "connected data source is unavailable");
        }

        int timeoutSeconds = Integer.getInteger(
                ROWS_TIMEOUT_PROPERTY, DEFAULT_ROWS_TIMEOUT_SECONDS);
        if (timeoutSeconds < 1) timeoutSeconds = DEFAULT_ROWS_TIMEOUT_SECONDS;
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Object monitor = createMonitorProxy(container, deadlineNanos);
        if (monitor == null) {
            throw new HttpException(500, "no progress monitor");
        }

        DBeaverSchema.SchemaNode target =
                DBeaverSchema.resolve(path, dataSource, monitor);
        Class<?> dataContainerClass =
                c("org.jkiss.dbeaver.model.struct.DBSDataContainer");
        if (!dataContainerClass.isInstance(target.value)) {
            throw new IllegalArgumentException(
                    "node does not support data reading");
        }

        DataPageReader reader = new DataPageReader(
                dataSource, target, monitor, deadlineNanos);
        return reader.read(offset, limit).toJson();
    }

    /**
     * Provider-neutral row/document reader built on DBeaver's own
     * DBSDataContainer seam.
     */
    private static final class DataPageReader {
        private final Object dataSource;
        private final DBeaverSchema.SchemaNode target;
        private final Object monitor;
        private final long deadlineNanos;

        private DataPageReader(
                Object dataSource, DBeaverSchema.SchemaNode target,
                Object monitor, long deadlineNanos) {
            this.dataSource = dataSource;
            this.target = target;
            this.monitor = monitor;
            this.deadlineNanos = deadlineNanos;
        }

        private DataPage read(long offset, int limit) throws Exception {
            Object instance = findOwningInstance(target.value);
            if (instance == null) {
                instance = dataSource;
                try {
                    Object defaultInstance =
                            call(dataSource, "getDefaultInstance");
                    if (defaultInstance != null) instance = defaultInstance;
                } catch (NoSuchMethodException ignored) { }
            }

            Object context;
            try {
                context = call(
                        instance, "getDefaultContext", arr(monitor, false));
            } catch (NoSuchMethodException e) {
                throw new HttpException(
                        500, "data source has no default execution context");
            }
            if (context == null) {
                throw new HttpException(
                        500, "data source returned no execution context");
            }

            Object purpose = call(
                    c("org.jkiss.dbeaver.model.exec.DBCExecutionPurpose"),
                    "valueOf", arr("USER"));
            Object session = call(
                    context, "openSession",
                    arr(monitor, purpose, "HTTP /rows"));
            if (session == null) {
                throw new HttpException(500, "could not open data session");
            }

            boolean document = c(
                    "org.jkiss.dbeaver.model.struct.DBSDocumentContainer")
                    .isInstance(target.value);
            DataReceiverCapture capture = new DataReceiverCapture(
                    monitor, document, limit);
            Object receiver = capture.createProxy();
            Object source = createExecutionSourceProxy(target.value);

            try {
                long requestedRows = (long) limit + 1L;
                call(target.value, "readData", arr(
                        source,
                        session,
                        receiver,
                        null,
                        offset,
                        requestedRows,
                        0L,
                        Math.min(limit + 1, MAX_ROWS_LIMIT)));
                if (System.nanoTime() >= deadlineNanos) {
                    throw new HttpException(504, "row read timed out");
                }
                return capture.toPage(
                        document ? "collection" : target.kind,
                        offset, limit);
            } catch (Exception failure) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new HttpException(504, "row read timed out");
                }
                throw failure;
            } finally {
                try { call(receiver, "close"); } catch (Exception ignored) { }
                try { call(session, "close"); } catch (Exception ignored) { }
            }
        }

        private Object findOwningInstance(Object value) throws Exception {
            Class<?> instanceClass =
                    c("org.jkiss.dbeaver.model.struct.DBSInstance");
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            Object current = value;
            for (int depth = 0;
                 current != null && depth < 16
                         && seen.put(current, Boolean.TRUE) == null;
                 depth++) {
                if (instanceClass.isInstance(current)) return current;
                try {
                    current = call(current, "getParentObject");
                } catch (NoSuchMethodException e) {
                    current = null;
                }
            }
            return null;
        }
    }

    private static Object createExecutionSourceProxy(Object dataContainer)
            throws Exception {
        Class<?> sourceClass =
                c("org.jkiss.dbeaver.model.exec.DBCExecutionSource");
        return Proxy.newProxyInstance(
                sourceClass.getClassLoader(),
                new Class[]{sourceClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return "HttpRowsExecutionSource";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == (args == null ? null : args[0]);
                            default: return null;
                        }
                    }
                    if ("getDataContainer".equals(method.getName())) {
                        return dataContainer;
                    }
                    return null;
                });
    }

    private static final class DataReceiverCapture {
        private final Object monitor;
        private final boolean document;
        private final int captureLimit;
        private final ArrayList<DataField> fields = new ArrayList<>();
        private final ArrayList<List<String>> rows = new ArrayList<>();

        private DataReceiverCapture(
                Object monitor, boolean document, int requestedLimit) {
            this.monitor = monitor;
            this.document = document;
            this.captureLimit = requestedLimit + 1;
        }

        private Object createProxy() throws Exception {
            Class<?> receiverClass =
                    c("org.jkiss.dbeaver.model.data.DBDDataReceiver");
            return Proxy.newProxyInstance(
                    receiverClass.getClassLoader(),
                    new Class[]{receiverClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "toString": return "HttpRowsDataReceiver";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals":
                                    return proxy == (args == null ? null : args[0]);
                                default: return null;
                            }
                        }
                        switch (method.getName()) {
                            case "fetchStart":
                                readFields(args[1]);
                                return null;
                            case "fetchRow":
                                if (rows.size() < captureLimit) {
                                    readRow(args[1]);
                                }
                                return null;
                            case "fetchEnd":
                            case "close":
                                return null;
                            case "getStatistics":
                                return null;
                            default:
                                return primitiveDefault(method.getReturnType());
                        }
                    });
        }

        @SuppressWarnings("unchecked")
        private void readFields(Object resultSet) throws Exception {
            fields.clear();
            Object metadata = call(resultSet, "getMeta");
            if (metadata == null) return;
            Object attributesValue = call(metadata, "getAttributes");
            if (!(attributesValue instanceof List)) return;

            LinkedHashMap<String, Integer> keyCounts = new LinkedHashMap<>();
            for (Object attribute : (List<Object>) attributesValue) {
                String name = stringMethod(attribute, "getName", "field");
                String label = stringMethod(attribute, "getLabel", name);
                String type = stringMethod(attribute, "getTypeName", "?");
                String baseKey = label == null || label.isEmpty() ? name : label;
                int count = keyCounts.containsKey(baseKey)
                        ? keyCounts.get(baseKey) + 1 : 1;
                keyCounts.put(baseKey, count);
                String jsonKey = count == 1 ? baseKey : baseKey + "_" + count;
                fields.add(new DataField(name, label, type, jsonKey));
            }
        }

        private void readRow(Object resultSet) throws Exception {
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                Object value = call(resultSet, "getAttributeValue", arr(i));
                values.add(jsonValue(value, monitor, document));
            }
            rows.add(values);
        }

        private DataPage toPage(String sourceKind, long offset, int limit) {
            boolean hasMore = rows.size() > limit;
            int returned = Math.min(rows.size(), limit);
            ArrayList<List<String>> visibleRows = new ArrayList<>();
            for (int i = 0; i < returned; i++) {
                visibleRows.add(rows.get(i));
            }
            return new DataPage(
                    sourceKind, document, fields, visibleRows,
                    offset, limit, hasMore);
        }
    }

    private static final class DataPage {
        private final String sourceKind;
        private final boolean dynamicFields;
        private final List<DataField> fields;
        private final List<List<String>> rows;
        private final long offset;
        private final int limit;
        private final boolean hasMore;

        private DataPage(
                String sourceKind, boolean dynamicFields,
                List<DataField> fields, List<List<String>> rows,
                long offset, int limit, boolean hasMore) {
            this.sourceKind = sourceKind;
            this.dynamicFields = dynamicFields;
            this.fields = new ArrayList<>(fields);
            this.rows = rows;
            this.offset = offset;
            this.limit = limit;
            this.hasMore = hasMore;
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\"sourceKind\":")
                    .append(q(sourceKind))
                    .append(",\"dynamicFields\":").append(dynamicFields)
                    .append(",\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) json.append(",");
                DataField field = fields.get(i);
                json.append("{\"name\":").append(q(field.name))
                    .append(",\"label\":").append(q(field.label))
                    .append(",\"type\":").append(q(field.type))
                    .append("}");
            }
            json.append("],\"records\":[");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                if (rowIndex > 0) json.append(",");
                List<String> row = rows.get(rowIndex);
                if (dynamicFields && row.size() == 1
                        && isJsonObject(row.get(0))) {
                    json.append(row.get(0));
                    continue;
                }
                json.append("{");
                for (int fieldIndex = 0;
                     fieldIndex < fields.size() && fieldIndex < row.size();
                     fieldIndex++) {
                    if (fieldIndex > 0) json.append(",");
                    json.append(q(fields.get(fieldIndex).jsonKey))
                        .append(":").append(row.get(fieldIndex));
                }
                json.append("}");
            }
            int returned = rows.size();
            json.append("],\"page\":{\"offset\":").append(offset)
                .append(",\"limit\":").append(limit)
                .append(",\"returned\":").append(returned)
                .append(",\"hasMore\":").append(hasMore)
                .append(",\"nextOffset\":")
                .append(hasMore ? String.valueOf(offset + returned) : "null")
                .append("}}");
            return json.toString();
        }
    }

    private static boolean isJsonObject(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }
}
