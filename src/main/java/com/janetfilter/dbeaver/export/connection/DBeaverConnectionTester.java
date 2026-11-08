package com.janetfilter.dbeaver.export.connection;

import com.janetfilter.dbeaver.export.runtime.DBeaverRuntime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs DBeaver's native connection test against an owned temporary descriptor. */
final class DBeaverConnectionTester {
    private static final long CLEANUP_GRACE_MILLIS = 5000L;
    private static final long POLL_MILLIS = 50L;
    private static final java.util.regex.Pattern URL_STARTUP_OPTION =
            java.util.regex.Pattern.compile(
                    "(?i)(?:[?;&])(?:init|initsql|initquery|bootstrap|sessionvariables|beforeconnect|afterconnect|onconnect|startup|shell|command|script)\\s*=");

    private DBeaverConnectionTester() { }

    static int configuredTimeoutSeconds() {
        final int fallback = 30;
        final int maximum = 3600;
        String configured = System.getProperty(
                "janf.dataExport.connectionTestTimeoutSeconds");
        if (configured == null || configured.trim().isEmpty()) return fallback;
        try {
            long parsed = Long.parseLong(configured.trim());
            if (parsed < 1) return fallback;
            return (int) Math.min(parsed, maximum);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static void start(final DBeaverConnections.Operation operation) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ClassLoader original = Thread.currentThread().getContextClassLoader();
                try {
                    Object descriptor = operation.targetDescriptor;
                    if (descriptor != null) {
                        Thread.currentThread().setContextClassLoader(
                                descriptor.getClass().getClassLoader());
                    }
                    DBeaverConnectionTester.run(operation);
                } finally {
                    Thread.currentThread().setContextClassLoader(original);
                }
            }
        }, "dbeaver-connection-test-" + operation.id);
        worker.setDaemon(true);
        worker.setContextClassLoader(DBeaverRuntime.class.getClassLoader());
        worker.start();
    }

    private static void run(DBeaverConnections.Operation operation) {
        if (!DBeaverConnections.transitionToRunning(operation)) return;
        TestOutcome outcome = null;
        try {
            outcome = execute(operation.targetDescriptor,
                    operation.timeoutMillis);
            DBeaverConnections.finishTest(operation, outcome);
        } catch (Exception failure) {
            String classification = classify(failure);
            Map<String, Object> diagnostic = diagnostic(classification);
            Map<String, Object> test = new LinkedHashMap<String, Object>();
            test.put("passed", false);
            test.put("session", false);
            test.put("diagnosticCategory", classification);
            DBeaverConnections.finishTest(operation,
                    new TestOutcome(false, "cancelled".equals(classification),
                            diagnostic, test,
                            new DBeaverConnections.CleanupOutcome(
                                    false, false, true, false)));
        }
    }

    static TestOutcome execute(Object original, long timeoutMillis)
            throws Exception {
        if (original == null) {
            return failed("unavailable", "The selected connection is unavailable.",
                    false, null);
        }
        String restriction = ownershipRestriction(
                Boolean.TRUE.equals(DBeaverRuntime.call(
                        original, "isSharedCredentials"))
                        || Boolean.TRUE.equals(DBeaverRuntime.call(
                                original, "isSharedCredentialsSelected")),
                hasStartupHooks(original), hasDistributedHandler(original));
        if (restriction != null) {
            return failed(restriction, diagnosticMessage(restriction), false, null);
        }

        long started = System.nanoTime();
        long deadline = deadlineAfterMillis(timeoutMillis);
        Object registry = DBeaverRuntime.call(original, "getRegistry");
        if (registry == null) {
            return failed("unavailable", "The connection registry is unavailable.",
                    false, null);
        }
        Object temporary = null;
        Object job = null;
        AtomicBoolean sessionSeen = new AtomicBoolean(false);
        AtomicReference<String> sessionType = new AtomicReference<String>();
        boolean testSucceeded = false;
        boolean connectErrorAbsent = false;
        boolean nativeStatusOk = false;
        boolean timedOut = false;
        String failureClass = null;
        Throwable nativeFailure = null;
        DBeaverConnections.CleanupOutcome cleanup = null;
        try {
            Object copy = DBeaverRuntime.call(registry, "createDataSource",
                    DBeaverRuntime.arr(original));
            if (copy == null || copy == original) {
                throw new IllegalStateException("native temporary profile unavailable");
            }
            Object candidateId = DBeaverRuntime.call(copy, "getId");
            if (candidateId != null && DBeaverRuntime.call(registry, "getDataSource",
                    DBeaverRuntime.arr(candidateId)) == copy) {
                throw new IllegalStateException("native factory returned a registered profile");
            }
            temporary = copy;
            Object driver = DBeaverRuntime.call(temporary, "getDriver");
            Class<?> descriptorClass = temporary.getClass();
            String temporaryId = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                Object candidate = DBeaverRuntime.call(descriptorClass,
                        "generateNewId", DBeaverRuntime.arr(driver));
                if (!(candidate instanceof String) || ((String) candidate).isEmpty()) {
                    continue;
                }
                Object collision = DBeaverRuntime.call(registry, "getDataSource",
                        DBeaverRuntime.arr(candidate));
                if (collision == null) {
                    temporaryId = (String) candidate;
                    break;
                }
            }
            if (temporaryId == null) {
                throw new IllegalStateException("native temporary identity unavailable");
            }
            DBeaverRuntime.call(temporary, "setId",
                    DBeaverRuntime.arr(temporaryId));
            DBeaverRuntime.call(temporary, "setTemporary",
                    DBeaverRuntime.arr(Boolean.TRUE));
            if (!temporaryId.equals(DBeaverRuntime.call(temporary, "getId"))
                    || !Boolean.TRUE.equals(DBeaverRuntime.call(
                            temporary, "isTemporary"))
                    || DBeaverRuntime.call(registry, "getDataSource",
                            DBeaverRuntime.arr(temporaryId)) != null) {
                throw new IllegalStateException("native temporary ownership could not be confirmed");
            }

            Class<?> testJobClass = DBeaverConnections.uiClass(
                    "org.jkiss.dbeaver.runtime.jobs.ConnectionTestJob");
            Class<?> runnableType = Class.forName(
                    "org.jkiss.dbeaver.model.runtime.DBRRunnableParametrized",
                    true, testJobClass.getClassLoader());
            Object callback = Proxy.newProxyInstance(runnableType.getClassLoader(),
                    new Class<?>[]{runnableType}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method,
                                Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                return objectMethod(proxy, method, args);
                            }
                            if ("run".equals(method.getName())
                                    && args != null && args.length == 1
                                    && args[0] != null) {
                                sessionType.set(args[0].getClass().getName());
                                try {
                                    Class<?> session = Class.forName(
                                            "org.jkiss.dbeaver.model.exec.DBCSession",
                                            true, testJobClass.getClassLoader());
                                    sessionSeen.set(session.isInstance(args[0]));
                                } catch (Exception unavailable) {
                                    sessionSeen.set(false);
                                }
                            }
                            return null;
                        }
                    });
            job = DBeaverRuntime.construct(testJobClass, temporary, callback);
            Object monitor = DBeaverRuntime.createMonitorProxy(temporary, deadline);
            if (monitor == null) {
                throw new IllegalStateException("native connection monitor unavailable");
            }
            DBeaverRuntime.call(job, "setOwnerMonitor",
                    DBeaverRuntime.arr(monitor));
            DBeaverRuntime.call(job, "schedule");
            while (isScheduledOrRunning(job)) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    DBeaverRuntime.call(job, "cancel");
                    break;
                }
                Thread.sleep(POLL_MILLIS);
            }
            if (timedOut) waitUntilStopped(job, CLEANUP_GRACE_MILLIS);

            Object connectError = DBeaverRuntime.call(job, "getConnectError");
            Object status = DBeaverRuntime.call(job, "getConnectStatus");
            connectErrorAbsent = connectError == null;
            nativeStatusOk = status != null
                    && Boolean.TRUE.equals(DBeaverRuntime.call(status, "isOK"));
            boolean nativeCancelled = isCancelledStatus(status);
            if (timedOut || System.nanoTime() >= deadline) {
                timedOut = true;
                failureClass = "timeout";
            } else if (!connectErrorAbsent) {
                nativeFailure = (Throwable) connectError;
                failureClass = classify(nativeFailure);
            } else if (nativeCancelled) {
                failureClass = "cancelled";
            } else if (!nativeStatusOk) {
                failureClass = "connection_failed";
            } else if (!sessionSeen.get()) {
                failureClass = "session_unavailable";
            } else {
                testSucceeded = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timedOut = System.nanoTime() >= deadline;
            failureClass = timedOut ? "timeout" : "cancelled";
            if (job != null) {
                try { DBeaverRuntime.call(job, "cancel"); }
                catch (Exception ignored) { }
                waitUntilStopped(job, CLEANUP_GRACE_MILLIS);
            }
        } catch (Exception failure) {
            failureClass = classify(failure);
            nativeFailure = failure;
            if (job != null) {
                try { DBeaverRuntime.call(job, "cancel"); }
                catch (Exception ignored) { }
                waitUntilStopped(job, CLEANUP_GRACE_MILLIS);
            }
        } finally {
            if (temporary != null) {
                cleanup = cleanTemporary(registry, temporary, job,
                        deadlineAfterMillis(CLEANUP_GRACE_MILLIS));
            } else {
                cleanup = new DBeaverConnections.CleanupOutcome(
                        false, false, false, true);
            }
        }

        testSucceeded = testSucceeded && successProof(connectErrorAbsent,
                nativeStatusOk, sessionSeen.get(), !timedOut,
                cleanup != null && cleanup.completed);
        if (cleanup == null || !cleanup.completed) {
            testSucceeded = false;
            failureClass = "cleanup_failed";
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("passed", testSucceeded);
        result.put("session", sessionSeen.get());
        if (sessionType.get() != null) result.put("sessionType", sessionType.get());
        result.put("elapsedMillis", job == null ? elapsed
                : DBeaverRuntime.call(job, "getConnectTime"));
        result.put("totalElapsedMillis", elapsed);
        if (timedOut) result.put("timedOut", true);
        if (testSucceeded) {
            putMetadata(result, "server", job, "getProductName", "getProductVersion");
            putMetadata(result, "driver", job, "getDriverName", "getDriverVersion");
            return new TestOutcome(true, false, null, result, cleanup);
        }
        if (failureClass == null) failureClass = "connection_failed";
        Map<String, Object> safeDiagnostic = nativeFailure == null
                ? diagnostic(failureClass) : diagnose(nativeFailure);
        if (!failureClass.equals(safeDiagnostic.get("category"))) {
            safeDiagnostic = diagnostic(failureClass);
        }
        result.put("diagnosticCategory", failureClass);
        return new TestOutcome(false, "cancelled".equals(failureClass),
                safeDiagnostic, result, cleanup);
    }

    private static void putMetadata(Map<String, Object> result, String key,
            Object job, String nameMethod, String versionMethod) throws Exception {
        if (job == null) return;
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        Object name = DBeaverRuntime.call(job, nameMethod);
        Object version = DBeaverRuntime.call(job, versionMethod);
        if (name instanceof String && !((String) name).isEmpty()) metadata.put("name", name);
        if (version instanceof String && !((String) version).isEmpty()) metadata.put("version", version);
        if (!metadata.isEmpty()) result.put(key, metadata);
    }

    static boolean hasStartupHooks(Object descriptor) throws Exception {
        Object configuration = DBeaverRuntime.call(
                descriptor, "getConnectionConfiguration");
        if (configuration == null) return false;
        Object url = DBeaverRuntime.call(configuration, "getUrl");
        if (url instanceof String && URL_STARTUP_OPTION.matcher((String) url).find()) {
            return true;
        }
        Object bootstrap = DBeaverRuntime.call(configuration, "getBootstrap");
        Object queries = bootstrap == null ? null
                : DBeaverRuntime.call(bootstrap, "getInitQueries");
        if (queries instanceof Iterable && ((Iterable<?>) queries).iterator().hasNext()) {
            return true;
        }
        Object events = DBeaverRuntime.call(configuration, "getDeclaredEvents");
        if (events != null && events.getClass().isArray()) {
            for (int index = 0; index < java.lang.reflect.Array.getLength(events); index++) {
                Object event = java.lang.reflect.Array.get(events, index);
                Object command = DBeaverRuntime.call(configuration, "getEvent",
                        DBeaverRuntime.arr(event));
                if (command != null && Boolean.TRUE.equals(
                        DBeaverRuntime.call(command, "isEnabled"))) return true;
            }
        }
        return hasHookProperty(configuration, "getProperties")
                || hasHookProperty(configuration, "getProviderProperties");
    }

    private static boolean hasHookProperty(Object configuration, String method)
            throws Exception {
        Object properties = DBeaverRuntime.call(configuration, method);
        if (!(properties instanceof Map)) return false;
        for (Object key : ((Map<?, ?>) properties).keySet()) {
            if (!(key instanceof String)) continue;
            Object value = ((Map<?, ?>) properties).get(key);
            if (value == null || Boolean.FALSE.equals(value)
                    || value instanceof String && ((String) value).isEmpty()) continue;
            String name = ((String) key).toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "").replace(".", "");
            if (name.contains("initquery") || name.contains("initsql")
                    || name.contains("startup") || name.contains("bootstrap")
                    || name.contains("beforeconnect") || name.contains("afterconnect")
                    || name.contains("onconnect") || name.contains("sessionvariables")
                    || name.contains("shell") || name.contains("command") || name.contains("script")) {
                return true;
            }
        }
        return false;
    }

    static String ownershipRestriction(boolean sharedCredentials,
            boolean startupHooks, boolean distributedHandler) {
        if (startupHooks) return "startup_hooks";
        if (sharedCredentials || distributedHandler) return "interaction_required";
        return null;
    }

    static boolean successProof(boolean noConnectError, boolean nativeStatusOk,
            boolean nativeSessionSeen, boolean notTimedOut,
            boolean cleanupComplete) {
        return noConnectError && nativeStatusOk && nativeSessionSeen
                && notTimedOut && cleanupComplete;
    }

    static boolean hasDistributedHandler(Object descriptor) throws Exception {
        Object configuration = DBeaverRuntime.call(
                descriptor, "getConnectionConfiguration");
        Object handlers = configuration == null ? null
                : DBeaverRuntime.call(configuration, "getHandlers");
        if (!(handlers instanceof Iterable)) return false;
        for (Object handler : (Iterable<?>) handlers) {
            if (!Boolean.TRUE.equals(DBeaverRuntime.call(handler, "isEnabled"))) {
                continue;
            }
            Object handlerDescriptor = DBeaverRuntime.call(
                    handler, "getHandlerDescriptor");
            if (handlerDescriptor != null && Boolean.TRUE.equals(
                    DBeaverRuntime.call(handlerDescriptor, "isDistributed"))) {
                return true;
            }
        }
        return false;
    }

    static String classify(Throwable failure) {
        return (String) diagnose(failure).get("category");
    }

    static Map<String, Object> diagnose(Throwable failure) {
        String category = "provider_error";
        String code = "provider_error";
        String sqlState = null;
        Integer vendorCode = null;
        String exceptionType = null;
        String missingClass = null;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32;
                depth++, current = current.getCause()) {
            String type = current.getClass().getName().toLowerCase(Locale.ROOT);
            exceptionType = current.getClass().getName();
            Object state = exceptionFact(current, "getSQLState");
            Object number = exceptionFact(current, "getErrorCode");
            if (number == null) number = exceptionFact(current, "getCode");
            if (sqlState == null && state instanceof String
                    && ((String) state).matches("[A-Za-z0-9]{5}")) {
                sqlState = (String) state;
            }
            if (vendorCode == null && number instanceof Number) {
                vendorCode = ((Number) number).intValue();
            }
            String message = current.getMessage();
            if ((type.contains("classnotfound") || type.contains("noclassdeffound"))
                    && message != null) {
                String name = message.replace('/', '.');
                if (name.matches("(?:java|javax|org\\.jkiss|org\\.eclipse|com\\.dbeaver|com\\.mongodb)\\.[A-Za-z0-9_.$]+")) {
                    missingClass = name;
                }
            }
            String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (text.contains("mongos") && (text.contains("'local'")
                    || text.contains("local database") || text.contains("illegaloperation"))) {
                category = "provider_configuration";
                code = "mongo_local_database_through_mongos";
                break;
            }
            if (type.contains("ssl") || type.contains("certificate")
                    || type.contains("certpath") || text.contains("pkix path")) {
                category = "tls";
                code = "tls_validation_failed";
            } else if (type.contains("authenticationexception")
                    || text.contains("authentication failed") || text.contains("invalid credentials")
                    || text.contains("login failed") || text.contains("logon denied")
                    || sqlState != null && sqlState.startsWith("28")
                    || type.startsWith("com.mongodb.") && Integer.valueOf(18).equals(vendorCode)) {
                category = "authentication";
                code = "authentication_failed";
            } else if ("42501".equals(sqlState) || text.contains("permission denied")
                    || text.contains("not authorized") || text.contains("insufficient privilege")
                    || type.startsWith("com.mongodb.") && Integer.valueOf(13).equals(vendorCode)) {
                category = "authorization";
                code = "authorization_denied";
            } else if (type.contains("unknownhost")) {
                category = "network";
                code = "dns_lookup_failed";
            } else if (type.contains("sockettimeout") || type.contains("sqltimeout")
                    || text.contains("timed out")) {
                category = "timeout";
                code = "provider_timeout";
            } else if (type.contains("connectexception") || text.contains("connection refused")
                    || text.contains("network is unreachable")
                    || sqlState != null && sqlState.startsWith("08")) {
                category = "network";
                code = "connection_failed";
            } else if (text.contains("no suitable driver") || type.contains("classnotfound")
                    || type.contains("noclassdeffound") || text.contains("driver class")) {
                boolean nativeApi = missingClass != null
                        && (missingClass.startsWith("org.jkiss.")
                        || missingClass.startsWith("org.eclipse.")
                        || missingClass.startsWith("com.dbeaver.")
                        || missingClass.startsWith("java."));
                category = nativeApi ? "native_compatibility" : "driver";
                code = nativeApi ? "native_api_unavailable" : "driver_unavailable";
            } else if (type.contains("interrupted") || text.contains("cancel")) {
                category = "cancelled";
                code = "cancelled";
            } else if (text.contains("credential") && (text.contains("required")
                    || text.contains("missing") || text.contains("prompt"))) {
                category = "interaction_required";
                code = "native_credentials_required";
            } else if (text.contains("unsupported") || text.contains("invalid url")) {
                category = "provider_configuration";
                code = "configuration_unsupported";
            }
        }
        Map<String, Object> result = diagnostic(category);
        result.put("code", code);
        if (sqlState != null) result.put("sqlState", sqlState);
        if (vendorCode != null) result.put("vendorCode", vendorCode);
        if (exceptionType != null) result.put("exceptionType", exceptionType);
        if (missingClass != null) result.put("missingClass", missingClass);
        if ("mongo_local_database_through_mongos".equals(code)) {
            result.put("message", "MongoDB rejected the local default database through mongos. "
                    + "Choose an appropriate default database; authSource is separate. "
                    + "Review the native settings and retest.");
        }
        return result;
    }

    private static Object exceptionFact(Throwable failure, String method) {
        try {
            return DBeaverRuntime.call(failure, method);
        } catch (Exception unavailable) {
            return null;
        }
    }

    static Map<String, Object> diagnostic(String classification) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("category", classification);
        result.put("message", diagnosticMessage(classification));
        return result;
    }

    private static String diagnosticMessage(String classification) {
        if ("authentication".equals(classification)) {
            return "DBeaver's native connection test could not authenticate.";
        }
        if ("network".equals(classification)) {
            return "DBeaver's native connection test could not reach the configured endpoint.";
        }
        if ("timeout".equals(classification)) {
            return "DBeaver's native connection test exceeded its cooperative deadline.";
        }
        if ("tls".equals(classification)) {
            return "DBeaver's native connection test could not validate the TLS connection.";
        }
        if ("authorization".equals(classification)) {
            return "The configured account is not authorized for the native test operation.";
        }
        if ("provider_error".equals(classification)) {
            return "The native provider reported an unclassified failure; inspect DBeaver's native UI.";
        }
        if ("cancelled".equals(classification)) {
            return "DBeaver's native connection test was cancelled.";
        }
        if ("driver".equals(classification)) {
            return "The enabled native driver could not establish a connection.";
        }
        if ("native_compatibility".equals(classification)) {
            return "The native DBeaver API is unavailable in the current classloader or version.";
        }
        if ("interaction_required".equals(classification)) {
            return "This connection requires interaction in the native DBeaver UI.";
        }
        if ("startup_hooks".equals(classification)) {
            return "The connection has startup SQL or shell hooks; the test was not run.";
        }
        if ("cleanup_failed".equals(classification)) {
            return "DBeaver could not confirm cleanup of the temporary test connection.";
        }
        if ("session_unavailable".equals(classification)) {
            return "DBeaver did not provide a native database session to the test callback.";
        }
        if ("unavailable".equals(classification)) {
            return "The selected native connection is unavailable.";
        }
        return "DBeaver's native connection test could not establish the requested connection configuration.";
    }

    private static TestOutcome failed(String classification, String message,
            boolean cancelled, DBeaverConnections.CleanupOutcome cleanup) {
        Map<String, Object> detail = diagnostic(classification);
        if (message != null) detail.put("message", message);
        Map<String, Object> test = new LinkedHashMap<String, Object>();
        test.put("passed", false);
        test.put("session", false);
        test.put("diagnosticCategory", classification);
        return new TestOutcome(false, cancelled, detail, test, cleanup);
    }

    private static boolean isCancelledStatus(Object status) {
        if (status == null) return false;
        try {
            return Integer.valueOf(8).equals(DBeaverRuntime.call(status, "getSeverity"));
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static boolean isScheduledOrRunning(Object job) throws Exception {
        int state = ((Number) DBeaverRuntime.call(job, "getState")).intValue();
        return state == 1 || state == 2 || state == 4;
    }

    private static void waitUntilStopped(Object job, long maximumMillis) {
        long deadline = deadlineAfterMillis(maximumMillis);
        while (System.nanoTime() < deadline) {
            try {
                if (!isScheduledOrRunning(job)) return;
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unavailable) {
                return;
            }
        }
    }

    private static DBeaverConnections.CleanupOutcome cleanTemporary(
            Object registry, Object temporary, Object testJob, long deadline) {
        boolean completed = true;
        try {
            Object jobManager = DBeaverRuntime.call(
                    DBeaverConnections.uiClass("org.eclipse.core.runtime.jobs.Job"),
                    "getJobManager");
            // A cooperative deadline cannot safely dispose a descriptor still owned by a native job.
            // Keep this worker responsible until that job relinquishes the descriptor.
            while (testJob != null && isScheduledOrRunning(testJob)) {
                Thread.sleep(POLL_MILLIS);
            }
            while (System.nanoTime() < deadline) {
                boolean connecting = Boolean.TRUE.equals(
                        DBeaverRuntime.call(temporary, "isConnecting"));
                boolean connected = Boolean.TRUE.equals(
                        DBeaverRuntime.call(temporary, "isConnected"));
                boolean testStillRunning = testJob != null
                        && isScheduledOrRunning(testJob);
                boolean disconnectPending = hasPendingNativeJobs(
                        jobManager, temporary, testJob);
                if (!connecting && !connected && !testStillRunning
                        && !disconnectPending) break;
                if (connected && !connecting && !testStillRunning
                        && !disconnectPending) {
                    Object monitor = DBeaverRuntime.createMonitorProxy(
                            temporary, deadline);
                    if (monitor == null) {
                        completed = false;
                        break;
                    }
                    DBeaverRuntime.call(temporary, "disconnect",
                            DBeaverRuntime.arr(monitor));
                }
                Thread.sleep(POLL_MILLIS);
            }
            while (Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isConnecting"))
                    || hasPendingNativeJobs(jobManager, temporary, testJob)) {
                Thread.sleep(POLL_MILLIS);
            }
            if (Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isConnected"))) {
                Object monitor = DBeaverRuntime.createMonitorProxy(
                        temporary, deadlineAfterMillis(CLEANUP_GRACE_MILLIS));
                if (monitor == null) completed = false;
                else DBeaverRuntime.call(temporary, "disconnect", DBeaverRuntime.arr(monitor));
            }
            if (Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isConnected"))) {
                completed = false;
            }
            String id = (String) DBeaverRuntime.call(temporary, "getId");
            Object registered = DBeaverRuntime.call(registry, "getDataSource",
                    DBeaverRuntime.arr(id));
            if (registered == temporary) {
                DBeaverRuntime.call(registry, "removeDataSource",
                        DBeaverRuntime.arr(temporary));
                DBeaverRuntime.call(registry, "checkForErrors");
            } else if (registered != null) {
                completed = false;
            }
            if (DBeaverRuntime.call(registry, "getDataSource",
                    DBeaverRuntime.arr(id)) != null) completed = false;
            if (!Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isDisposed"))) {
                DBeaverRuntime.call(temporary, "dispose");
            }
            if (!Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isDisposed"))) {
                completed = false;
            }
            return new DBeaverConnections.CleanupOutcome(false, false, true, completed);
        } catch (Exception failure) {
            try {
                if (!Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isConnecting"))
                        && !Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isConnected"))
                        && !Boolean.TRUE.equals(DBeaverRuntime.call(temporary, "isDisposed"))) {
                    DBeaverRuntime.call(temporary, "dispose");
                }
            } catch (Exception ignored) { }
            return new DBeaverConnections.CleanupOutcome(false, false, true, false);
        }
    }

    private static boolean hasPendingNativeJobs(Object manager, Object container,
            Object testJob) throws Exception {
        Object jobs = DBeaverRuntime.call(manager, "find",
                DBeaverRuntime.arr(container));
        if (jobs == null || !jobs.getClass().isArray()) return false;
        for (int index = 0; index < java.lang.reflect.Array.getLength(jobs); index++) {
            Object job = java.lang.reflect.Array.get(jobs, index);
            if (job == testJob) continue;
            if (((Number) DBeaverRuntime.call(job, "getState")).intValue() != 0) {
                return true;
            }
        }
        return false;
    }

    private static long deadlineAfterMillis(long millis) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, millis));
        return Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("equals".equals(method.getName())) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("toString".equals(method.getName())) return "DBeaverTestCallback";
        return null;
    }

    static final class TestOutcome {
        final boolean passed, cancelled;
        final Map<String, Object> diagnostic, test;
        final DBeaverConnections.CleanupOutcome cleanup;
        TestOutcome(boolean passed, boolean cancelled,
                Map<String, Object> diagnostic, Map<String, Object> test,
                DBeaverConnections.CleanupOutcome cleanup) {
            this.passed = passed;
            this.cancelled = cancelled;
            this.diagnostic = diagnostic;
            this.test = test;
            this.cleanup = cleanup;
        }
    }
}
