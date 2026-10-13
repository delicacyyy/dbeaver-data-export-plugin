package com.janetfilter.dbeaver.export;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DBeaverConnectionTesterTest {
    @Test public void borrowedFactoryResultCannotBecomeADeletableTestDescriptor()
            throws Exception {
        NativeRegistry registry = new NativeRegistry();
        NativeProfile original = new NativeProfile(registry, "original");
        NativeProfile other = new NativeProfile(registry, "other");
        registry.profiles.put(original.id, original);
        registry.profiles.put(other.id, other);
        for (NativeProfile candidate : new NativeProfile[] {original, other}) {
            registry.factoryResult = candidate;
            DBeaverConnectionTester.TestOutcome result =
                    DBeaverConnectionTester.execute(original, 1000);
            assertFalse(result.passed);
            assertFalse(result.cleanup.attempted);
            assertTrue(result.cleanup.completed);
            assertTrue(original.connected);
            assertFalse(original.disposed);
            assertTrue(other.connected);
            assertFalse(other.disposed);
            assertEquals(2, registry.profiles.size());
        }
    }

    @Test public void diagnosticFactsDistinguishAuthenticationTlsAndUnknownErrors()
            throws Exception {
        Map<String, Object> authentication = DBeaverConnectionTester.diagnose(
                new java.sql.SQLException("PRIVATE_SENTINEL", "28P01", 18456));
        assertEquals("authentication", authentication.get("category"));
        assertEquals("28P01", authentication.get("sqlState"));
        assertEquals(18456, authentication.get("vendorCode"));
        assertFalse(authentication.toString().contains("PRIVATE_SENTINEL"));
        assertEquals("tls", DBeaverConnectionTester.classify(
                new javax.net.ssl.SSLHandshakeException("PRIVATE_SENTINEL")));
        assertEquals("provider_error", DBeaverConnectionTester.classify(
                new IllegalStateException("PRIVATE_SENTINEL")));
        Map<String, Object> mongos = DBeaverConnectionTester.diagnose(
                new IllegalStateException("Command failed with error 20 (IllegalOperation): "
                        + "Can't use 'local' database through mongos"));
        assertEquals("provider_configuration", mongos.get("category"));
        assertEquals("mongo_local_database_through_mongos", mongos.get("code"));
    }

    @Test public void nativeUrlStartupSqlIsDetectedBeforeConnecting() throws Exception {
        NativeProfile profile = new NativeProfile(new NativeRegistry(), "profile");
        profile.configuration.url = "jdbc:h2:mem:test;INIT=CREATE TABLE demo(id INTEGER)";
        assertTrue(DBeaverConnectionTester.hasStartupHooks(profile));
        profile.configuration.url = "jdbc:sqlite:test.db";
        assertFalse(DBeaverConnectionTester.hasStartupHooks(profile));
    }

    @Test public void terminalFailureCannotBeOverwrittenByLateSuccess() {
        DBeaverConnections.Operation operation = operation("test");
        operation.identity = identity();
        DBeaverConnections.finishTest(operation, new DBeaverConnectionTester.TestOutcome(
                false, false, DBeaverConnectionTester.diagnostic("timeout"),
                Collections.<String, Object>singletonMap("passed", false), null));
        DBeaverConnections.finishTest(operation, new DBeaverConnectionTester.TestOutcome(
                true, false, null,
                Collections.<String, Object>singletonMap("passed", true), null));
        assertEquals("failed", operation.snapshot().get("state"));
        assertEquals(Boolean.FALSE,
                ((Map<?, ?>) operation.snapshot().get("test")).get("passed"));
        assertEquals("timeout",
                ((Map<?, ?>) operation.snapshot().get("diagnostic")).get("category"));
    }

    @Test public void successfulTestRequiresNativeStatusSessionAndCleanup() {
        assertTrue(DBeaverConnectionTester.successProof(
                true, true, true, true, true));
        assertFalse(DBeaverConnectionTester.successProof(
                false, true, true, true, true));
        assertFalse(DBeaverConnectionTester.successProof(
                true, false, true, true, true));
        assertFalse(DBeaverConnectionTester.successProof(
                true, true, false, true, true));
        assertFalse(DBeaverConnectionTester.successProof(
                true, true, true, false, true));
        assertFalse(DBeaverConnectionTester.successProof(
                true, true, true, true, false));
    }

    @Test public void ownershipRestrictionsKeepNativeHooksAndNetworkHandlersHonest() {
        assertEquals("startup_hooks", DBeaverConnectionTester.ownershipRestriction(
                false, true, false));
        assertEquals("interaction_required", DBeaverConnectionTester.ownershipRestriction(
                true, false, false));
        assertEquals("interaction_required", DBeaverConnectionTester.ownershipRestriction(
                false, false, true));
        assertEquals(null, DBeaverConnectionTester.ownershipRestriction(
                false, false, false));
    }

    @Test public void classifiesMongosLocalDatabaseAsProviderConfiguration() {
        assertEquals("provider_configuration", DBeaverConnectionTester.classify(
                new IllegalStateException(
                        "MongoServerError 20 IllegalOperation: Cannot use local database through mongos")));
    }

    @Test public void authenticationClassificationDoesNotMatchAuthSourceSetting() {
        assertEquals("authentication", DBeaverConnectionTester.classify(
                new IllegalStateException("Authentication failed for supplied credentials")));
        assertEquals("provider_configuration", DBeaverConnectionTester.classify(
                new IllegalArgumentException("Unsupported value authSource=admin")));
    }

    @Test public void operationReportsSessionResultAndOriginalProfileFlags() {
        DBeaverWorkspace.Identity identity = identity();
        DBeaverConnections.Operation passed = operation("test");
        passed.identity = identity;
        passed.saved = false;
        Map<String, Object> testResult = new LinkedHashMap<String, Object>();
        testResult.put("passed", true);
        testResult.put("session", true);
        DBeaverConnections.finishTest(passed,
                new DBeaverConnectionTester.TestOutcome(true, false, null,
                        testResult, new DBeaverConnections.CleanupOutcome(
                                false, false, true, true)));
        Map<String, Object> passedSnapshot = passed.snapshot();
        assertEquals("succeeded", passedSnapshot.get("state"));
        assertEquals(Boolean.FALSE, passedSnapshot.get("created"));
        assertEquals(Boolean.FALSE, passedSnapshot.get("saved"));
        assertEquals(Boolean.FALSE, passedSnapshot.get("connected"));
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) passedSnapshot.get("test")).get("passed"));
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) passedSnapshot.get("cleanup")).get("completed"));

        DBeaverConnections.Operation cleanupFailure = operation("test");
        cleanupFailure.identity = identity;
        cleanupFailure.saved = true;
        Map<String, Object> failedResult = new LinkedHashMap<String, Object>();
        failedResult.put("passed", false);
        failedResult.put("session", true);
        Map<String, Object> diagnostic = DBeaverConnectionTester.diagnostic(
                "cleanup_failed");
        DBeaverConnections.finishTest(cleanupFailure,
                new DBeaverConnectionTester.TestOutcome(false, false, diagnostic,
                        failedResult, new DBeaverConnections.CleanupOutcome(
                                false, false, true, false)));
        Map<String, Object> failedSnapshot = cleanupFailure.snapshot();
        assertEquals("failed", failedSnapshot.get("state"));
        assertEquals(Boolean.FALSE, failedSnapshot.get("created"));
        assertEquals(Boolean.TRUE, failedSnapshot.get("saved"));
        assertEquals("cleanup_failed",
                ((Map<?, ?>) failedSnapshot.get("diagnostic")).get("category"));
        assertEquals(Boolean.FALSE,
                ((Map<?, ?>) failedSnapshot.get("cleanup")).get("completed"));

        DBeaverConnections.Operation cancelled = operation("test");
        cancelled.identity = identity;
        cancelled.saved = true;
        Map<String, Object> cancelledResult = new LinkedHashMap<String, Object>();
        cancelledResult.put("passed", false);
        cancelledResult.put("session", false);
        DBeaverConnections.finishTest(cancelled,
                new DBeaverConnectionTester.TestOutcome(false, true,
                        DBeaverConnectionTester.diagnostic("cancelled"),
                        cancelledResult, new DBeaverConnections.CleanupOutcome(
                                false, false, true, true)));
        assertEquals("cancelled", cancelled.snapshot().get("state"));
        assertEquals(Boolean.TRUE, cancelled.snapshot().get("saved"));
    }

    private static DBeaverConnections.Operation operation(String kind) {
        return new DBeaverConnections.Operation(
                "10000000-0000-0000-0000-000000000010", kind,
                null, null, null, null, Collections.<String>emptySet(), 1L);
    }

    private static DBeaverWorkspace.Identity identity() {
        return new DBeaverWorkspace.Identity(
                "profile-test", "Test profile", "postgresql",
                "postgres-jdbc", "project-1", false);
    }

    public static final class NativeRegistry {
        final Map<String, NativeProfile> profiles = new LinkedHashMap<String, NativeProfile>();
        NativeProfile factoryResult;
        public NativeProfile createDataSource(Object original) { return factoryResult; }
        public NativeProfile getDataSource(String id) { return profiles.get(id); }
    }

    public static final class NativeProfile {
        final NativeRegistry registry;
        final String id;
        final NativeConfiguration configuration = new NativeConfiguration();
        boolean connected = true, disposed;
        NativeProfile(NativeRegistry registry, String id) { this.registry = registry; this.id = id; }
        public NativeRegistry getRegistry() { return registry; }
        public String getId() { return id; }
        public boolean isSharedCredentials() { return false; }
        public boolean isSharedCredentialsSelected() { return false; }
        public NativeConfiguration getConnectionConfiguration() { return configuration; }
    }

    public static final class NativeConfiguration {
        String url = "jdbc:sqlite:test.db";
        public String getUrl() { return url; }
        public Object getBootstrap() { return null; }
        public Object[] getDeclaredEvents() { return new Object[0]; }
        public Map<String, String> getProperties() { return Collections.emptyMap(); }
        public Map<String, String> getProviderProperties() { return Collections.emptyMap(); }
        public java.util.List<Object> getHandlers() { return Collections.emptyList(); }
    }
}
