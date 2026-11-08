package com.janetfilter.dbeaver.export.http;

import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.utils.JsonRequests;
import com.janetfilter.dbeaver.export.connection.DBeaverConnections;
import com.janetfilter.dbeaver.export.data.DBeaverRows;
import com.janetfilter.dbeaver.export.data.DBeaverSchema;
import com.janetfilter.dbeaver.export.data.DBeaverSql;
import com.janetfilter.dbeaver.export.data.DataExportJson;
import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;
import com.janetfilter.dbeaver.export.runtime.evaluation.DBeaverRuntimeEvaluator;
import com.janetfilter.dbeaver.export.runtime.evaluation.RuntimeEvaluationResult;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP routing and transport; DBeaver operations remain outside the controller. */
final class DataExportController {
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    private final Class<?> httpExchangeClass;
    private final Class<?> headersClass;

    DataExportController() throws ClassNotFoundException {
        ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();
        httpExchangeClass = Class.forName(
                "com.sun.net.httpserver.HttpExchange", true, platformLoader);
        headersClass = Class.forName(
                "com.sun.net.httpserver.Headers", true, platformLoader);
    }

    void registerWith(Object server) throws Exception {
        // Keep jdk.httpserver types out of the bootstrap-loaded plugin's signatures.
        ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();
        Class<?> handlerClass = Class.forName(
                "com.sun.net.httpserver.HttpHandler", true, platformLoader);
        Object handler = Proxy.newProxyInstance(platformLoader,
                new Class[]{handlerClass}, (proxy, method, args) -> {
                    if (method.getName().equals("handle")) handle(args[0]);
                    return null;
                });
        Class<?> serverClass = Class.forName(
                "com.sun.net.httpserver.HttpServer", true, platformLoader);
        serverClass.getMethod("createContext", String.class, handlerClass)
                .invoke(server, "/", handler);
    }

    private void handle(Object exchange) {
        boolean administrative = false;
        try {
            String method = (String) callR(
                    exchange, httpExchangeClass, "getRequestMethod", new Class<?>[0]);
            URI uri = (URI) callR(
                    exchange, httpExchangeClass, "getRequestURI", new Class<?>[0]);
            String path = uri.getPath();
            administrative = isAdministrative(path, method);
            if (administrative && requestHeader(exchange, "Origin") != null) {
                throw new HttpException(403, "Origin is not allowed");
            }
            Map<String, String> query = queryParams(uri);
            switch (path) {
                case "/runtime/evaluate":
                    evaluateRuntime(exchange, method);
                    break;
                case "/projects":
                    listProjects(exchange, method);
                    break;
                case "/drivers":
                    listDrivers(exchange, method, query);
                    break;
                case "/datasources":
                    if ("POST".equalsIgnoreCase(method)) {
                        createDatasource(exchange, method);
                    } else {
                        listDatasources(exchange, method, query);
                    }
                    break;
                case "/schema":
                    getSchema(exchange, method, query);
                    break;
                case "/rows":
                    getRows(exchange, method, query);
                    break;
                case "/execute":
                    executeSql(exchange, method, query);
                    break;
                default:
                    if (path.startsWith("/connection-operations/")) {
                        getConnectionOperation(exchange, method, path);
                    } else if (path.startsWith("/datasources/")) {
                        routeDatasource(exchange, method, uri, query);
                    } else {
                        throw new HttpException(404, "route not found");
                    }
            }
        } catch (IllegalArgumentException e) {
            respondErrorOrClose(exchange, 400, administrative
                    ? new IllegalArgumentException("invalid administrative request") : e);
        } catch (HttpException e) {
            respondErrorOrClose(exchange, e.status, e);
        } catch (Exception e) {
            respondErrorOrClose(exchange, 500, administrative
                    ? new HttpException(500, "connection administration failed") : e);
        }
    }

    private static boolean isAdministrative(String path, String method) {
        return "/runtime/evaluate".equals(path)
                || "/projects".equals(path) || "/drivers".equals(path)
                || path.startsWith("/connection-operations/")
                || path.startsWith("/datasources/")
                || ("/datasources".equals(path) && "POST".equalsIgnoreCase(method));
    }

    private void evaluateRuntime(Object exchange, String method) throws Exception {
        requireMethod(method, "POST");
        Map<String, Object> request = readAdministrativeRequest(exchange);
        RuntimeEvaluationResult result = DBeaverRuntimeEvaluator.evaluate(request);
        respondR(exchange, result.status, result.json);
    }

    private void listProjects(Object exchange, String method) throws Exception {
        requireMethod(method, "GET");
        respondR(exchange, 200, JsonRequests.toJson(DBeaverWorkspace.projectListing()));
    }

    private void listDrivers(Object exchange, String method, Map<String, String> query)
            throws Exception {
        requireMethod(method, "GET");
        respondR(exchange, 200, JsonRequests.toJson(DBeaverConnections.drivers(query)));
    }

    private void createDatasource(Object exchange, String method) throws Exception {
        requireMethod(method, "POST");
        DBeaverConnections.Result result =
                DBeaverConnections.create(readAdministrativeRequest(exchange));
        respondR(exchange, result.status, JsonRequests.toJson(result.body));
    }

    private void getConnectionOperation(Object exchange, String method, String path)
            throws Exception {
        requireMethod(method, "GET");
        String id = path.substring("/connection-operations/".length());
        if (id.isEmpty() || id.indexOf('/') >= 0) {
            throw new HttpException(404, "connection operation not found");
        }
        respondR(exchange, 200, JsonRequests.toJson(DBeaverConnections.operation(id)));
    }

