package com.janetfilter.dbeaver.export.connection;

import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ConnectionSettingsTest {
    private static final String PROVIDER = "mongodb";
    private static final String DRIVER = "cql";

    @Test public void portNumbersMustFitTheDocumentedRangeWithoutHugeExpansion() {
        assertBadRequest(() -> ConnectionSettings.parse("postgresql", map("port", "")));
        assertBadRequest(() -> ConnectionSettings.parse("postgresql", map("port", "0")));
        assertBadRequest(() -> ConnectionSettings.parse("postgresql", map("port", "65536")));
        assertBadRequest(() -> ConnectionSettings.parse("postgresql",
                map("port", new java.math.BigDecimal("1E+10000"))));
        assertBadRequest(() -> ConnectionSettings.parse("postgresql",
                map("port", new java.math.BigDecimal("5432.5"))));
    }

    @Test public void permitsOnlyTheConfirmedMongoAuthenticationSettings() {
        assertEquals("5432", ConnectionSettings.parse("postgresql",
                map("port", new java.math.BigDecimal("5432.0"))).get("port"));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER, Arrays.asList("url")));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("username", "credentials-not-collected")));
        Map<String, Object> connection = map(
                "providerProperties", map("authSource", "admin",
                        "authMechanism", "SCRAM-SHA-256"),
                "url", "mongodb://node1:27017,node2:27017/catalog?authSource=admin");
        Map<String, Object> parsed = ConnectionSettings.parse(PROVIDER, connection);
        assertEquals("admin", ((Map<?, ?>) parsed.get("providerProperties")).get("authSource"));
        assertTrue(ConnectionSettings.safePropertyName(PROVIDER, "authSource"));
        assertTrue(ConnectionSettings.safePropertyName(PROVIDER, "authMechanism"));
        assertFalse(ConnectionSettings.containsCredentials(PROVIDER,
                "mongodb://mongo.example/catalog?authSource=admin&authMechanism=SCRAM-SHA-256"));
        assertTrue(ConnectionSettings.containsCredentials(PROVIDER,
                "mongodb://user:secret@mongo.example/catalog?authSource=admin"));
        assertTrue(ConnectionSettings.containsCredentials(PROVIDER,
                "mongodb://mongo.example/catalog?password=secret"));

        assertBadRequest(() -> ConnectionSettings.parse("postgresql",
                map("providerProperties", map("authSource", "admin"))));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("properties", map("authSource", "admin"))));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("providerProperties", map("username", "native-user"))));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("providerProperties", map("password", "native-secret"))));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("url", "mongodb://native-user:native-secret@mongo.example/catalog")));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("url", "mongodb://mongo.example/catalog?token=private")));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("url", "mongodb://mongo.example/catalog?auth=admin")));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("providerProperties", map("authMechanism", "password=secret"))));
        assertBadRequest(() -> ConnectionSettings.parse(PROVIDER,
                map("providerProperties", map("authMechanism", "UNKNOWN-MECHANISM"))));
        assertFalse(ConnectionSettings.safePropertyName("postgresql", "authSource"));
    }

    @Test public void appliesMongoUriDatabaseAndRejectsConflictingExplicitDatabase()
            throws Exception {
        FakeDriver driver = new FakeDriver();
        FakeConfiguration configuration = new FakeConfiguration();
        Map<String, Object> parsed = ConnectionSettings.parse(PROVIDER, map(
                "url", "mongo://node1:27017,node2:27017/admin?authSource=admin"));
        ConnectionSettings.apply(configuration, driver, parsed);
        assertEquals("admin", configuration.database);
        assertEquals("admin", configuration.providerProperties.get("authSource"));
        assertEquals("URL", configuration.configurationType);

        Map<String, Object> conflicting = ConnectionSettings.parse(PROVIDER, map(
                "database", "local", "url", "mongo://mongo.example/admin"));
        assertStatus(422, () -> ConnectionSettings.apply(
                new FakeConfiguration(), driver, conflicting));
    }

    @Test public void snapshotsOnlySelectedSafeValuesAndRedactsSensitiveUrls()
            throws Exception {
        FakeDriver driver = new FakeDriver();
        FakeConfiguration configuration = new FakeConfiguration();
        configuration.host = "mongo.example";
        configuration.database = null;
        configuration.url = "mongodb://node1:27017,node2:27017/admin?authSource=admin";
        configuration.properties.put("applicationName", "console");
        configuration.properties.put("password", "never-export-this");
        configuration.providerProperties.put("authSource", "admin");
        configuration.providerProperties.put("authMechanism", "SCRAM-SHA-256");
        configuration.providerProperties.put("password", "never-export-this-either");
        configuration.unlistedNativeValue = "opaque native configuration";
        Map<String, Object> safeSnapshot = ConnectionSettings.snapshot(driver, configuration);
        assertEquals(configuration.url, safeSnapshot.get("url"));
        assertEquals("admin", safeSnapshot.get("database"));
        assertEquals("admin",
                ((Map<?, ?>) safeSnapshot.get("providerProperties")).get("authSource"));
        configuration.database = "admin";
        configuration.url = "mongodb://private-user:private-pass@mongo.example/admin";

        Map<String, Object> snapshot = ConnectionSettings.snapshot(driver, configuration);
        assertEquals("mongo.example", snapshot.get("host"));
        assertEquals("admin", snapshot.get("database"));
        assertFalse(snapshot.containsKey("url"));
        assertEquals(Collections.singletonMap("applicationName", "console"),
                snapshot.get("properties"));
        assertEquals("admin", ((Map<?, ?>) snapshot.get("providerProperties")).get("authSource"));
        assertEquals("SCRAM-SHA-256",
                ((Map<?, ?>) snapshot.get("providerProperties")).get("authMechanism"));
        String serialized = snapshot.toString();
        assertFalse(serialized.contains("private-user"));
        assertFalse(serialized.contains("private-pass"));
        assertFalse(serialized.contains("never-export-this"));
        assertFalse(serialized.contains("opaque native configuration"));
    }

    @Test public void unknownOpaqueUrlsAreNotExportedFromLiveOrStoredSettings()
            throws Exception {
        for (String url : Arrays.asList("custom:PRIVATE_SENTINEL", "jdbc:opaque:PRIVATE_SENTINEL")) {
            FakeConfiguration configuration = new FakeConfiguration();
            configuration.url = url;
            Map<String, Object> snapshot = ConnectionSettings.snapshot(
                    new FakeDriver(), configuration);
            assertFalse(snapshot.containsKey("url"));
            assertFalse(snapshot.toString().contains("PRIVATE_SENTINEL"));
            String json = "{\"connections\":{\"target-id\":{\"provider\":\"mongodb\","
                    + "\"driver\":\"cql\",\"name\":\"Target\","
                    + "\"configuration\":{\"url\":\"" + url + "\"}}}}";
            ConnectionSettings.StoredProfile stored =
                    ConnectionSettings.readPersisted(bytes(json), identity());
            assertTrue(stored.matches);
            assertFalse(stored.settings.containsKey("url"));
            assertFalse(stored.settings.toString().contains("PRIVATE_SENTINEL"));
        }
    }

    @Test public void readsOnlyTargetSafeSettingsAndValidatesCompleteJsonDocument()
            throws Exception {
        DBeaverWorkspace.Identity expected = identity();
        String json = "{\"connections\":{" +
                "\"elsewhere\":{\"provider\":\"postgresql\",\"driver\":\"pg\",\"name\":\"Other\",\"configuration\":{\"password\":\"other-secret\"}}," +
                "\"target-id\":{\"provider\":\"mongodb\",\"driver\":\"cql\",\"name\":\"Target\",\"configuration\":{" +
                "\"url\":\"mongodb://mongo.example/admin?authSource=admin&authMechanism=SCRAM-SHA-256\"," +
                "\"properties\":{\"applicationName\":\"tool\",\"username\":\"hidden-user\",\"password\":\"hidden-secret\"}," +
                "\"providerProperties\":{\"authSource\":\"admin\",\"authMechanism\":\"SCRAM-SHA-256\",\"password\":\"hidden-secret\"}," +
                "\"username\":\"ignored-user\",\"password\":\"ignored-secret\"}," +
                "\"credentials\":{\"password\":\"credential-secret\"}}}," +
                "\"auth-profiles\":{\"shared\":{\"password\":\"profile-secret\"}}}";
        ConnectionSettings.StoredProfile stored = ConnectionSettings.readPersisted(
                bytes(json), expected);
        assertTrue(stored.matches);
        assertEquals("admin", stored.settings.get("database"));
        assertEquals("tool", ((Map<?, ?>) stored.settings.get("properties")).get("applicationName"));
        assertEquals("admin", ((Map<?, ?>) stored.settings.get("providerProperties")).get("authSource"));
        assertFalse(stored.settings.toString().contains("secret"));
        assertFalse(stored.settings.toString().contains("hidden-user"));

        DBeaverWorkspace.Identity wrongDriver = new DBeaverWorkspace.Identity(
                expected.id, expected.name, expected.providerId, "another-driver",
                expected.projectId, false);
        ConnectionSettings.StoredProfile mismatch = ConnectionSettings.readPersisted(
                bytes(json), wrongDriver);
        assertFalse(mismatch.matches);
        assertTrue(mismatch.settings.isEmpty());
        ConnectionSettings.StoredProfile incomplete = ConnectionSettings.readPersisted(
                bytes("{\"connections\":{\"target-id\":{\"driver\":\"cql\","
                        + "\"name\":\"Target\"}}}"), expected);
        assertFalse(incomplete.matches);
        assertTrue(incomplete.settings.isEmpty());

        try {
            ConnectionSettings.readPersisted(bytes(json.substring(0, json.length() - 1)), expected);
            fail("trailing truncation must not prove persistence");
        } catch (java.io.IOException expectedFailure) {
            // The whole native document is required for persistence proof.
        }
    }

    @Test public void readsSafeXmlSettingsWithoutEarlyReturningOrResolvingEntities()
            throws Exception {
        DBeaverWorkspace.Identity expected = identity();
        String xml = "<data-sources><data-source id=\"target-id\" name=\"Target\" " +
                "provider=\"mongodb\" driver=\"cql\"><connection type=\"URL\" " +
                "url=\"mongodb://mongo.example/admin?authSource=admin\"><properties>" +
                "<property name=\"applicationName\" value=\"tool\"/>" +
                "<property name=\"password\" value=\"hidden-secret\"/>" +
                "</properties><provider-properties><property name=\"authSource\" value=\"admin\"/>" +
                "<property name=\"password\" value=\"provider-secret\"/></provider-properties>" +
                "<credentials password=\"credential-secret\"/></connection></data-source>" +
                "<auth-profiles><profile password=\"profile-secret\"/></auth-profiles></data-sources>";
        ConnectionSettings.StoredProfile stored = ConnectionSettings.readPersisted(
                bytes(xml), expected);
        assertTrue(stored.matches);
        assertEquals("admin", stored.settings.get("database"));
        assertEquals("tool", ((Map<?, ?>) stored.settings.get("properties")).get("applicationName"));
        assertEquals("admin", ((Map<?, ?>) stored.settings.get("providerProperties")).get("authSource"));
        assertFalse(stored.settings.toString().contains("secret"));
        try {
            ConnectionSettings.readPersisted(
                    bytes(xml.substring(0, xml.lastIndexOf("</data-sources>"))), expected);
            fail("truncated XML after the matching profile must not prove persistence");
        } catch (Exception expectedFailure) {
            // The scanner must parse through the document end before confirming a match.
        }

        String dtd = "<!DOCTYPE data-sources [<!ENTITY ext SYSTEM \"file:///secret\">]>" +
                "<data-sources>&ext;</data-sources>";
        try {
            ConnectionSettings.readPersisted(bytes(dtd), expected);
            fail("DTD-bearing profile storage must be rejected");
        } catch (Exception expectedFailure) {
            // Native profile XML must never resolve document-defined entities.
        }
    }

    private static DBeaverWorkspace.Identity identity() {
        return new DBeaverWorkspace.Identity("target-id", "Target", PROVIDER,
                DRIVER, "project-1", false);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertBadRequest(ThrowingRunnable action) {
        assertStatus(400, action);
    }

    private static void assertStatus(int status, ThrowingRunnable action) {
        try {
            action.run();
            fail("expected HTTP " + status);
        } catch (HttpException error) {
            assertEquals(status, error.status);
            assertFalse(error.getMessage().contains("native-secret"));
            assertFalse(error.getMessage().contains("private"));
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    public static final class FakeDriver {
        public String getProviderId() { return PROVIDER; }
        public Iterable<String> getSupportedConfigurationTypes() {
            return Arrays.asList("URL", "MANUAL");
        }
        public Iterable<String> getSupportedPageFields() {
            return Arrays.asList("host", "port", "database", "server");
        }
        public Object[] getMainPropertyDescriptors() {
            return new Object[] { new PropertyDescriptor("applicationName", String.class) };
        }
        public Object[] getProviderPropertyDescriptors() {
            return new Object[] {
                    new PropertyDescriptor("authSource", String.class, "auth"),
                    new PropertyDescriptor("authMechanism", String.class, "auth"),
                    new PropertyDescriptor("password", String.class, "secret")
            };
        }
    }

    public static final class PropertyDescriptor {
        private final String id;
        private final Class<?> type;
        private final String[] features;
        PropertyDescriptor(String id, Class<?> type, String... features) {
            this.id = id;
            this.type = type;
            this.features = features;
        }
        public String getId() { return id; }
        public Class<?> getDataType() { return type; }
        public Object[] getFeatures() { return features; }
        public boolean hasFeature(String feature) {
            for (String current : features) if (current.equals(feature)) return true;
            return false;
        }
        public boolean isHidden() { return false; }
    }

    public static final class FakeConfiguration {
        String configurationType;
        String host;
        String port;
        String database;
        String server;
        String url;
        String unlistedNativeValue;
        final Map<String, String> properties = new LinkedHashMap<String, String>();
        final Map<String, String> providerProperties = new LinkedHashMap<String, String>();
        public void setConfigurationType(Object type) { configurationType = String.valueOf(type); }
        public void setHostName(String value) { host = value; }
        public void setHostPort(String value) { port = value; }
        public void setDatabaseName(String value) { database = value; }
        public void setServerName(String value) { server = value; }
        public void setUrl(String value) { url = value; }
        public void setProperty(String key, String value) { properties.put(key, value); }
        public void setProviderProperty(String key, String value) {
            providerProperties.put(key, value);
        }
        public String getConfigurationType() { return configurationType; }
        public String getHostName() { return host; }
        public String getHostPort() { return port; }
        public String getDatabaseName() { return database; }
        public String getServerName() { return server; }
        public String getUrl() { return url; }
        public Map<String, String> getProperties() { return properties; }
        public Map<String, String> getProviderProperties() { return providerProperties; }
    }
}
