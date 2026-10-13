package com.janetfilter.dbeaver.export;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DBeaverConnectionsTest {
    @Test public void validatesGuidedAndDirectRequestBoundaries() {
        Map<String, Object> guided = request("guided");
        guided.put("connection", map("host", " host.example ", "port",
                new java.math.BigDecimal("5432")));
        DBeaverConnections.CreationRequest parsed =
                DBeaverConnections.parseRequest(guided);
        assertEquals(" host.example ", parsed.connection.get("host"));
        assertEquals("5432", parsed.connection.get("port"));
        assertNull(parsed.name);

        Map<String, Object> direct = request("direct");
        direct.put("name", "catalog-profile");
        direct.put("connection", map("url", "jdbc:postgresql://db.example/catalog"));
        assertEquals("jdbc:postgresql://db.example/catalog",
                DBeaverConnections.parseRequest(direct).connection.get("url"));

        final Map<String, Object> invalidField = request("guided");
        invalidField.put("connection", map("password", "not-accepted"));
        assertStatus(400, () -> DBeaverConnections.parseRequest(invalidField));

        final Map<String, Object> invalidProperty = request("guided");
        invalidProperty.put("connection",
                map("properties", map("auth.token", "private-value")));
        assertStatus(400, () -> DBeaverConnections.parseRequest(invalidProperty));
        final Map<String, Object> invalidPropertyValue = request("guided");
        invalidPropertyValue.put("connection",
                map("properties", map("applicationName", Integer.valueOf(12))));
        assertStatus(400,
                () -> DBeaverConnections.parseRequest(invalidPropertyValue));
        final Map<String, Object> credentialPropertyUrl = request("guided");
        credentialPropertyUrl.put("connection", map("properties",
                map("jdbcUrl",
                        "jdbc:postgresql://db.example/catalog?password=secret")));
        assertStatus(400,
                () -> DBeaverConnections.parseRequest(credentialPropertyUrl));

        final Map<String, Object> credentialUrl = request("direct");
        credentialUrl.put("name", "catalog-profile");
        credentialUrl.put("connection", map(
                "url", "jdbc:postgresql://user:secret@db.example/catalog"));
        assertStatus(400, () -> DBeaverConnections.parseRequest(credentialUrl));

        final Map<String, Object> nonJdbcUrl = request("direct");
        nonJdbcUrl.put("name", "catalog-profile");
        nonJdbcUrl.put("connection", map("url", "https://db.example/catalog"));
        assertStatus(422, () -> DBeaverConnections.parseRequest(nonJdbcUrl));

        final Map<String, Object> unsupportedDirectField = request("direct");
        unsupportedDirectField.put("name", "catalog-profile");
        unsupportedDirectField.put("connection", map(
                "url", "jdbc:postgresql://db.example/catalog",
                "host", "ignored.example"));
        assertStatus(422,
                () -> DBeaverConnections.parseRequest(unsupportedDirectField));
        final Map<String, Object> invalidPort = request("guided");
        invalidPort.put("connection", map("port", "70000"));
        assertStatus(400, () -> DBeaverConnections.parseRequest(invalidPort));
        final Map<String, Object> fractionalPort = request("guided");
        fractionalPort.put("connection", map("port", new java.math.BigDecimal("5432.5")));
        assertStatus(400, () -> DBeaverConnections.parseRequest(fractionalPort));

        final Map<String, Object> shellCommand = request("guided");
        shellCommand.put("connection",
                map("properties", map("shellCommand", "run-this")));
        assertStatus(400, () -> DBeaverConnections.parseRequest(shellCommand));

        final Map<String, Object> nullName = request("guided");
        nullName.put("name", null);
        assertStatus(400, () -> DBeaverConnections.parseRequest(nullName));
    }

    @Test public void directCapabilityRequiresJdbcUrlAndUsableLocalDriver() {
        Set<String> url = Collections.singleton("URL");
        assertTrue(DBeaverConnections.directCapability(
                true, url, false, false, true, true));
        assertFalse(DBeaverConnections.directCapability(
                false, url, false, false, true, true));
        assertFalse(DBeaverConnections.directCapability(
                true, Collections.singleton("MANUAL"), false, false, true, true));
        assertFalse(DBeaverConnections.directCapability(
                true, url, true, false, true, true));
        assertFalse(DBeaverConnections.directCapability(
                true, url, false, true, true, true));
        assertFalse(DBeaverConnections.directCapability(
                true, url, false, false, false, true));
        assertFalse(DBeaverConnections.directCapability(
                true, url, false, false, true, false));
    }

    @Test public void guidedOperationMovesQueuedAwaitingAndTerminalOnly() {
        DBeaverConnections.Operation operation = new DBeaverConnections.Operation(
                "10000000-0000-0000-0000-000000000001");
        assertEquals("queued", operation.snapshot().get("state"));
        assertEquals("create", operation.snapshot().get("kind"));
        assertTrue(DBeaverConnections.transitionToAwaitingInput(operation));
        assertEquals("awaiting_input", operation.snapshot().get("state"));
        assertFalse(DBeaverConnections.transitionToAwaitingInput(operation));

        DBeaverWorkspace.Identity identity = identity("profile-1", "Profile");
        DBeaverConnections.finish(operation, "saved", true, true,
                identity, null, null, null);
        Map<String, Object> snapshot = operation.snapshot();
        assertEquals("saved", snapshot.get("state"));
        assertEquals(Boolean.TRUE, snapshot.get("created"));
        assertEquals(Boolean.TRUE, snapshot.get("saved"));
        assertEquals("profile-1", ((Map<?, ?>) snapshot.get("datasource")).get("id"));
        assertFalse(DBeaverConnections.transitionToAwaitingInput(operation));
        DBeaverConnections.Operation cancelled = new DBeaverConnections.Operation(
                "10000000-0000-0000-0000-000000000003");
        assertTrue(DBeaverConnections.transitionToAwaitingInput(cancelled));
        DBeaverConnections.finish(cancelled, "cancelled", false, false,
                null, "cancelled", "connection wizard was closed", null);
        assertEquals("cancelled", cancelled.snapshot().get("state"));
        assertEquals(Boolean.FALSE, cancelled.snapshot().get("saved"));
    }

    @Test public void editOperationRetainsOriginalProfileFlags() {
        DBeaverConnections.Operation operation = new DBeaverConnections.Operation(
                "10000000-0000-0000-0000-000000000004", "edit", null,
                null, null, null, Collections.<String>emptySet(), 1L);
        DBeaverWorkspace.Identity identity = identity("profile-4", "Existing");
        operation.identity = identity;
        operation.saved = true;
        assertTrue(DBeaverConnections.transitionToAwaitingInput(operation));
        DBeaverConnections.finish(operation, "saved", false, true,
                identity, null, null, null);
        Map<String, Object> snapshot = operation.snapshot();
        assertEquals("edit", snapshot.get("kind"));
        assertEquals(Boolean.FALSE, snapshot.get("created"));
        assertEquals(Boolean.TRUE, snapshot.get("saved"));
        assertEquals("Existing",
                ((Map<?, ?>) snapshot.get("datasource")).get("name"));
    }
    @Test public void unknownConnectionOperationReturnsNotFound() {
        assertStatus(404, () -> DBeaverConnections.operation(
                "10000000-0000-0000-0000-000000000099"));
    }


    @Test public void failedOperationExposesCleanupResidualState() {
        DBeaverConnections.Operation operation = new DBeaverConnections.Operation(
                "10000000-0000-0000-0000-000000000002");
        assertTrue(DBeaverConnections.transitionToAwaitingInput(operation));
        DBeaverWorkspace.Identity identity = identity("profile-2", "Profile");
        DBeaverConnections.CleanupOutcome cleanup =
                new DBeaverConnections.CleanupOutcome(true, false, true, false);
        DBeaverConnections.finish(operation, "failed", true, false,
                identity, "persistence_failed", "safe failure", cleanup);
        Map<String, Object> snapshot = operation.snapshot();
        assertEquals("failed", snapshot.get("state"));
        assertEquals(Boolean.TRUE, snapshot.get("created"));
        assertEquals(Boolean.FALSE, snapshot.get("saved"));
        assertEquals("persistence_failed", snapshot.get("code"));
        Map<?, ?> cleanupResult = (Map<?, ?>) snapshot.get("cleanup");
        assertEquals(Boolean.TRUE, cleanupResult.get("attempted"));
        assertEquals(Boolean.FALSE, cleanupResult.get("completed"));
    }

    @Test public void directNameAndGeneratedIdCollisionsDoNotReplaceProfiles() throws Exception {
        InMemoryStore namedCollision = new InMemoryStore();
        namedCollision.names.add("Existing");
        assertStatus(409, () -> DBeaverConnections.registerDirect(
                namedCollision, "existing", null, null));
        assertEquals(0, namedCollision.createCount);

        InMemoryStore idCollision = new InMemoryStore();
        idCollision.ids.add("generated-id");
        assertStatus(409, () -> DBeaverConnections.registerDirect(
                idCollision, "New profile", null, null));
        assertEquals(0, idCollision.registered.size());
        assertEquals(1, idCollision.disposeCount);
    }

    @Test public void directPartialSaveFailureReportsCleanupAndResidualProfile()
            throws Exception {
        InMemoryStore cleaned = new InMemoryStore();
        cleaned.failNextCheck = true;
        DBeaverConnections.Result cleanedResult = DBeaverConnections.registerDirect(
                cleaned, "New profile", null, null);
        assertEquals(500, cleanedResult.status);
        assertEquals(Boolean.FALSE, cleanedResult.body.get("created"));
        assertEquals(Boolean.FALSE, cleanedResult.body.get("saved"));
        Map<?, ?> cleanup = (Map<?, ?>) cleanedResult.body.get("cleanup");
        assertEquals(Boolean.TRUE, cleanup.get("attempted"));
        assertEquals(Boolean.TRUE, cleanup.get("completed"));
        assertTrue(cleaned.registered.isEmpty());

        InMemoryStore residual = new InMemoryStore();
        residual.failNextCheck = true;
        residual.failRemove = true;
        DBeaverConnections.Result residualResult = DBeaverConnections.registerDirect(
                residual, "New profile", null, null);
        assertEquals(500, residualResult.status);
        assertEquals(Boolean.TRUE, residualResult.body.get("created"));
        assertEquals(Boolean.FALSE, residualResult.body.get("saved"));
        assertNotNull(residualResult.body.get("datasource"));
        Map<?, ?> residualCleanup = (Map<?, ?>) residualResult.body.get("cleanup");
        assertEquals(Boolean.TRUE, residualCleanup.get("attempted"));
        assertEquals(Boolean.FALSE, residualCleanup.get("completed"));
        assertEquals(1, residual.registered.size());
    }

    @Test public void directSuccessIsPersistedButExplicitlyDisconnected()
            throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.persistOnAdd = true;
        DBeaverConnections.Result result = DBeaverConnections.registerDirect(
                store, "New profile", null, null);
        assertEquals(201, result.status);
        assertEquals(Boolean.TRUE, result.body.get("created"));
        assertEquals(Boolean.TRUE, result.body.get("saved"));
        assertEquals(Boolean.FALSE, result.body.get("connected"));
        assertNotNull(result.body.get("datasource"));
    }

    @Test public void persistenceReadbackOnlyMatchesTargetProfileMetadata()
            throws Exception {
        DBeaverWorkspace.Identity expected = identity("target-id", "Target profile");
        String json = "{\"connections\":{"
                + "\"unrelated\":{\"provider\":\"other\",\"driver\":\"other\","
                + "\"name\":\"Other\",\"configuration\":{\"password\":\"not-read\"}},"
                + "\"target-id\":{\"provider\":\"postgresql\","
                + "\"driver\":\"postgres-jdbc\",\"name\":\"Target profile\","
                + "\"configuration\":{\"url\":\"jdbc:postgresql://db/catalog\","
                + "\"password\":\"not-read\"},\"credentials\":{\"password\":\"not-read\"}}},"
                + "\"auth-profiles\":{\"profile\":{\"password\":\"not-read\"}}}";
        assertTrue(ConnectionSettings.readPersisted(bytes(json), expected).matches);

        DBeaverWorkspace.Identity wrongDriver = new DBeaverWorkspace.Identity(
                expected.id, expected.name, expected.providerId, "another-driver",
                expected.projectId, false);
        assertFalse(ConnectionSettings.readPersisted(
                bytes(json), wrongDriver).matches);
    }

    @Test public void truncatedPersistenceCannotConfirmCreation() throws Exception {
        String truncated = "{\"connections\":{\"target-id\":{"
                + "\"provider\":\"postgresql\",\"driver\":\"postgres-jdbc\","
                + "\"name\":\"Target profile\"}";
        try {
            ConnectionSettings.readPersisted(
                    bytes(truncated), identity("target-id", "Target profile"));
            fail("truncated storage was accepted as persistence proof");
        } catch (java.io.IOException expected) {
            // A complete stored document, not an early matching entry, is required.
        }
    }

    private static DBeaverWorkspace.Identity identity(String id, String name) {
        return new DBeaverWorkspace.Identity(id, name, "postgresql",
                "postgres-jdbc", "project-1", false);
    }

    private static Map<String, Object> request(String mode) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("projectId", "project-1");
        request.put("mode", mode);
        request.put("providerId", "postgresql");
        request.put("driverId", "postgres-jdbc");
        return request;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertStatus(int status, ThrowingRunnable action) {
        try {
            action.run();
            fail("expected HTTP " + status);
        } catch (DataExportPlugin.HttpException error) {
            assertEquals(status, error.status);
            assertFalse(error.getMessage().contains("not-accepted"));
            assertFalse(error.getMessage().contains("private-value"));
            assertFalse(error.getMessage().contains("secret@"));
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class InMemoryStore implements DBeaverConnections.DirectStore {
        final Set<String> ids = new HashSet<String>();
        final Set<String> names = new HashSet<String>();
        final Set<String> persisted = new HashSet<String>();
        final Map<String, DBeaverConnections.Profile> registered = new HashMap<String, DBeaverConnections.Profile>();
        int createCount;
        int disposeCount;
        boolean failNextCheck;
        boolean failRemove;
        boolean persistOnAdd;

        @Override public Set<String> ids() { return new HashSet<String>(ids); }

        @Override public boolean nameExists(String name) {
            for (String current : names) {
                if (current.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        @Override public DBeaverConnections.Profile create(String name,
                Object driver, Object configuration) {
            createCount++;
            return new DBeaverConnections.Profile(new Object(),
                    new DBeaverWorkspace.Identity("generated-id", name,
                            "postgresql", "postgres-jdbc", "project-1", false));
        }

        @Override public void add(DBeaverConnections.Profile profile) {
            ids.add(profile.identity.id);
            names.add(profile.identity.name);
            registered.put(profile.identity.id, profile);
            if (persistOnAdd) persisted.add(profile.identity.id);
        }

        @Override public boolean isRegistered(DBeaverConnections.Profile profile) {
            return registered.get(profile.identity.id) == profile;
        }

        @Override public void checkForErrors() {
            if (failNextCheck) {
                failNextCheck = false;
                throw new IllegalStateException("simulated persistence failure");
            }
        }

        @Override public boolean isPersisted(DBeaverConnections.Profile profile) {
            return persisted.contains(profile.identity.id);
        }

        @Override public void remove(DBeaverConnections.Profile profile) {
            if (failRemove) throw new IllegalStateException("simulated cleanup failure");
            registered.remove(profile.identity.id);
            ids.remove(profile.identity.id);
            names.remove(profile.identity.name);
            persisted.remove(profile.identity.id);
        }

        @Override public void dispose(DBeaverConnections.Profile profile) {
            disposeCount++;
        }
    }
}
