package com.janetfilter.dbeaver.export;

import com.janetfilter.core.Environment;
import com.janetfilter.core.plugin.MyTransformer;
import com.janetfilter.core.plugin.PluginConfig;
import com.janetfilter.core.plugin.PluginEntry;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DataExportPlugin implements PluginEntry {

    private static final String PORT_PROPERTY = "janf.dataExport.port";
    private static final String ROWS_TIMEOUT_PROPERTY =
            "janf.dataExport.rowsTimeoutSeconds";
    private static final String EXECUTE_TIMEOUT_PROPERTY =
            "janf.dataExport.executeTimeoutSeconds";
    private static final int DEFAULT_PORT = 8765;
    private static final int DEFAULT_ROWS_LIMIT = 100;
    private static final int MAX_ROWS_LIMIT = 1000;
    private static final int DEFAULT_ROWS_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_EXECUTE_TIMEOUT_SECONDS = 30;
    private static final int MAX_EXECUTE_BODY_BYTES = 1024 * 1024;
    private static CreationAccess creationAccess;
    private static volatile ClassLoader dbeaverClassLoader;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static int port;
    private static ExecutorService executor;

    // -- PluginEntry ----------------------------------------------------------

    @Override public String getName() { return "dbeaver-data-export"; }
    @Override public String getAuthor() { return "BrainStorm"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public String getDescription() { return "HTTP API for DBeaver data export."; }

    @Override public void init(Environment env, PluginConfig config) {
        port = Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT);
        creationAccess = CreationAccess.load();
    }

    @Override public List<MyTransformer> getTransformers() {
        return Arrays.asList(new ClassLoaderBridgeTransformer());
    }

    @SuppressWarnings("unused")
    public static void setDBeaverClassLoader(ClassLoader cl) {
        if (cl == null) return;
        dbeaverClassLoader = cl;
        if (started.compareAndSet(false, true)) {
            executor = Executors.newFixedThreadPool(4);
            new Thread(() -> {
                Thread.currentThread().setContextClassLoader(cl);
                try { startServer(); }
                catch (Exception e) {
                    System.err.println("[dbeaver-data-export] SERVER FAILED: " + e);
                    e.printStackTrace();
                }
            }, "dbeaver-data-export-server").start();
        }
    }

    // -- HTTP Server (all via reflection — bootstrap CL can't see jdk.httpserver)

    private static Object httpServer;
    private static Class<?> httpHandlerClass;
    private static Class<?> httpExchangeClass;
    private static Class<?> headersClass;
    private static void startServer() throws Exception {
        ClassLoader pl = ClassLoader.getPlatformClassLoader();
        Class<?> hsClass = Class.forName("com.sun.net.httpserver.HttpServer", true, pl);
        httpExchangeClass = Class.forName("com.sun.net.httpserver.HttpExchange", true, pl);
        headersClass = Class.forName("com.sun.net.httpserver.Headers", true, pl);
        httpServer = hsClass.getMethod("create", InetSocketAddress.class, int.class)
                .invoke(null, new InetSocketAddress("127.0.0.1", port), 0);

        httpHandlerClass = Class.forName("com.sun.net.httpserver.HttpHandler", true, pl);
        Object handler = Proxy.newProxyInstance(pl,
                new Class[]{httpHandlerClass}, (p, m, a) -> {
                    if (m.getName().equals("handle")) dispatch(a[0]);
                    return null;
        });
        hsClass.getMethod("createContext", String.class, httpHandlerClass)
                .invoke(httpServer, "/", handler);
        hsClass.getMethod("setExecutor", Executor.class).invoke(httpServer, executor);
        hsClass.getMethod("start").invoke(httpServer);
        System.out.println("[dbeaver-data-export] HTTP on http://127.0.0.1:" + port);
    }

    // -- Router ---------------------------------------------------------------

    private static void dispatch(Object ex) {
        boolean administrative = false;
        try {
            String method = (String) callR(
                    ex, httpExchangeClass, "getRequestMethod", new Class<?>[0]);
            URI uri = (URI) callR(
                    ex, httpExchangeClass, "getRequestURI", new Class<?>[0]);
            String path = uri.getPath();
            administrative = "/projects".equals(path) || "/drivers".equals(path)
                    || path.startsWith("/connection-operations/")
                    || path.startsWith("/datasources/")
                    || ("/datasources".equals(path) && "POST".equalsIgnoreCase(method));
            if (administrative) {
                creationAccess.require(
                        requestHeader(ex, "Authorization"), requestHeader(ex, "Origin"));
                Map<String, String> query = queryParams(uri);
                if ("/projects".equals(path)) {
                    requireMethod(method, "GET");
                    respondR(ex, 200, JsonRequests.toJson(DBeaverWorkspace.projectListing()));
                } else if ("/drivers".equals(path)) {
                    requireMethod(method, "GET");
                    respondR(ex, 200, JsonRequests.toJson(DBeaverConnections.drivers(query)));
                } else if (path.startsWith("/connection-operations/")) {
                    requireMethod(method, "GET");
                    String id = path.substring("/connection-operations/".length());
                    if (id.isEmpty() || id.indexOf('/') >= 0) {
                        throw new HttpException(404, "connection operation not found");
                    }
                    respondR(ex, 200, JsonRequests.toJson(DBeaverConnections.operation(id)));
                } else if ("/datasources".equals(path)) {
                    requireMethod(method, "POST");
                    DBeaverConnections.Result result =
                            DBeaverConnections.create(readAdministrativeRequest(ex));
                    respondR(ex, result.status, JsonRequests.toJson(result.body));
                } else {
                    String[] parts = uri.getRawPath().substring("/datasources/".length())
                            .split("/", -1);
                    if (parts[0].isEmpty() || parts.length > 2) {
                        throw new HttpException(404, "connection route not found");
                    }
                    String id = URI.create("http://127.0.0.1/" + parts[0])
                            .getPath().substring(1);
                    if (parts.length == 1) {
                        requireMethod(method, "GET");
                        respondR(ex, 200, JsonRequests.toJson(
                                DBeaverConnections.inspect(query.get("projectId"), id)));
                    } else if ("edit".equals(parts[1]) || "test".equals(parts[1])) {
                        requireMethod(method, "POST");
                        Map<String, Object> request = readAdministrativeRequest(ex);
                        DBeaverConnections.Result result = "edit".equals(parts[1])
                                ? DBeaverConnections.edit(query.get("projectId"), id, request)
                                : DBeaverConnections.test(query.get("projectId"), id, request);
                        respondR(ex, result.status, JsonRequests.toJson(result.body));
                    } else {
                        throw new HttpException(404, "connection route not found");
                    }
                }
                return;
            }

            Map<String, String> query = queryParams(uri);
            String result;
            switch (path) {
                case "/datasources":
                    requireMethod(method, "GET");
                    result = handleDatasourcesR(query);
                    break;
                case "/schema":
                    requireMethod(method, "GET");
                    result = handleSchemaR(query);
                    break;
                case "/rows":
                    requireMethod(method, "GET");
                    result = handleRowsR(query);
                    break;
                case "/execute":
                    requireMethod(method, "POST");
                    InputStream body = (InputStream) callR(
                            ex, httpExchangeClass, "getRequestBody", new Class<?>[0]);
                    try {
                        result = handleExecuteR(
                                query, readUtf8Body(body, MAX_EXECUTE_BODY_BYTES));
                    } finally {
                        body.close();
                    }
                    break;
                default:
                    throw new HttpException(404, "route not found");
            }
            respondR(ex, result.startsWith("{\"error\"") ? 500 : 200, result);
        } catch (IllegalArgumentException e) {
            respondErrorOrClose(ex, 400, administrative
                    ? new IllegalArgumentException("invalid administrative request") : e);
        } catch (HttpException e) {
            respondErrorOrClose(ex, e.status, e);
        } catch (Exception e) {
            respondErrorOrClose(ex, 500, administrative
                    ? new HttpException(500, "connection administration failed") : e);
        }
    }

    private static String requestHeader(Object exchange, String name) throws Exception {
        Object headers = callR(
                exchange, httpExchangeClass, "getRequestHeaders", new Class<?>[0]);
        return (String) callR(
                headers, headersClass, "getFirst", new Class<?>[]{String.class}, name);
    }

    private static Map<String, Object> readAdministrativeRequest(Object exchange)
            throws Exception {
        String contentType = requestHeader(exchange, "Content-Type");
        if (contentType == null || !"application/json".equalsIgnoreCase(
                contentType.split(";", 2)[0].trim())) {
            throw new HttpException(415, "application/json required");
        }
        try (InputStream body = (InputStream) callR(
                exchange, httpExchangeClass, "getRequestBody", new Class<?>[0])) {
            return JsonRequests.parseObject(readUtf8Body(body, MAX_EXECUTE_BODY_BYTES));
        }
    }

    private static void requireMethod(String actual, String required) {
        if (!required.equalsIgnoreCase(actual)) {
            throw new HttpException(405, required + " required");
        }
    }

    private static void respondErrorOrClose(Object ex, int code, Exception error) {
        try {
            respondR(ex, code, errJson(error));
        } catch (Exception responseFailure) {
            System.err.println("[dbeaver-data-export] RESPONSE FAILED: " + responseFailure);
            try {
                callR(ex, httpExchangeClass, "close", new Class<?>[0]);
            } catch (Exception ignored) { }
        }
    }

    // -- Handlers -------------------------------------------------------------

    private static String handleDatasourcesR(Map<String, String> query) throws Exception {
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(query.get("projectId"));
        List<Object> result = new ArrayList<Object>();
        for (Object descriptor : DBeaverWorkspace.datasources(project)) {
            DBeaverWorkspace.Identity identity = DBeaverWorkspace.identity(descriptor);
            Map<String, Object> item = identity.asMap();
            item.put("projectName", project.name);
            item.put("connected", identity.connected);
            result.add(item);
        }
        return JsonRequests.toJson(result);
    }

    private static String handleSchemaR(java.util.Map<String,String> q) throws Exception {
        String projectId = q.get("projectId");
        String datasourceId = q.get("dsId");
        DBeaverWorkspace.requireId(projectId, "projectId");
        DBeaverWorkspace.requireId(datasourceId, "dsId");
        Object c = DBeaverWorkspace.requireDatasource(
                DBeaverWorkspace.requireProject(projectId), datasourceId);
        ensureConnected(c);
        Object ds = call(c, "getDataSource");
        if (ds == null) return err("no dataSource");
        Object mon = createMonitorProxy(c);
        if (mon == null) return err("no progress monitor");

        SchemaDiscovery discovery = new SchemaDiscovery(projectId, datasourceId, ds, mon);
        String mode = q.get("mode");
        if ("full".equalsIgnoreCase(mode)) {
            return fullSchemaJson(discovery, (String) call(c, "getName"), mon);
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
        return schemaChildrenJson(discovery.children(parent), mon);
    }

    /**
     * The endpoint-facing schema discovery module. Its interface is a stateless
     * structural path; DBeaver objects and navigator details stay inside.
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
                return nodes(
                        mongoDatabases(), "datasource", root);
            }
            return childrenOf(dataSource, "datasource", root);
        }

        private Object[] mongoDatabases() throws Exception {
            Object context = null;
            Object cursor = null;
            java.util.Iterator<?> databaseNames = null;
            Exception failure = null;
            java.util.ArrayList<Object> databases =
                    new java.util.ArrayList<>();

            try {
                Object instance = call(
                        dataSource, "getDefaultInstance");
                if (instance == null) {
                    throw new HttpException(
                            503,
                            "default MongoDB instance is unavailable");
                }

                Object defaultContext = call(
                        instance, "getDefaultContext",
                        arr(monitor, false));
                if (defaultContext == null) {
                    throw new HttpException(
                            503,
                            "default MongoDB context is unavailable");
                }

                context = call(
                        instance, "openIsolatedContext",
                        arr(monitor, "HTTP /schema root", defaultContext));
                if (context == null) {
                    throw new HttpException(
                            503,
                            "provider returned no isolated context");
                }
                if (context == defaultContext) {
                    context = null;
                    throw new HttpException(
                            503,
                            "provider returned the shared execution context");
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
                            500,
                            "MongoDB database names are not iterable");
                }
                databaseNames = ((Iterable<?>) names).iterator();
                cursor = databaseNames;
                Class<?> databaseClass = providerClass(
                        instance,
                        "com.dbeaver.db.mongodb.model.MGDatabase");
                while (databaseNames.hasNext()) {
                    String name = String.valueOf(databaseNames.next());
                    databases.add(construct(
                            databaseClass, instance, name));
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
                List<SchemaNode> candidates =
                        resolvedPath.segments.isEmpty()
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
            java.util.LinkedHashMap<String,SchemaNode> unique =
                    new java.util.LinkedHashMap<>();
            if (values == null) return new java.util.ArrayList<>();

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
            return new java.util.ArrayList<>(unique.values());
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

            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            collectStructuralChildren(
                    node, parentKind, monitor, types, result, 0);
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

    private static final class SchemaNode {
        private final Object value;
        private final String name;
        private final String kind;
        private final SchemaPath path;

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


    static final class HttpException extends RuntimeException {
        final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
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
        java.util.ArrayList<FullScope> scopes = new java.util.ArrayList<>();
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
        java.util.ArrayList<SchemaNode> scopes = new java.util.ArrayList<>();
        java.util.ArrayList<SchemaNode> tables = new java.util.ArrayList<>();
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
            collectFullScopes(
                    discovery, scope, dataSourceName, result, depth + 1);
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

    private static String handleRowsR(java.util.Map<String,String> q) throws Exception {
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
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Object monitor = createMonitorProxy(container, deadlineNanos);
        if (monitor == null) {
            throw new HttpException(500, "no progress monitor");
        }

        SchemaDiscovery discovery = new SchemaDiscovery(
                path.projectId, path.datasourceId, dataSource, monitor);
        SchemaNode target = discovery.resolve(path);
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
        private final SchemaNode target;
        private final Object monitor;
        private final long deadlineNanos;

        private DataPageReader(
                Object dataSource, SchemaNode target,
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
            java.util.IdentityHashMap<Object,Boolean> seen =
                    new java.util.IdentityHashMap<>();
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
        private final java.util.ArrayList<DataField> fields =
                new java.util.ArrayList<>();
        private final java.util.ArrayList<java.util.List<String>> rows =
                new java.util.ArrayList<>();

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

            java.util.LinkedHashMap<String,Integer> keyCounts =
                    new java.util.LinkedHashMap<>();
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
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                Object value = call(resultSet, "getAttributeValue", arr(i));
                values.add(jsonValue(value, monitor, document));
            }
            rows.add(values);
        }

        private DataPage toPage(String sourceKind, long offset, int limit) {
            boolean hasMore = rows.size() > limit;
            int returned = Math.min(rows.size(), limit);
            java.util.ArrayList<java.util.List<String>> visibleRows =
                    new java.util.ArrayList<>();
            for (int i = 0; i < returned; i++) {
                visibleRows.add(rows.get(i));
            }
            return new DataPage(
                    sourceKind, document, fields, visibleRows,
                    offset, limit, hasMore);
        }
    }

    private static final class DataField {
        private final String name;
        private final String label;
        private final String type;
        private final String jsonKey;

        private DataField(
                String name, String label, String type, String jsonKey) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.jsonKey = jsonKey;
        }
    }

    private static final class DataPage {
        private final String sourceKind;
        private final boolean dynamicFields;
        private final List<DataField> fields;
        private final List<java.util.List<String>> rows;
        private final long offset;
        private final int limit;
        private final boolean hasMore;

        private DataPage(
                String sourceKind, boolean dynamicFields,
                List<DataField> fields, List<java.util.List<String>> rows,
                long offset, int limit, boolean hasMore) {
            this.sourceKind = sourceKind;
            this.dynamicFields = dynamicFields;
            this.fields = new java.util.ArrayList<>(fields);
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
                java.util.List<String> row = rows.get(rowIndex);
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

    private static String stringMethod(
            Object target, String method, String fallback) {
        try {
            Object value = call(target, method);
            return value == null ? fallback : String.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String handleExecuteR(java.util.Map<String,String> q, String body)
            throws Exception {
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

    // -- SQL ------------------------------------------------------------------

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
                                503,
                                "provider returned the shared "
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
            java.util.ArrayList<DataField> fields =
                    new java.util.ArrayList<>();
            Object metadata = call(resultSet, "getMeta");
            Object attributesValue = metadata == null
                    ? null : call(metadata, "getAttributes");
            if (attributesValue instanceof List) {
                java.util.LinkedHashMap<String,Integer> keyCounts =
                        new java.util.LinkedHashMap<>();
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

            java.util.ArrayList<java.util.List<String>> rows =
                    new java.util.ArrayList<>();
            while (rows.size() <= limit
                    && Boolean.TRUE.equals(call(resultSet, "nextRow"))) {
                java.util.ArrayList<String> row =
                        new java.util.ArrayList<>();
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

    private static Exception closeIteratorAfter(
            Object iterator, Exception failure) {
        if (!(iterator instanceof AutoCloseable)) return failure;
        try {
            ((AutoCloseable) iterator).close();
            return failure;
        } catch (Exception closeFailure) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
                return failure;
            }
            return closeFailure;
        }
    }

    private static boolean isMongoDataSource(Object dataSource) {
        for (Class<?> type = dataSource.getClass();
             type != null; type = type.getSuperclass()) {
            if ("com.dbeaver.db.mongodb.model.MGDataSource"
                    .equals(type.getName())) {
                return true;
            }
        }
        return false;
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

    private static Object createDataContainerProxy(
            Object dataSource) throws Exception {
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
                                return proxy
                                        == (args == null ? null : args[0]);
                            default: return null;
                        }
                    }
                    if ("getDataSource".equals(method.getName())) {
                        return dataSource;
                    }
                    return primitiveDefault(method.getReturnType());
                });
    }

    private static Exception closeAfter(
            Object resource, Exception failure) {
        if (resource == null) return failure;
        try {
            call(resource, "close");
            return failure;
        } catch (Exception closeFailure) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
                return failure;
            }
            return closeFailure;
        }
    }

    private static final class SqlExecutionResult {
        private final String kind;
        private final List<DataField> fields;
        private final List<java.util.List<String>> rows;
        private final int limit;
        private final boolean truncated;
        private final long updateCount;

        private SqlExecutionResult(
                String kind, List<DataField> fields,
                List<java.util.List<String>> rows, int limit,
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
                List<java.util.List<String>> rows,
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
                java.util.List<String> row = rows.get(rowIndex);
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

    // -- Connection -----------------------------------------------------------

    private static void ensureConnected(Object c) throws Exception {
        if ((Boolean) call(c, "isConnected")) return;
        Object mon = createMonitorProxy(c);
        if (mon == null) throw new RuntimeException("no monitor");
        call(c, "connect", arr(mon, true, false));
    }

    // -- Utilities ------------------------------------------------------------


    private static void respondR(Object ex, int code, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        Object hdrs = callR(
                ex, httpExchangeClass, "getResponseHeaders", new Class<?>[0]);
        callR(
                hdrs, headersClass, "set",
                new Class<?>[]{String.class, String.class},
                "Content-Type", "application/json; charset=utf-8");
        callR(
                ex, httpExchangeClass, "sendResponseHeaders",
                new Class<?>[]{int.class, long.class},
                code, (long) b.length);
        OutputStream os = (OutputStream) callR(
                ex, httpExchangeClass, "getResponseBody", new Class<?>[0]);
        os.write(b); os.close();
    }

    static java.util.Map<String,String> queryParams(URI uri) {
        java.util.Map<String,String> parameters = new java.util.LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                parameters.put(
                        java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair.length > 1
                                ? java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                                : "");
            }
        }
        return parameters;
    }

    private static int intParam(java.util.Map<String,String> q, String key, int def) {
        try { return Integer.parseInt(q.get(key)); } catch (Exception e) { return def; }
    }

    private static int boundedIntParam(
            java.util.Map<String,String> query, String key,
            int defaultValue, int minimum, int maximum) {
        String raw = query.get(key);
        if (raw == null || raw.isEmpty()) return defaultValue;
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static long nonNegativeLongParam(
            java.util.Map<String,String> query, String key, long defaultValue) {
        String raw = query.get(key);
        if (raw == null || raw.isEmpty()) return defaultValue;
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
        return value;
    }

    // -- JSON helpers ---------------------------------------------------------

    private static String jsonValue(
            Object value, Object monitor, boolean extendedJson)
            throws Exception {
        return jsonValue(
                value, monitor, extendedJson, 0,
                new java.util.IdentityHashMap<Object,Boolean>());
    }

    private static String jsonValue(
            Object value, Object monitor, boolean extendedJson, int depth,
            java.util.IdentityHashMap<Object,Boolean> seen) throws Exception {
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
        if ("java.sql.Date".equals(className)
                || "java.sql.Time".equals(className)) {
            return q(value);
        }
        if (value instanceof java.util.Date) {
            String instant = ((java.util.Date) value).toInstant().toString();
            return extendedJson ? dateJson(instant) : q(instant);
        }
        if (value.getClass().getName().startsWith("java.time.")) {
            return q(value);
        }
        if (value instanceof byte[]) {
            return binaryJson((byte[]) value, (byte) 0);
        }

        if ("org.bson.types.ObjectId".equals(className)) {
            return "{\"$oid\":" + q(call(value, "toHexString")) + "}";
        }
        if ("org.bson.types.Decimal128".equals(className)) {
            return "{\"$numberDecimal\":" + q(value) + "}";
        }
        if ("org.bson.types.Binary".equals(className)) {
            byte[] data = (byte[]) call(value, "getData");
            Object subtype = call(value, "getType");
            byte type = subtype instanceof Number
                    ? ((Number) subtype).byteValue() : (byte) 0;
            return binaryJson(data, type);
        }

        Class<?> documentClass =
                c("org.jkiss.dbeaver.model.data.DBDDocument");
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
            if (raw != value) {
                return jsonValue(
                        raw, monitor, extendedJson, depth + 1, seen);
            }
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
                for (Object entryValue
                        : ((java.util.Map<?,?>) value).entrySet()) {
                    java.util.Map.Entry<?,?> entry =
                            (java.util.Map.Entry<?,?>) entryValue;
                    if (!first) json.append(",");
                    first = false;
                    json.append(q(String.valueOf(entry.getKey())))
                        .append(":")
                        .append(jsonValue(
                                entry.getValue(), monitor,
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
                    json.append(jsonValue(
                            item, monitor, extendedJson, depth + 1, seen));
                }
                return json.append("]").toString();
            }
            if (value.getClass().isArray()) {
                StringBuilder json = new StringBuilder("[");
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    if (i > 0) json.append(",");
                    json.append(jsonValue(
                            java.lang.reflect.Array.get(value, i),
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
        String hexSubtype = String.format(
                java.util.Locale.ROOT, "%02x", subtype & 0xff);
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

    private static String q(Object s) {
        if (s == null) return "null";
        String value = s.toString();
        StringBuilder escaped = new StringBuilder(value.length() + 2)
                .append("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
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

    private static String err(String msg) {
        return "{\"error\":" + q(msg) + "}";
    }
    private static String errJson(Exception e) {
        Throwable cause = e;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String m = cause.getMessage();
        return "{\"error\":" + q(cause.getClass().getSimpleName() + ": "
                + (m != null ? m : "null")) + "}";
    }


    private static String readUtf8Body(
            InputStream input, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new HttpException(
                        413, "request body exceeds " + maxBytes + " bytes");
            }
            bytes.write(buffer, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("request body must be valid UTF-8");
        }
    }

    private static boolean isView(Object o) {
        try {
            return Boolean.TRUE.equals(call(o, "isView"));
        } catch (Exception e) {
            return o.getClass().getSimpleName().contains("View");
        }
    }
    private static String colType(Object col) {
        try { return (String) call(col, "getTypeName"); } catch (Exception e) { return "?"; }
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

    // -- Reflection infrastructure -------------------------------------------

    private static Object callR(
            Object target, Class<?> apiClass, String method,
            Class<?>[] parameterTypes, Object... args) throws Exception {
        return apiClass.getMethod(method, parameterTypes).invoke(target, args);
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
        java.util.ArrayList<Object> combined = new java.util.ArrayList<>();
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
                            && m.getParameterTypes()[0].isInstance(mon))
                        return toArray(m.invoke(t, mon));
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
        if (obj instanceof java.util.Collection)
            return ((java.util.Collection<?>) obj).toArray();
        return null;
    }

    private static Object createMonitorProxy(Object container) {
        return createMonitorProxy(container, Long.MAX_VALUE);
    }

    static Object createMonitorProxy(Object container, long deadlineNanos) {
        try {
            for (Method m : container.getClass().getMethods())
                if (m.getName().equals("connect") && m.getParameterCount() == 3)
                    return Proxy.newProxyInstance(m.getParameterTypes()[0].getClassLoader(),
                            new Class[]{m.getParameterTypes()[0]}, (p, method, a) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    switch (method.getName()) {
                                        case "toString": return "DataExportProgressMonitor";
                                        case "hashCode": return System.identityHashCode(p);
                                        case "equals": return p == (a == null ? null : a[0]);
                                        default: return null;
                                    }
                                }
                                if ("isCanceled".equals(method.getName())
                                        && method.getReturnType() == boolean.class) {
                                    return System.nanoTime() >= deadlineNanos;
                                }
                                return primitiveDefault(method.getReturnType());
                            });
        } catch (Exception ignored) { }
        return null;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        if (List.class.isAssignableFrom(type))
            return Collections.emptyList();
        return null;
    }

    static Class<?> c(String name) throws Exception {
        ClassLoader cl = dbeaverClassLoader;
        while (cl != null) {
            try { return Class.forName(name, true, cl); }
            catch (ClassNotFoundException ignored) { }
            cl = cl.getParent();
        }
        return Class.forName(name);
    }

    static Class<?> providerClass(Object anchor, String name)
            throws Exception {
        return Class.forName(
                name, true, anchor.getClass().getClassLoader());
    }

    static Object call(Object t, String method) throws Exception {
        Class<?> o = (t instanceof Class) ? (Class<?>) t : t.getClass();
        Method member = publicContractMethod(o, o.getMethod(method));
        return member.invoke(t instanceof Class ? null : t);
    }

    static Object call(Object t, String method, Object[] args) throws Exception {
        Class<?> o = (t instanceof Class) ? (Class<?>) t : t.getClass();
        for (Method m : o.getMethods())
            if (m.getName().equals(method)
                    && m.getParameterCount() == args.length
                    && parametersCompatible(m.getParameterTypes(), args))
                return publicContractMethod(o, m).invoke(t instanceof Class ? null : t, args);
        throw new NoSuchMethodException(method);
    }

    private static Method publicContractMethod(Class<?> type, Method method) {
        if (java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            return method;
        }
        Method contract = findPublicMethod(type, method.getName(), method.getParameterTypes());
        return contract != null ? contract : method;
    }

    private static Method findPublicMethod(
            Class<?> type, String name, Class<?>[] parameterTypes) {
        if (java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                if (java.lang.reflect.Modifier.isPublic(
                        method.getDeclaringClass().getModifiers())) return method;
            } catch (NoSuchMethodException absent) {
                // This public contract does not declare the requested method.
            }
        }
        for (Class<?> contract : type.getInterfaces()) {
            Method method = findPublicMethod(contract, name, parameterTypes);
            if (method != null) return method;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findPublicMethod(parent, name, parameterTypes);
    }

    static Object construct(Class<?> type, Object... args)
            throws Exception {
        for (java.lang.reflect.Constructor<?> constructor
                : type.getConstructors()) {
            if (constructor.getParameterCount() == args.length
                    && parametersCompatible(
                            constructor.getParameterTypes(), args)) {
                return constructor.newInstance(args);
            }
        }
        throw new NoSuchMethodException(type.getName() + " constructor");
    }

    private static boolean parametersCompatible(
            Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            Class<?> parameterType = parameterTypes[i];
            if (arg == null) {
                if (parameterType.isPrimitive()) return false;
                continue;
            }
            if (parameterType.isPrimitive()) {
                parameterType = primitiveWrapper(parameterType);
            }
            if (!parameterType.isInstance(arg)) return false;
        }
        return true;
    }

    private static Class<?> primitiveWrapper(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    static Object[] arr(Object... vals) { return vals; }
}
