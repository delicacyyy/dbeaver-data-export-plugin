package com.janetfilter.dbeaver.export.data;

import static com.janetfilter.dbeaver.export.connection.DBeaverConnections.ensureConnected;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.arr;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.c;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.call;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.closeAfter;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.closeIteratorAfter;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.construct;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.createMonitorProxy;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.isMongoDataSource;
import static com.janetfilter.dbeaver.export.runtime.DBeaverRuntime.providerClass;
import static com.janetfilter.dbeaver.export.data.DataExportJson.err;
import static com.janetfilter.dbeaver.export.data.DataExportJson.q;

import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DBeaverSchema {
    private DBeaverSchema() { }

    public static String describe(Map<String, String> q) throws Exception {
        String projectId = q.get("projectId");
        String datasourceId = q.get("dsId");
        DBeaverWorkspace.requireId(projectId, "projectId");
        DBeaverWorkspace.requireId(datasourceId, "dsId");
        Object container = DBeaverWorkspace.requireDatasource(
                DBeaverWorkspace.requireProject(projectId), datasourceId);
        ensureConnected(container);
        Object dataSource = call(container, "getDataSource");
        if (dataSource == null) return err("no dataSource");
        Object monitor = createMonitorProxy(container);
        if (monitor == null) return err("no progress monitor");

        SchemaDiscovery discovery = new SchemaDiscovery(
                projectId, datasourceId, dataSource, monitor);
        String mode = q.get("mode");
        if ("full".equalsIgnoreCase(mode)) {
            return fullSchemaJson(discovery, (String) call(container, "getName"), monitor);
        }
        if (mode != null && !mode.isEmpty()
                && !"children".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException(
                    "mode must be 'children' or 'full'");
        }

        String parentId = q.get("parent");
        SchemaPath parent = parentId == null || parentId.isEmpty()
                ? discovery.root
                : SchemaPath.decode(parentId, projectId, datasourceId);
        return schemaChildrenJson(discovery.children(parent), monitor);
    }

    static SchemaNode resolve(
            SchemaPath path, Object dataSource, Object monitor) throws Exception {
        SchemaDiscovery discovery = new SchemaDiscovery(
                path.projectId, path.datasourceId, dataSource, monitor);
        return discovery.resolve(path);
    }

    /**
     * Endpoint-facing schema discovery. Its interface is a stateless structural
     * path; DBeaver objects and navigator details stay inside.
     */
    private static final class SchemaDiscovery {
        private final SchemaPath root;
        private final Object dataSource;
        private final Object monitor;
        private final SchemaTypes types;
        private final MetadataAdapter structural = new StructuralMetadataAdapter();
        private final MetadataAdapter navigator = new NavigatorMetadataAdapter();
        private final boolean navigatorFallbackAllowed;

        private SchemaDiscovery(String projectId, String datasourceId,
                Object dataSource, Object monitor) throws Exception {
            this.root = SchemaPath.root(projectId, datasourceId);
            this.dataSource = dataSource;
            this.monitor = monitor;
            this.types = new SchemaTypes();
            this.navigatorFallbackAllowed = !isJdbcDataSource(dataSource);
        }

        private List<SchemaNode> children(SchemaPath parentPath) throws Exception {
            if (parentPath.segments.isEmpty()) {
                return rootChildren();
            }
            return children(resolve(parentPath));
        }

        private List<SchemaNode> rootChildren() throws Exception {
            if (isMongoDataSource(dataSource)) {
                return nodes(mongoDatabases(), "datasource", root);
            }
            return childrenOf(dataSource, "datasource", root);
        }

        private Object[] mongoDatabases() throws Exception {
            Object context = null;
            Object cursor = null;
            java.util.Iterator<?> databaseNames = null;
            Exception failure = null;
            ArrayList<Object> databases = new ArrayList<>();

            try {
                Object instance = call(dataSource, "getDefaultInstance");
                if (instance == null) {
                    throw new HttpException(
                            503, "default MongoDB instance is unavailable");
                }

                Object defaultContext = call(
                        instance, "getDefaultContext", arr(monitor, false));
                if (defaultContext == null) {
                    throw new HttpException(
                            503, "default MongoDB context is unavailable");
                }

                context = call(instance, "openIsolatedContext",
                        arr(monitor, "HTTP /schema root", defaultContext));
                if (context == null) {
                    throw new HttpException(
                            503, "provider returned no isolated context");
                }
                if (context == defaultContext) {
                    context = null;
                    throw new HttpException(
                            503, "provider returned the shared execution context");
                }
                call(context, "checkContextAlive", arr(monitor));

                Object client = call(context, "getClient");
                if (client == null) {
                    throw new HttpException(
                            503, "isolated MongoDB client is unavailable");
                }
                Object names = call(client, "listDatabaseNames");
                if (!(names instanceof Iterable)) {
                    throw new HttpException(
                            500, "MongoDB database names are not iterable");
                }
                databaseNames = ((Iterable<?>) names).iterator();
                cursor = databaseNames;
                Class<?> databaseClass = providerClass(
                        instance, "com.dbeaver.db.mongodb.model.MGDatabase");
                while (databaseNames.hasNext()) {
                    String name = String.valueOf(databaseNames.next());
                    databases.add(construct(databaseClass, instance, name));
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                failure = closeIteratorAfter(cursor, failure);
                failure = closeAfter(context, failure);
            }

            if (failure != null) throw failure;
            return databases.toArray();
        }

        private SchemaNode resolve(SchemaPath path) throws Exception {
            if (!root.projectId.equals(path.projectId)
                    || !root.datasourceId.equals(path.datasourceId)) {
                throw new IllegalArgumentException(
                        "node id belongs to another project or data source");
            }
            if (path.segments.isEmpty()) {
                throw new IllegalArgumentException(
                        "node id does not identify a data object");
            }
            Object parent = dataSource;
            String parentKind = "datasource";
            SchemaPath resolvedPath = root;
            SchemaNode match = null;

            for (SchemaPath.Segment segment : path.segments) {
                match = null;
                List<SchemaNode> candidates = resolvedPath.segments.isEmpty()
                        ? rootChildren()
                        : childrenOf(parent, parentKind, resolvedPath);
                for (SchemaNode child : candidates) {
                    if (segment.kind.equals(child.kind)
                            && segment.name.equals(child.name)) {
                        match = child;
                        break;
                    }
                }
                if (match == null) {
                    throw new HttpException(
                            404, "node no longer exists: " + segment.name);
                }
                parent = match.value;
                parentKind = match.kind;
                resolvedPath = match.path;
            }
            return match;
        }

        private List<SchemaNode> children(SchemaNode parent) throws Exception {
            return childrenOf(parent.value, parent.kind, parent.path);
        }

        private List<SchemaNode> childrenOf(
                Object parent, String parentKind, SchemaPath parentPath)
                throws Exception {
            Object[] values = structural.children(parent, parentKind, monitor, types);
            List<SchemaNode> result = nodes(values, parentKind, parentPath);

            if (result.isEmpty() && navigatorFallbackAllowed
                    && !"table".equals(parentKind)
                    && !"view".equals(parentKind)
                    && !"column".equals(parentKind)) {
                values = navigator.children(parent, parentKind, monitor, types);
                result = nodes(values, parentKind, parentPath);
            }
            return result;
        }

        private List<SchemaNode> nodes(
                Object[] values, String parentKind, SchemaPath parentPath)
                throws Exception {
            LinkedHashMap<String, SchemaNode> unique = new LinkedHashMap<>();
            if (values == null) return new ArrayList<>();

            for (Object value : values) {
                if (value == null || isSystemObject(value)) continue;
                String kind = types.kindOf(value, parentKind);
                if (kind == null) continue;
                String name = String.valueOf(call(value, "getName"));
                String key = kind + "\u0000" + name;
                if (!unique.containsKey(key)) {
                    SchemaPath path = parentPath.append(kind, name);
                    unique.put(key, new SchemaNode(value, name, kind, path));
                }
            }
            return new ArrayList<>(unique.values());
        }
    }

    private interface MetadataAdapter {
        Object[] children(
                Object parent, String parentKind, Object monitor, SchemaTypes types)
                throws Exception;
    }

    /**
     * Primary adapter: invokes only methods that describe structural metadata.
     * In particular it never calls a relational object's generic getChildren().
     */
    private static final class StructuralMetadataAdapter implements MetadataAdapter {
        @Override
        public Object[] children(
                Object parent, String parentKind, Object monitor, SchemaTypes types)
                throws Exception {
            switch (parentKind) {
                case "datasource": {
                    Object[] scopes = firstNonEmptyMetadata(
                            parent, monitor,
                            "getCatalogs", "getSchemas", "getKeyspaces",
                            "getDatabases");
                    if (scopes != null && scopes.length > 0) return scopes;
                    Object[] entities = allMetadata(
                            parent, monitor,
                            "getTables", "getViews", "getMaterializedViews",
                            "getCollections");
                    return entities != null ? entities : scopes;
                }
                case "catalog": {
                    Object[] schemas = firstNonEmptyMetadata(
                            parent, monitor, "getSchemas");
                    if (schemas != null && schemas.length > 0) return schemas;
                    Object[] entities = allMetadata(
                            parent, monitor,
                            "getTables", "getViews", "getMaterializedViews",
                            "getCollections");
                    return entities != null ? entities : schemas;
                }
                case "schema":
                    return allMetadata(
                            parent, monitor,
                            "getTables", "getViews", "getMaterializedViews",
                            "getCollections");
                case "table":
                case "view":
                    return callNoArgOrMon(parent, monitor, "getAttributes");
                default:
                    return new Object[0];
            }
        }
    }

    /**
     * Secondary adapter for non-JDBC providers. It may cross known structural
     * grouping folders, but never arbitrary navigator branches such as Jobs or
     * Administration.
     */
    private static final class NavigatorMetadataAdapter implements MetadataAdapter {
        @Override
        public Object[] children(
                Object parent, String parentKind, Object monitor, SchemaTypes types)
                throws Exception {
            Object platform = call(
                    c("org.jkiss.dbeaver.runtime.DBWorkbench"), "getPlatform");
            Object navigatorModel = call(platform, "getNavigatorModel");
            Object node = call(navigatorModel, "getNodeByObject", arr(parent));
            if (node == null) {
                node = call(navigatorModel, "getNodeByObject",
                        arr(monitor, parent, true));
            }
            if (node == null) return new Object[0];

            ArrayList<Object> result = new ArrayList<>();
            collectStructuralChildren(node, parentKind, monitor, types, result, 0);
            return result.toArray();
        }

        private void collectStructuralChildren(
                Object node, String parentKind, Object monitor, SchemaTypes types,
                List<Object> result, int depth) throws Exception {
            if (depth > 2) return;
            Object[] children = navigatorChildren(node, monitor);
            if (children == null) return;

            for (Object child : children) {
                Object value;
                try {
                    value = nodeValue(child);
                } catch (Exception ignored) {
                    continue;
                }
                if (value != null && types.kindOf(value, parentKind) != null) {
                    result.add(value);
                } else if (depth < 2 && isStructuralFolder(child)) {
                    collectStructuralChildren(
                            child, parentKind, monitor, types, result, depth + 1);
                }
            }
        }
    }

    private static final class SchemaTypes {
        private final Class<?> catalogClass;
        private final Class<?> schemaClass;
        private final Class<?> entityClass;

        private SchemaTypes() throws Exception {
            catalogClass = c("org.jkiss.dbeaver.model.struct.rdb.DBSCatalog");
            schemaClass = c("org.jkiss.dbeaver.model.struct.rdb.DBSSchema");
            entityClass = c("org.jkiss.dbeaver.model.struct.DBSEntity");
        }

        private String kindOf(Object value, String parentKind) {
            if ("table".equals(parentKind) || "view".equals(parentKind)) {
                return "column";
            }
            if ("datasource".equals(parentKind)
                    && catalogClass.isInstance(value)) {
                return "catalog";
            }
            if (("datasource".equals(parentKind) || "catalog".equals(parentKind))
                    && schemaClass.isInstance(value)) {
                return "schema";
            }
            if (("datasource".equals(parentKind)
                    || "catalog".equals(parentKind)
                    || "schema".equals(parentKind))
                    && entityClass.isInstance(value)) {
                return isView(value) ? "view" : "table";
            }
            return null;
        }
    }

    static final class SchemaNode {
        final Object value;
        final String name;
        final String kind;
        final SchemaPath path;

        private SchemaNode(
                Object value, String name, String kind, SchemaPath path) {
            this.value = value;
            this.name = name;
            this.kind = kind;
            this.path = path;
        }

        private boolean expandable() {
            return !"column".equals(kind);
        }
    }

    private static String schemaChildrenJson(
            List<SchemaNode> children, Object monitor)
            throws Exception {
        StringBuilder j = new StringBuilder("{\"items\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) j.append(",");
            SchemaNode child = children.get(i);
            j.append("{\"id\":").append(q(child.path.encode()))
             .append(",\"name\":").append(q(child.name))
             .append(",\"kind\":").append(q(child.kind))
             .append(",\"expandable\":").append(child.expandable());
            if ("column".equals(child.kind)) {
                String comment = columnComment(child.value, monitor);
                j.append(",\"dataType\":").append(q(colType(child.value)))
                 .append(",\"comment\":")
                 .append(comment == null ? "null" : q(comment));
            }
            j.append("}");
        }
        return j.append("]}").toString();
    }

    private static String fullSchemaJson(
            SchemaDiscovery discovery, String dataSourceName,
            Object monitor) throws Exception {
        ArrayList<FullScope> scopes = new ArrayList<>();
        collectFullScopes(discovery, null, dataSourceName, scopes, 0);

        StringBuilder j = new StringBuilder("[");
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) j.append(",");
            FullScope scope = scopes.get(i);
            j.append("{\"name\":").append(q(scope.name))
             .append(",\"type\":").append(q(scope.kind))
             .append(",\"tables\":[");
            for (int t = 0; t < scope.tables.size(); t++) {
                if (t > 0) j.append(",");
                SchemaNode table = scope.tables.get(t);
                j.append("{\"name\":").append(q(table.name))
                 .append(",\"type\":").append(q(table.kind))
                 .append(",\"columns\":[");
                List<SchemaNode> columns = discovery.children(table);
                for (int a = 0; a < columns.size(); a++) {
                    if (a > 0) j.append(",");
                    SchemaNode column = columns.get(a);
                    String comment = columnComment(column.value, monitor);
                    j.append("{\"name\":").append(q(column.name))
                     .append(",\"type\":").append(q(colType(column.value)))
                     .append(",\"comment\":")
                     .append(comment == null ? "null" : q(comment))
                     .append("}");
                }
                j.append("]}");
            }
            j.append("]}");
        }
        return j.append("]").toString();
    }

    private static void collectFullScopes(
            SchemaDiscovery discovery, SchemaNode parent, String dataSourceName,
            List<FullScope> result, int depth) throws Exception {
        if (depth > 8) {
            throw new IllegalStateException("schema hierarchy is too deep");
        }
        List<SchemaNode> children = parent == null
                ? discovery.children(discovery.root)
                : discovery.children(parent);
        ArrayList<SchemaNode> scopes = new ArrayList<>();
        ArrayList<SchemaNode> tables = new ArrayList<>();
        for (SchemaNode child : children) {
            if ("catalog".equals(child.kind) || "schema".equals(child.kind)) {
                scopes.add(child);
            } else if ("table".equals(child.kind) || "view".equals(child.kind)) {
                tables.add(child);
            }
        }

        if (parent == null) {
            if (!tables.isEmpty()) {
                result.add(new FullScope(dataSourceName, "datasource", tables));
            }
        } else if (!tables.isEmpty() || scopes.isEmpty()) {
            result.add(new FullScope(parent.name, parent.kind, tables));
        }
        for (SchemaNode scope : scopes) {
            collectFullScopes(discovery, scope, dataSourceName, result, depth + 1);
        }
    }

    private static final class FullScope {
        private final String name;
        private final String kind;
        private final List<SchemaNode> tables;

        private FullScope(String name, String kind, List<SchemaNode> tables) {
            this.name = name;
            this.kind = kind;
            this.tables = tables;
        }
    }

    private static boolean isJdbcDataSource(Object dataSource) {
        for (Class<?> type = dataSource.getClass();
             type != null; type = type.getSuperclass()) {
            String name = type.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains(".model.impl.jdbc.")
                    || name.endsWith(".jdbcdatasource")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSystemObject(Object value) {
        try {
            return Boolean.TRUE.equals(call(value, "isSystem"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Object[] navigatorChildren(Object node, Object mon) throws Exception {
        return toArray(call(node, "getChildren", arr(mon)));
    }

    private static Object nodeValue(Object node) throws Exception {
        try {
            return call(node, "getValueObject");
        } catch (NoSuchMethodException e) {
            return call(node, "getObject");
        }
    }

    private static boolean isStructuralFolder(Object node) {
        String label = null;
        for (String method : new String[]{
                "getNodeDisplayName", "getNodeItemPath", "getName"}) {
            try {
                Object value = call(node, method);
                if (value != null) {
                    label = String.valueOf(value);
                    break;
                }
            } catch (Exception ignored) { }
        }
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "").replace("_", "").replace("-", "");
        return "catalog".equals(normalized)
                || "catalogs".equals(normalized)
                || "schema".equals(normalized)
                || "schemas".equals(normalized)
                || "database".equals(normalized)
                || "databases".equals(normalized)
                || "keyspace".equals(normalized)
                || "keyspaces".equals(normalized)
                || "table".equals(normalized)
                || "tables".equals(normalized)
                || "view".equals(normalized)
                || "views".equals(normalized)
                || "collection".equals(normalized)
                || "collections".equals(normalized)
                || "entity".equals(normalized)
                || "entities".equals(normalized);
    }

    private static boolean isView(Object o) {
        try {
            return Boolean.TRUE.equals(call(o, "isView"));
        } catch (Exception e) {
            return o.getClass().getSimpleName().contains("View");
        }
    }

    private static String colType(Object col) {
        try {
            return (String) call(col, "getTypeName");
        } catch (Exception e) {
            return "?";
        }
    }

    private static String columnComment(Object column, Object monitor) {
        try {
            Class<?> lazyDescriptionType =
                    c("org.jkiss.dbeaver.model.DBPObjectWithLazyDescription");
            Object value;
            if (monitor != null && lazyDescriptionType.isInstance(column)) {
                value = call(column, "getDescription", arr(monitor));
            } else {
                Class<?> describedType =
                        c("org.jkiss.dbeaver.model.DBPObjectWithDescription");
                if (!describedType.isInstance(column)) return null;
                value = call(column, "getDescription");
            }
            if (value == null) return null;

            String comment = String.valueOf(value).trim();
            return comment.isEmpty() ? null : comment;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object[] firstNonEmptyMetadata(
            Object target, Object mon, String... names) throws Exception {
        Object[] emptyResult = null;
        Exception lastFailure = null;
        for (String name : names) {
            try {
                Object[] result = callNoArgOrMon(target, mon, name);
                if (result == null) continue;
                if (result.length > 0) return result;
                emptyResult = result;
            } catch (Exception failure) {
                lastFailure = failure;
            }
        }
        if (emptyResult != null) return emptyResult;
        if (lastFailure != null) throw lastFailure;
        return null;
    }

    private static Object[] allMetadata(
            Object target, Object mon, String... names) throws Exception {
        ArrayList<Object> combined = new ArrayList<>();
        boolean methodSucceeded = false;
        Exception lastFailure = null;
        for (String name : names) {
            try {
                Object[] result = callNoArgOrMon(target, mon, name);
                if (result == null) continue;
                methodSucceeded = true;
                for (Object value : result) {
                    if (value != null && !combined.contains(value)) {
                        combined.add(value);
                    }
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
        }
        if (methodSucceeded) return combined.toArray();
        if (lastFailure != null) throw lastFailure;
        return null;
    }

    private static Object[] callNoArgOrMon(
            Object t, Object mon, String... names) throws Exception {
        Exception lastFailure = null;
        for (String name : names) {
            for (Method m : t.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                try {
                    if (m.getParameterCount() == 0) return toArray(m.invoke(t));
                    if (m.getParameterCount() == 1 && mon != null
                            && m.getParameterTypes()[0].isInstance(mon)) {
                        return toArray(m.invoke(t, mon));
                    }
                } catch (Exception failure) {
                    lastFailure = failure;
                }
            }
        }
        if (lastFailure != null) throw lastFailure;
        return null;
    }

    private static Object[] toArray(Object obj) {
        if (obj instanceof Object[]) return (Object[]) obj;
        if (obj instanceof List) return ((List<?>) obj).toArray();
        if (obj instanceof java.util.Collection) {
            return ((java.util.Collection<?>) obj).toArray();
        }
        return null;
    }
}
