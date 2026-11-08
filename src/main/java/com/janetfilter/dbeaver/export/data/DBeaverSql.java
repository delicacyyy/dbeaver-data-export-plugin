package com.janetfilter.dbeaver.export.data;

import static com.janetfilter.dbeaver.export.connection.DBeaverConnections.ensureConnected;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.arr;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.c;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.call;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.closeAfter;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.construct;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.createMonitorProxy;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.isMongoDataSource;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.primitiveDefault;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.providerClass;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.stringMethod;
import static com.janetfilter.dbeaver.export.data.DataExportJson.jsonValue;
import static com.janetfilter.dbeaver.export.data.DataExportJson.q;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.DEFAULT_ROWS_LIMIT;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.MAX_ROWS_LIMIT;
import static com.janetfilter.dbeaver.export.utils.DataExportParameters.boundedIntParam;

import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.utils.JsonRequests;
import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DBeaverSql {
    private static final String EXECUTE_TIMEOUT_PROPERTY =
            "janf.dataExport.executeTimeoutSeconds";
    private static final int DEFAULT_EXECUTE_TIMEOUT_SECONDS = 30;

    private DBeaverSql() {
    }

    public static String execute(Map<String, String> q, String body) throws Exception {
        String projectId = q.get("projectId");
        String datasourceId = q.get("dsId");
        DBeaverWorkspace.requireId(projectId, "projectId");
        DBeaverWorkspace.requireId(datasourceId, "dsId");
        int limit = boundedIntParam(
                q, "limit", DEFAULT_ROWS_LIMIT, 1, MAX_ROWS_LIMIT);
        int configuredTimeout = Integer.getInteger(
                EXECUTE_TIMEOUT_PROPERTY, DEFAULT_EXECUTE_TIMEOUT_SECONDS);
        if (configuredTimeout < 1) {
            configuredTimeout = DEFAULT_EXECUTE_TIMEOUT_SECONDS;
        }
        int timeoutSeconds = boundedIntParam(
                q, "timeout", configuredTimeout, 1, configuredTimeout);

        String sql = JsonRequests.string(JsonRequests.parseObject(body), "sql", false);
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "request body must contain a non-empty 'sql' string");
        }

        Object container = DBeaverWorkspace.requireDatasource(
                DBeaverWorkspace.requireProject(projectId), datasourceId);
        ensureConnected(container);
        Object dataSource = call(container, "getDataSource");
        if (dataSource == null) {
            throw new HttpException(503, "connected data source is unavailable");
        }
        String databaseName = q.get("database");
        if (databaseName != null) {
            databaseName = databaseName.trim();
            if (databaseName.isEmpty()) databaseName = null;
        }
        boolean mongoDataSource = isMongoDataSource(dataSource);
        if (databaseName != null && !mongoDataSource) {
            throw new IllegalArgumentException(
                    "database is supported only for MongoDB data sources");
        }
        if (databaseName != null
                && !databaseName.matches("[A-Za-z][A-Za-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException(
                    "database must start with a letter and contain only "
                    + "letters, digits, '_' or '-' (maximum 63 characters)");
        }

        long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Object monitor = createMonitorProxy(container, deadlineNanos);
        if (monitor == null) {
            throw new HttpException(503, "progress monitor is unavailable");
        }

        SqlExecutor executor = new SqlExecutor(
                dataSource, monitor, deadlineNanos,
                timeoutSeconds, databaseName, mongoDataSource);
        return executor.execute(sql, limit).toJson();
    }

    private static final class SqlExecutor {
        private final Object dataSource;
        private final Object monitor;
        private final long deadlineNanos;
        private final int timeoutSeconds;
        private final String databaseName;
        private final boolean mongoDataSource;

        private SqlExecutor(
                Object dataSource, Object monitor,
                long deadlineNanos, int timeoutSeconds,
                String databaseName, boolean mongoDataSource) {
            this.dataSource = dataSource;
            this.monitor = monitor;
            this.deadlineNanos = deadlineNanos;
            this.timeoutSeconds = timeoutSeconds;
            this.databaseName = databaseName;
            this.mongoDataSource = mongoDataSource;
        }

        private SqlExecutionResult execute(String sql, int limit)
                throws Exception {
            Object context = null;
            Object session = null;
            Object statement = null;
            Object resultSet = null;
            Object mongoDatabaseCache = null;
            Object temporaryMongoDatabase = null;
            Exception failure = null;
            SqlExecutionResult result = null;

            try {
                Object instance = call(dataSource, "getDefaultInstance");
                if (instance == null) {
                    throw new HttpException(
                            503, "default database instance is unavailable");
                }

                if (mongoDataSource && databaseName != null) {
                    mongoDatabaseCache =
                            call(instance, "getDatabaseCache");
                    Object mongoDatabase =
                            call(instance, "getDatabase", arr(databaseName));
                    if (mongoDatabase == null) {
                        mongoDatabase = construct(
                                providerClass(
                                        instance,
                                        "com.dbeaver.db.mongodb.model."
                                        + "MGDatabase"),
                                instance, databaseName);
                        call(mongoDatabaseCache, "cacheObject",
                                arr(mongoDatabase));
                        temporaryMongoDatabase = mongoDatabase;
                    }
                    context = construct(
                            providerClass(
                                    instance,
                                    "com.dbeaver.db.mongodb.exec."
                                    + "MGExecutionContext"),
                            instance, "HTTP /execute", databaseName);
                    call(context, "connect", arr(monitor));
                } else {
                    Object defaultContext = call(
                            instance, "getDefaultContext",
                            arr(monitor, false));
                    if (defaultContext == null) {
                        throw new HttpException(
                                503,
                                "default execution context is unavailable");
                    }
                    context = call(
                            instance, "openIsolatedContext",
                            arr(monitor, "HTTP /execute", defaultContext));
                    if (context == defaultContext) {
                        context = null;
                        throw new HttpException(
                                503, "provider returned the shared "
                                + "execution context");
                    }
                }
                if (context == null) {
                    throw new HttpException(
                            503, "provider returned no isolated context");
                }
                call(context, "checkContextAlive", arr(monitor));

                Object purpose = call(
                        c("org.jkiss.dbeaver.model.exec.DBCExecutionPurpose"),
                        "valueOf", arr("USER"));
                session = call(
                        context, "openSession",
                        arr(monitor, purpose, "HTTP /execute"));
                if (session == null) {
                    throw new HttpException(
                            503, "could not open SQL execution session");
                }

                Object statementType = call(
                        c("org.jkiss.dbeaver.model.exec.DBCStatementType"),
                        "valueOf", arr("QUERY"));
                statement = call(
                        session, "prepareStatement",
                        arr(statementType, sql, false, false, false));
                if (statement == null) {
                    throw new HttpException(
                            500, "could not prepare SQL statement");
                }
                call(statement, "setStatementSource",
                        arr(createSqlExecutionSourceProxy(
                                mongoDataSource ? instance : null)));
                call(statement, "setStatementTimeout", arr(timeoutSeconds));
                call(statement, "setResultsFetchSize",
                        arr(Math.min(limit + 1, MAX_ROWS_LIMIT)));
                call(statement, "setLimit", arr(0L, (long) limit + 1L));

                boolean hasResultSet = Boolean.TRUE.equals(
                        call(statement, "executeStatement"));
                if (System.nanoTime() >= deadlineNanos) {
                    throw new HttpException(
                            504, "SQL execution timed out");
                }
                if (hasResultSet) {
                    resultSet = call(statement, "openResultSet");
                    if (resultSet == null) {
                        throw new HttpException(
                                500, "statement returned no result set");
                    }
                    result = readResultSet(resultSet, limit);
                } else {
                    Object count = call(statement, "getUpdateRowCount");
                    long updateCount = count instanceof Number
                            ? ((Number) count).longValue() : -1L;
                    result = SqlExecutionResult.update(updateCount);
                }
            } catch (Exception error) {
                failure = System.nanoTime() >= deadlineNanos
                        ? new HttpException(504, "SQL execution timed out")
                        : error;
            } finally {
                failure = closeAfter(resultSet, failure);
                failure = closeAfter(statement, failure);
                failure = closeAfter(session, failure);
                failure = closeAfter(context, failure);
                if (temporaryMongoDatabase != null
                        && mongoDatabaseCache != null) {
                    try {
                        call(mongoDatabaseCache, "removeObject",
                                arr(temporaryMongoDatabase, true));
                    } catch (Exception cleanupFailure) {
                        if (failure == null) {
                            failure = cleanupFailure;
                        } else {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
            }

            if (failure != null) throw failure;
            if (result == null) {
                throw new HttpException(
                        500, "SQL execution produced no result");
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private SqlExecutionResult readResultSet(Object resultSet, int limit)
                throws Exception {
            ArrayList<DataField> fields = new ArrayList<>();
            Object metadata = call(resultSet, "getMeta");
            Object attributesValue = metadata == null
                    ? null : call(metadata, "getAttributes");
            if (attributesValue instanceof List) {
                LinkedHashMap<String, Integer> keyCounts =
                        new LinkedHashMap<>();
                for (Object attribute : (List<Object>) attributesValue) {
                    String name = stringMethod(
                            attribute, "getName", "field");
                    String label = stringMethod(
                            attribute, "getLabel", name);
                    String type = stringMethod(
                            attribute, "getTypeName", "?");
                    String baseKey = label == null || label.isEmpty()
                            ? name : label;
                    int count = keyCounts.containsKey(baseKey)
                            ? keyCounts.get(baseKey) + 1 : 1;
                    keyCounts.put(baseKey, count);
                    String jsonKey = count == 1
                            ? baseKey : baseKey + "_" + count;
                    fields.add(new DataField(
                            name, label, type, jsonKey));
                }
            }

            ArrayList<List<String>> rows = new ArrayList<>();
            while (rows.size() <= limit
                    && Boolean.TRUE.equals(call(resultSet, "nextRow"))) {
                ArrayList<String> row = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    Object value = call(
                            resultSet, "getAttributeValue", arr(i));
                    row.add(jsonValue(value, monitor, false));
                }
                rows.add(row);
                if (System.nanoTime() >= deadlineNanos) {
                    throw new HttpException(504, "SQL execution timed out");
                }
            }

            boolean truncated = rows.size() > limit;
            if (truncated) rows.remove(rows.size() - 1);
            return SqlExecutionResult.rows(fields, rows, limit, truncated);
        }
    }

    private static Object createSqlExecutionSourceProxy(
            Object sourceDataSource) throws Exception {
        Class<?> sourceClass =
                c("org.jkiss.dbeaver.model.exec.DBCExecutionSource");
        Object dataContainer = sourceDataSource == null
                ? null : createDataContainerProxy(sourceDataSource);
        return Proxy.newProxyInstance(
                sourceClass.getClassLoader(),
                new Class[]{sourceClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return "HttpExecuteSource";
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

    private static Object createDataContainerProxy(Object dataSource)
            throws Exception {
        Class<?> dataContainerClass =
                c("org.jkiss.dbeaver.model.struct.DBSDataContainer");
        return Proxy.newProxyInstance(
                dataContainerClass.getClassLoader(),
                new Class[]{dataContainerClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return "HttpExecuteDataContainer";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == (args == null ? null : args[0]);
                            default: return null;
                        }
                    }
                    if ("getDataSource".equals(method.getName())) {
                        return dataSource;
                    }
                    return primitiveDefault(method.getReturnType());
                });
    }

    private static final class SqlExecutionResult {
        private final String kind;
        private final List<DataField> fields;
        private final List<List<String>> rows;
        private final int limit;
        private final boolean truncated;
        private final long updateCount;

        private SqlExecutionResult(
                String kind, List<DataField> fields,
                List<List<String>> rows, int limit,
                boolean truncated, long updateCount) {
            this.kind = kind;
            this.fields = fields;
            this.rows = rows;
            this.limit = limit;
            this.truncated = truncated;
            this.updateCount = updateCount;
        }

        private static SqlExecutionResult rows(
                List<DataField> fields,
                List<List<String>> rows,
                int limit, boolean truncated) {
            return new SqlExecutionResult(
                    "rows", fields, rows, limit, truncated, -1L);
        }

        private static SqlExecutionResult update(long updateCount) {
            return new SqlExecutionResult(
                    "update", Collections.emptyList(),
                    Collections.emptyList(), 0, false, updateCount);
        }

        private String toJson() {
            StringBuilder json = new StringBuilder(
                    "{\"context\":\"isolated\",\"resultKind\":")
                    .append(q(kind))
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
            json.append("],\"summary\":{\"limit\":")
                .append(limit)
                .append(",\"returned\":").append(rows.size())
                .append(",\"truncated\":").append(truncated)
                .append(",\"updateCount\":")
                .append("update".equals(kind)
                        ? String.valueOf(updateCount) : "null")
                .append("}}");
            return json.toString();
        }
    }
}