    private void routeDatasource(Object exchange, String method, URI uri,
            Map<String, String> query) throws Exception {
        String[] parts = uri.getRawPath().substring("/datasources/".length())
                .split("/", -1);
        if (parts[0].isEmpty() || parts.length > 2) {
            throw new HttpException(404, "connection route not found");
        }
        String id = URI.create("http://127.0.0.1/" + parts[0]).getPath().substring(1);
        if (parts.length == 1) {
            inspectDatasource(exchange, method, query, id);
        } else if ("edit".equals(parts[1])) {
            editDatasource(exchange, method, query, id);
        } else if ("test".equals(parts[1])) {
            testDatasource(exchange, method, query, id);
        } else {
            throw new HttpException(404, "connection route not found");
        }
    }

    private void inspectDatasource(Object exchange, String method,
            Map<String, String> query, String id) throws Exception {
        requireMethod(method, "GET");
        respondR(exchange, 200, JsonRequests.toJson(
                DBeaverConnections.inspect(query.get("projectId"), id)));
    }

    private void editDatasource(Object exchange, String method,
            Map<String, String> query, String id) throws Exception {
        requireMethod(method, "POST");
        DBeaverConnections.Result result = DBeaverConnections.edit(
                query.get("projectId"), id, readAdministrativeRequest(exchange));
        respondR(exchange, result.status, JsonRequests.toJson(result.body));
    }

    private void testDatasource(Object exchange, String method,
            Map<String, String> query, String id) throws Exception {
        requireMethod(method, "POST");
        DBeaverConnections.Result result = DBeaverConnections.test(
                query.get("projectId"), id, readAdministrativeRequest(exchange));
        respondR(exchange, result.status, JsonRequests.toJson(result.body));
    }

    private void listDatasources(Object exchange, String method, Map<String, String> query)
            throws Exception {
        requireMethod(method, "GET");
        respondData(exchange, JsonRequests.toJson(DBeaverWorkspace.datasourceListing(query.get("projectId"))));
    }

    private void getSchema(Object exchange, String method, Map<String, String> query)
            throws Exception {
        requireMethod(method, "GET");
        respondData(exchange, DBeaverSchema.describe(query));
    }

    private void getRows(Object exchange, String method, Map<String, String> query)
            throws Exception {
        requireMethod(method, "GET");
        respondData(exchange, DBeaverRows.read(query));
    }

    private void executeSql(Object exchange, String method, Map<String, String> query)
            throws Exception {
        requireMethod(method, "POST");
        InputStream body = (InputStream) callR(
                exchange, httpExchangeClass, "getRequestBody", new Class<?>[0]);
        String result;
        try {
            result = DBeaverSql.execute(query, readUtf8Body(body, MAX_REQUEST_BODY_BYTES));
        } finally {
            body.close();
        }
        respondData(exchange, result);
    }

    private String requestHeader(Object exchange, String name) throws Exception {
        Object headers = callR(
                exchange, httpExchangeClass, "getRequestHeaders", new Class<?>[0]);
        return (String) callR(
                headers, headersClass, "getFirst", new Class<?>[]{String.class}, name);
    }

    private Map<String, Object> readAdministrativeRequest(Object exchange)
            throws Exception {
        String contentType = requestHeader(exchange, "Content-Type");
        if (contentType == null || !"application/json".equalsIgnoreCase(
                contentType.split(";", 2)[0].trim())) {
            throw new HttpException(415, "application/json required");
        }
        try (InputStream body = (InputStream) callR(
                exchange, httpExchangeClass, "getRequestBody", new Class<?>[0])) {
            return JsonRequests.parseObject(readUtf8Body(body, MAX_REQUEST_BODY_BYTES));
        }
    }

    private static void requireMethod(String actual, String required) {
        if (!required.equalsIgnoreCase(actual)) {
            throw new HttpException(405, required + " required");
        }
    }

    private void respondData(Object exchange, String json) throws Exception {
        respondR(exchange, json.startsWith("{\"error\"") ? 500 : 200, json);
    }

    private void respondR(Object exchange, int code, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Object headers = callR(
                exchange, httpExchangeClass, "getResponseHeaders", new Class<?>[0]);
        callR(headers, headersClass, "set",
                new Class<?>[]{String.class, String.class},
                "Content-Type", "application/json; charset=utf-8");
        callR(exchange, httpExchangeClass, "sendResponseHeaders",
                new Class<?>[]{int.class, long.class}, code, (long) bytes.length);
        OutputStream output = (OutputStream) callR(
                exchange, httpExchangeClass, "getResponseBody", new Class<?>[0]);
        output.write(bytes);
        output.close();
    }

    private void respondErrorOrClose(Object exchange, int code, Exception error) {
        try {
            respondR(exchange, code, errJson(error));
        } catch (Exception responseFailure) {
            System.err.println("[dbeaver-data-export] RESPONSE FAILED: " + responseFailure);
            try {
                callR(exchange, httpExchangeClass, "close", new Class<?>[0]);
            } catch (Exception ignored) { }
        }
    }

    static Map<String, String> queryParams(URI uri) {
        Map<String, String> parameters = new LinkedHashMap<>();
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

    private static String errJson(Exception error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return "{\"error\":" + DataExportJson.q(cause.getClass().getSimpleName() + ": "
                + (message != null ? message : "null")) + "}";
    }

    private static String readUtf8Body(InputStream input, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
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
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("request body must be valid UTF-8");
        }
    }

    private static Object callR(Object target, Class<?> apiClass, String method,
            Class<?>[] parameterTypes, Object... args) throws Exception {
        return apiClass.getMethod(method, parameterTypes).invoke(target, args);
    }
}
