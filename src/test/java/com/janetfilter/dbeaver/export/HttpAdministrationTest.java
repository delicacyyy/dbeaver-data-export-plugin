package com.janetfilter.dbeaver.export;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HttpAdministrationTest {
    private static Object server;
    private static ExecutorService workers;
    private static String baseUrl;
    private static String originalPort;
    private static HttpClient client;

    @BeforeClass
    public static void start() throws Exception {
        originalPort = System.getProperty("janf.dataExport.port");
        System.setProperty("janf.dataExport.port", "0");
        new DataExportPlugin().init(null, null);
        workers = Executors.newFixedThreadPool(4);
        field("executor").set(null, workers);
        Method startServer = DataExportPlugin.class.getDeclaredMethod("startServer");
        startServer.setAccessible(true);
        startServer.invoke(null);
        server = field("httpServer").get(null);
        Class<?> serverApi = Class.forName(
                "com.sun.net.httpserver.HttpServer", true, ClassLoader.getPlatformClassLoader());
        InetSocketAddress address = (InetSocketAddress)
                serverApi.getMethod("getAddress").invoke(server);
        baseUrl = "http://127.0.0.1:" + address.getPort();
        client = HttpClient.newBuilder().executor(workers)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterClass
    public static void stop() throws Exception {
        try {
            if (server != null) {
                Class<?> serverApi = Class.forName(
                        "com.sun.net.httpserver.HttpServer", true,
                        ClassLoader.getPlatformClassLoader());
                serverApi.getMethod("stop", int.class).invoke(server, 0);
            }
        } finally {
            if (workers != null) workers.shutdownNow();
            client = null;
            restore("janf.dataExport.port", originalPort);
        }
    }


    @Test
    public void browserOriginsAreRejected() throws Exception {
        for (String path : new String[] {
                "/projects", "/drivers", "/datasources/profile?projectId=p"
        }) {
            assertEquals(403, request("GET", path,
                    "https://untrusted.example", null, null).status);
        }
        for (String path : new String[] {
                "/datasources/profile/edit?projectId=p",
                "/datasources/profile/test?projectId=p"
        }) {
            assertEquals(403, request("POST", path,
                    "https://untrusted.example", "application/json", bytes("{}")).status);
        }
    }

    @Test
    public void administrativeMutationsRequireJsonMediaType() throws Exception {
        for (String path : new String[] {
                "/datasources", "/datasources/profile/edit?projectId=p",
                "/datasources/profile/test?projectId=p"
        }) {
            assertEquals(415, request("POST", path, null, "text/plain", bytes("{}")).status);
        }
    }

    @Test
    public void malformedAdministrativeJsonDoesNotEchoPayloads() throws Exception {
        String privateValue = "do-not-echo-this-request-value";
        Response response = request("POST", "/datasources", null,
                "application/json; charset=utf-8", bytes(
                        "{\"value\":\"" + privateValue + "\",\"ignored\":invalid}"));
        assertEquals(400, response.status);
        assertFalse(response.body.contains(privateValue));
    }

    @Test
    public void creationBodyLimitIsEnforced() throws Exception {
        byte[] body = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(body, (byte) ' ');
        assertEquals(413, request("POST", "/datasources", null,
                "application/json", body).status);
    }

    @Test
    public void missingOperationHasSpecificStatus() throws Exception {
        assertEquals(404, request("GET", "/connection-operations/not-an-operation",
                null, null, null).status);
    }

    @Test
    public void datasourceAndOperationMethodsAreEnforced() throws Exception {
        assertEquals(405, request("DELETE", "/datasources", null, null, null).status);
        assertEquals(405, request("POST", "/connection-operations/unknown",
                null, "application/json", bytes("{}" )).status);
        assertEquals(405, request("GET", "/execute", null, null, null).status);
        assertEquals(405, request("POST", "/projects", null,
                "application/json", bytes("{}")).status);
        assertEquals(405, request("POST", "/datasources/profile?projectId=p", null,
                "application/json", bytes("{}")).status);
        assertEquals(405, request("GET", "/datasources/profile/edit?projectId=p",
                null, null, null).status);
        assertEquals(405, request("GET", "/datasources/profile/test?projectId=p",
                null, null, null).status);
        assertEquals(405, request("POST", "/schema", null,
                "application/json", bytes("{}")).status);
        assertEquals(405, request("POST", "/rows", null,
                "application/json", bytes("{}")).status);
    }

    @Test
    public void executeRejectsMalformedIgnoredValuesBeforeDatasourceLookup() throws Exception {
        assertEquals(400, request("POST", "/execute?projectId=p&dsId=unused", null,
                "application/json", bytes(
                        "{\"sql\":\"SELECT 1\",\"ignored\":invalid}")).status);
    }

    @Test
    public void invalidUtf8CannotSilentlyChangeSqlText() throws Exception {
        byte[] body = bytes("{\"sql\":\"SELECT 1\"}");
        body[10] = (byte) 0xff;
        assertEquals(400, request("POST", "/execute?projectId=p&dsId=unused", null,
                "application/json", body).status);
    }

    @Test
    public void dataSelectorsCannotFallBackToFirstProjectOrDatasourceName() throws Exception {
        assertEquals(400, request("GET", "/datasources", null, null, null).status);
        assertEquals(400, request("GET", "/schema?projectId=p&ds=legacy-name",
                null, null, null).status);
        assertEquals(400, request("POST", "/execute?projectId=p&ds=legacy-name",
                null, "application/json", bytes("{\"sql\":\"SELECT 1\"}")).status);
    }

    private static Response request(String method, String path, String origin,
                                    String contentType, byte[] body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (origin != null) request.header("Origin", origin);
        if (contentType != null) request.header("Content-Type", contentType);
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }


    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Field field(String name) throws Exception {
        Field field = DataExportPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
