package com.janetfilter.dbeaver.export;
import static com.janetfilter.dbeaver.export.DataExportPlugin.call;


import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Runtime-backed connection discovery and creation for the DBeaver UI/API. */
final class DBeaverConnections {
    private static final int MAX_OPERATIONS = 64;
    private static final long OPERATION_RETENTION_MILLIS = 60L * 60L * 1000L;
    private static final long QUEUED_TIMEOUT_MILLIS = 30L * 1000L;
    private static final int MAX_SUBSTITUTION_POLLS = 20;
    private static final int SUBSTITUTION_POLL_DELAY_MILLIS = 250;
    private static final Object OPERATION_LOCK = new Object();
    private static final LinkedHashMap<String, Operation> OPERATIONS =
            new LinkedHashMap<String, Operation>();
    private static String activeOperationId;
    private static volatile Field nativeDialogInstance;

    private DBeaverConnections() { }

    static Object drivers(Map<String, String> query) throws Exception {
        if (query == null) query = Collections.emptyMap();
        String projectId = query.get("projectId");
        if (!validId(projectId)) {
            throw badRequest("projectId is required");
        }
        String providerId = query.get("providerId");
        String driverId = query.get("driverId");
        if ((providerId == null) != (driverId == null)) {
            throw badRequest("providerId and driverId must be supplied together");
        }
        if (providerId != null
                && (!validId(providerId) || !validId(driverId))) {
            throw badRequest("providerId and driverId have invalid syntax");
        }

        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(projectId);
        boolean directWritable = DBeaverWorkspace.writable(project);
        List<Object> enabled = enabledDrivers();
        List<Object> items = new ArrayList<Object>();
        boolean selected = providerId == null;
        for (Object driver : enabled) {
            String currentProviderId = string(call(driver, "getProviderId"));
            String currentDriverId = string(call(driver, "getId"));
            if (providerId != null
                    && (!providerId.equals(currentProviderId)
                    || !driverId.equals(currentDriverId))) {
                continue;
            }
            selected = true;
            items.add(driverMetadata(driver, directWritable));
        }
        if (!selected) throw new DataExportPlugin.HttpException(
                404, "enabled driver not found");

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("project", DBeaverWorkspace.projectMetadata(project));
        return result;
    }

    static Result create(Map<String, Object> request) throws Exception {
        CreationRequest spec = parseRequest(request);
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(spec.projectId);
        if (!DBeaverWorkspace.writable(project)) {
            throw new DataExportPlugin.HttpException(
                    503, "data source storage is read-only");
        }
        if ("guided".equals(spec.mode)) assertNoNativeWizard();
        Object driver = findEnabledDriver(spec.providerId, spec.driverId);
        DriverInfo info = driverInfo(driver);
        validateConnection(spec, info, driver);

        if ("direct".equals(spec.mode)) {
            if (!info.directCapable) {
                throw unsupported("this driver does not support validated direct URL registration; choose guided mode");
            }
            synchronized (project.registry) {
                NativeDirectStore store = new NativeDirectStore(project);
                return registerDirect(store, spec.name, driver,
                        directConfiguration(driver, spec, info));
            }
        }

        if (spec.name != null && nameExists(project.registry, spec.name)) {
            throw new DataExportPlugin.HttpException(
                    409, "a data source with that name already exists");
        }
        Object configuration = guidedConfiguration(driver, spec, info);
        Operation operation = enqueue(project, driver, spec, configuration);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("operationId", operation.id);
        body.put("kind", "create");
        body.put("state", "queued");
        return new Result(202, body);
    }

    static Map<String, Object> inspect(String projectId, String datasourceId)
            throws Exception {
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(projectId);
        Object descriptor = DBeaverWorkspace.requireDatasource(project, datasourceId);
        DBeaverWorkspace.Identity identity = DBeaverWorkspace.identity(descriptor);
        Profile profile = new Profile(descriptor, identity);
        boolean saved = new NativeDirectStore(project).isPersisted(profile);
        Object driver = DataExportPlugin.call(descriptor, "getDriver");
        Object configuration = DataExportPlugin.call(
                descriptor, "getConnectionConfiguration");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("datasource", identity.asMap());
        result.put("saved", saved);
        result.put("connected", identity.connected);
        result.put("configuration",
                ConnectionSettings.snapshot(driver, configuration));
        return result;
    }

    static Result edit(String projectId, String datasourceId,
            Map<String, Object> request) throws Exception {
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(projectId);
        Object descriptor = DBeaverWorkspace.requireDatasource(project, datasourceId);
        if (!DBeaverWorkspace.writable(project)) {
            throw new DataExportPlugin.HttpException(
                    503, "data source storage is read-only");
        }
        DBeaverWorkspace.Identity identity = DBeaverWorkspace.identity(descriptor);
        EditRequest spec = parseEditRequest(request, identity.providerId);
        Object driver = DataExportPlugin.call(descriptor, "getDriver");
        if (!spec.connection.isEmpty()) {
            ConnectionSettings.validateForDriver(driver, spec.connection);
        }
        if (spec.name != null
                && nameExistsExcept(project.registry, spec.name, identity.id)) {
            throw new DataExportPlugin.HttpException(
                    409, "a data source with that name already exists");
        }
        Profile profile = new Profile(descriptor, identity);
        boolean saved = new NativeDirectStore(project).isPersisted(profile);
        Operation operation = enqueueEdit(project, descriptor, identity,
                spec, saved);
        return queued(operation);
    }

    static Result test(String projectId, String datasourceId,
            Map<String, Object> request) throws Exception {
        int timeoutSeconds = parseTestTimeout(request);
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(projectId);
        Object descriptor = DBeaverWorkspace.requireDatasource(project, datasourceId);
        DBeaverWorkspace.Identity identity = DBeaverWorkspace.identity(descriptor);
        boolean saved = new NativeDirectStore(project).isPersisted(
                new Profile(descriptor, identity));
        Operation operation = enqueueTest(project, descriptor, identity,
                saved, TimeUnit.SECONDS.toMillis(timeoutSeconds));
        return queued(operation);
    }

    private static Result queued(Operation operation) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("operationId", operation.id);
        body.put("kind", operation.kind);
        body.put("state", "queued");
        return new Result(202, body);
    }

    private static EditRequest parseEditRequest(Map<String, Object> request,
            String providerId) {
        if (request == null) throw badRequest("edit request must be an object");
        requireKeys(request, "name", "connection");
        String name = optionalString(request, "name");
        if (name != null && !validName(name)) {
            throw badRequest("name must be non-empty, at most 128 characters, and contain no controls");
        }
        Map<String, Object> connection = Collections.emptyMap();
        if (request.containsKey("connection")) {
            Object raw = request.get("connection");
            if (!(raw instanceof Map)) {
                throw badRequest("connection must be an object");
            }
            connection = ConnectionSettings.parse(providerId, raw);
        }
        return new EditRequest(name, connection);
    }

    private static int parseTestTimeout(Map<String, Object> request) {
        if (request == null) throw badRequest("test request must be an object");
        requireKeys(request, "timeout");
        int maximum = DBeaverConnectionTester.configuredTimeoutSeconds();
        if (!request.containsKey("timeout")) return maximum;
        Object raw = request.get("timeout");
        if (!(raw instanceof Number)) {
            throw badRequest("timeout must be a positive integer number of seconds");
        }
        final int timeout;
        try {
            java.math.BigDecimal number = raw instanceof java.math.BigDecimal
                    ? (java.math.BigDecimal) raw
                    : new java.math.BigDecimal(raw.toString());
            timeout = number.intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw badRequest("timeout must be a positive integer number of seconds");
        }
        if (timeout < 1 || timeout > maximum) {
            throw badRequest("timeout must be positive and no greater than the configured maximum");
        }
        return timeout;
    }

    private static boolean nameExistsExcept(Object registry, String name,
            String excludedId) throws Exception {
        if (name == null) return false;
        Object dataSources = DataExportPlugin.call(registry, "getDataSources");
        if (!(dataSources instanceof Iterable)) {
            throw new DataExportPlugin.HttpException(
                    503, "data source registry is unavailable");
        }
        for (Object dataSource : (Iterable<?>) dataSources) {
            String id = string(DataExportPlugin.call(dataSource, "getId"));
            String currentName = string(DataExportPlugin.call(dataSource, "getName"));
            if (!excludedId.equals(id) && currentName != null
                    && name.equalsIgnoreCase(currentName)) return true;
        }
        return false;
    }

    static Map<String, Object> operation(String id) throws Exception {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new DataExportPlugin.HttpException(
                    404, "connection operation not found");
        }
        synchronized (OPERATION_LOCK) {
            pruneOperations(System.currentTimeMillis());
            Operation operation = OPERATIONS.get(id);
            if (operation == null) {
                throw new DataExportPlugin.HttpException(
                        404, "connection operation not found");
            }
            return operation.snapshot();
        }
    }


    static CreationRequest parseRequest(Map<String, Object> request) {
        if (request == null) throw badRequest("creation request must be an object");
        requireKeys(request, "projectId", "mode", "providerId", "driverId",
                "name", "connection");
        String projectId = requiredString(request, "projectId");
        if (!validId(projectId)) throw badRequest("projectId has invalid syntax");
        String mode = requiredString(request, "mode");
        if (!"guided".equals(mode) && !"direct".equals(mode)) {
            throw badRequest("mode must be guided or direct");
        }
        String providerId = requiredString(request, "providerId");
        String driverId = requiredString(request, "driverId");
        if (!validId(providerId) || !validId(driverId)) {
            throw badRequest("providerId and driverId have invalid syntax");
        }
        String name = optionalString(request, "name");
        if (name != null && !validName(name)) {
            throw badRequest("name must be non-empty, at most 128 characters, and contain no controls");
        }
        if ("direct".equals(mode) && name == null) {
            throw badRequest("name is required for direct creation");
        }
        Map<String, Object> connection;
        if (request.containsKey("connection")) {
            Object supplied = request.get("connection");
            if (!(supplied instanceof Map)) {
                throw badRequest("connection must be an object");
            }
            connection = ConnectionSettings.parse(providerId, supplied);
        } else {
            connection = Collections.emptyMap();
        }
        if ("direct".equals(mode)) {
            Object url = connection.get("url");
            if (!(url instanceof String) || ((String) url).trim().isEmpty()) {
                throw unsupported("direct creation requires a non-empty JDBC URL; choose guided mode");
            }
            if (!((String) url).regionMatches(true, 0, "jdbc:", 0, 5)) {
                throw unsupported("direct creation requires a JDBC URL; choose guided mode");
            }
            for (String key : connection.keySet()) {
                if (!"url".equals(key) && !"properties".equals(key)) {
                    throw unsupported("direct creation accepts only url and validated properties; choose guided mode");
                }
            }
        }
        return new CreationRequest(projectId, mode, providerId, driverId, name,
                connection);
    }


    private static Map<String, Object> driverMetadata(Object driver,
            boolean directWritable) throws Exception {
        DriverInfo info = driverInfo(driver);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String providerId = string(call(driver, "getProviderId"));
        item.put("providerId", providerId);
        item.put("driverId", call(driver, "getId"));
        item.put("name", call(driver, "getName"));
        item.put("defaultPort", call(driver, "getDefaultPort"));
        item.put("configurationTypes", info.configurationTypes);
        item.put("supportedFields", info.supportedFields);
        List<String> modes = new ArrayList<String>();
        modes.add("guided");
        if (directWritable && info.directCapable) modes.add("direct");
        item.put("creationModes", modes);
        item.put("mainProperties", propertyDescriptors(
                call(driver, "getMainPropertyDescriptors"), providerId));
        item.put("providerProperties", propertyDescriptors(
                call(driver, "getProviderPropertyDescriptors"), providerId));
        return item;
    }

    private static List<Map<String, Object>> propertyDescriptors(Object array,
            String providerId) throws Exception {
        List<Map<String, Object>> descriptors = new ArrayList<Map<String, Object>>();
        if (array == null) return descriptors;
        for (int i = 0; i < Array.getLength(array); i++) {
            Object descriptor = Array.get(array, i);
            String id = string(call(descriptor, "getId"));
            if (id == null || id.isEmpty()
                    || !ConnectionSettings.safePropertyName(providerId, id)
                    || sensitiveDescriptor(descriptor)) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", id);
            item.put("label", call(descriptor, "getDisplayName"));
            Object dataType = call(descriptor, "getDataType");
            item.put("type", dataType instanceof Class
                    ? ((Class<?>) dataType).getName() : null);
            item.put("required", call(descriptor, "isRequired"));
            Object defaultValue = call(descriptor, "getDefaultValue");
            if (defaultValue instanceof Number || defaultValue instanceof Boolean) {
                item.put("default", defaultValue);
            }
            descriptors.add(item);
        }
        return descriptors;
    }

    private static boolean sensitiveDescriptor(Object descriptor)
            throws Exception {
        Object features = call(descriptor, "getFeatures");
        if (features != null && features.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(features); i++) {
                Object feature = Array.get(features, i);
                if (feature != null && sensitiveFeature(feature.toString())) return true;
            }
        }
        for (String feature : Arrays.asList("secret", "sensitive", "password",
                "credential", "auth", "hidden")) {
            if (Boolean.TRUE.equals(call(descriptor, "hasFeature", arr(feature)))) {
                return true;
            }
        }
        try {
            if (Boolean.TRUE.equals(call(descriptor, "isHidden"))) return true;
        } catch (NoSuchMethodException ignored) {
            // Older compatible DBeaver descriptors may not expose isHidden.
        }
        return false;
    }

    private static boolean sensitiveFeature(String feature) {
        String lower = feature.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("secret") || lower.contains("sensitive")
                || lower.contains("password") || lower.contains("credential")
                || lower.contains("auth") || lower.contains("hidden");
    }

    private static DriverInfo driverInfo(Object driver) throws Exception {
        Object types = call(driver, "getSupportedConfigurationTypes");
        Set<String> typeNames = new LinkedHashSet<String>();
        Map<String, Object> typeValues = new HashMap<String, Object>();
        if (types instanceof Iterable) {
            for (Object type : (Iterable<?>) types) {
                if (type == null) continue;
                String name = type.toString();
                typeNames.add(name);
                typeValues.put(name, type);
            }
        }
        Object rawFields = call(driver, "getSupportedPageFields");
        Set<String> fields = new LinkedHashSet<String>();
        if (rawFields instanceof Iterable) {
            for (Object field : (Iterable<?>) rawFields) {
                if (field != null) fields.add(field.toString());
            }
        }
        if (typeNames.contains("URL")) fields.add("url");
        Object provider = call(driver, "getDataSourceProvider");
        boolean jdbcProvider = false;
        if (provider != null) {
            try {
                Class<?> jdbcProviderClass = DataExportPlugin.providerClass(
                        provider, "org.jkiss.dbeaver.model.impl.jdbc.JDBCDataSourceProvider");
                jdbcProvider = jdbcProviderClass.isInstance(provider);
            } catch (ClassNotFoundException unavailable) {
                jdbcProvider = false;
            }
        }
        boolean capable = directCapability(jdbcProvider, typeValues.keySet(),
                Boolean.TRUE.equals(call(driver, "isDisabled")),
                Boolean.TRUE.equals(call(driver, "isInternalDriver")),
                Boolean.TRUE.equals(call(driver, "isInstantiable")),
                Boolean.TRUE.equals(call(driver, "isSupportedByLocalSystem")))
                && directRequirementsSupported(driver);
        return new DriverInfo(typeNames, fields, typeValues, capable);
    }

    static boolean directCapability(boolean jdbcProvider, Set<String> types,
            boolean disabled, boolean internal, boolean instantiable,
            boolean localSystemSupported) {
        return jdbcProvider && types.contains("URL") && !disabled && !internal
                && instantiable && localSystemSupported;
    }

    private static List<Object> enabledDrivers() throws Exception {
        Object platform = DataExportPlugin.call(
                DataExportPlugin.c("org.jkiss.dbeaver.runtime.DBWorkbench"),
                "getPlatform");
        Object providerRegistry = DataExportPlugin.call(
                platform, "getDataSourceProviderRegistry");
        Object providers = DataExportPlugin.call(
                providerRegistry, "getEnabledDataSourceProviders");
        if (!(providers instanceof Iterable)) {
            throw new IllegalStateException("enabled provider list is unavailable");
        }
        List<Object> drivers = new ArrayList<Object>();
        for (Object provider : (Iterable<?>) providers) {
            if (provider == null) continue;
            Object currentDrivers = DataExportPlugin.call(provider, "getEnabledDrivers");
            if (!(currentDrivers instanceof Iterable)) continue;
            for (Object driver : (Iterable<?>) currentDrivers) {
                if (driver == null || !usableDriver(driver)) continue;
                drivers.add(driver);
            }
        }
        return drivers;
    }

    private static boolean usableDriver(Object driver) throws Exception {
        if (Boolean.TRUE.equals(call(driver, "isDisabled"))
                || Boolean.TRUE.equals(call(driver, "isInternalDriver"))
                || !Boolean.TRUE.equals(call(driver, "isInstantiable"))
                || !Boolean.TRUE.equals(call(driver, "isSupportedByLocalSystem"))) {
            return false;
        }
        if (call(driver, "getDataSourceProvider") == null) return false;
        Object stub = call(driver, "getDriverStub");
        if (stub != null) return false;
        Object types = call(driver, "getSupportedConfigurationTypes");
        return types instanceof Collection && !((Collection<?>) types).isEmpty();
    }

    private static Object findEnabledDriver(String providerId, String driverId)
            throws Exception {
        for (Object driver : enabledDrivers()) {
            if (providerId.equals(call(driver, "getProviderId"))
                    && driverId.equals(call(driver, "getId"))) return driver;
        }
        throw new DataExportPlugin.HttpException(404, "enabled driver not found");
    }

    private static void validateConnection(CreationRequest spec, DriverInfo info,
            Object driver) throws Exception {
        ConnectionSettings.validateForDriver(driver, spec.connection);
        if ("direct".equals(spec.mode)) {
            validateDirectRequiredProperties(spec, driver);
        }
    }

    private static boolean propertyValueMatches(Object descriptor, String value)
            throws Exception {
        Object typeValue = call(descriptor, "getDataType");
        if (!(typeValue instanceof Class)) return false;
        Class<?> type = (Class<?>) typeValue;
        try {
            if (type == String.class) return true;
            if (type == Boolean.class || type == boolean.class) {
                return "true".equalsIgnoreCase(value)
                        || "false".equalsIgnoreCase(value);
            }
            if (type == Byte.class || type == byte.class) Byte.parseByte(value);
            else if (type == Short.class || type == short.class) Short.parseShort(value);
            else if (type == Integer.class || type == int.class) Integer.parseInt(value);
            else if (type == Long.class || type == long.class) Long.parseLong(value);
            else if (type == Float.class || type == float.class) Float.parseFloat(value);
            else if (type == Double.class || type == double.class) Double.parseDouble(value);
            else if (type == Character.class || type == char.class) return value.length() == 1;
            else if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                for (Object constant : constants) {
                    if (((Enum<?>) constant).name().equals(value)) return true;
                }
                return false;
            } else {
                return false;
            }
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }


    private static Object guidedConfiguration(Object driver, CreationRequest spec,
            DriverInfo info) throws Exception {
        Object configuration = DataExportPlugin.construct(
                DataExportPlugin.c("org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration"));
        fillConfiguration(configuration, driver, spec.connection, info, false);
        return configuration;
    }

    private static Object directConfiguration(Object driver, CreationRequest spec,
            DriverInfo info) throws Exception {
        Object configuration = DataExportPlugin.construct(
                DataExportPlugin.c("org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration"));
        fillConfiguration(configuration, driver, spec.connection, info, true);
        return configuration;
    }

    private static void fillConfiguration(Object configuration, Object driver,
            Map<String, Object> connection, DriverInfo info, boolean direct)
            throws Exception {
        ConnectionSettings.apply(configuration, driver, connection);
        if (direct) {
            applyPropertyDefaults(configuration, driver,
                    "getMainPropertyDescriptors", "setProperty",
                    connection.get("properties"));
            applyPropertyDefaults(configuration, driver,
                    "getProviderPropertyDescriptors", "setProviderProperty",
                    connection.get("providerProperties"));
        }
        String configurationType = direct ? "URL"
                : connection.containsKey("url") ? "URL"
                : hasManualHints(connection) ? "MANUAL" : null;
        if (configurationType != null) {
            Object type = info.typeValues.get(configurationType);
            if (type == null) {
                if (direct) throw unsupported("URL configuration is unavailable; choose guided mode");
                throw unsupported("manual configuration is unavailable for these hints; choose guided mode");
            }
            DataExportPlugin.call(configuration, "setConfigurationType",
                    DataExportPlugin.arr(type));
        }
        if (direct) {
            Object generated = DataExportPlugin.call(driver, "getConnectionURL",
                    DataExportPlugin.arr(configuration));
            String supplied = (String) connection.get("url");
            if (!(generated instanceof String)
                    || !supplied.equals(generated)
                    || ConnectionSettings.containsCredentials(
                            string(DataExportPlugin.call(driver, "getProviderId")),
                            (String) generated)) {
                throw unsupported("DBeaver cannot establish the requested safe JDBC URL; choose guided mode");
            }
        }
    }

    private static boolean hasManualHints(Map<String, Object> connection) {
        for (String key : Arrays.asList("host", "port", "database", "server")) {
            if (connection.containsKey(key)) return true;
        }
        return false;
    }

    private static Operation enqueue(DBeaverWorkspace.ProjectContext project,
            Object driver, CreationRequest spec, Object configuration)
            throws Exception {
        synchronized (OPERATION_LOCK) {
            pruneOperations(System.currentTimeMillis());
            assertWizardAvailableLocked();
            requireOperationCapacityLocked();
            String id = newOperationIdLocked();
            Map<String, Set<String>> beforeByProject =
                    snapshotIdsByProject();
            Set<String> beforeIds = beforeByProject.get(project.id);
            if (beforeIds == null) beforeIds = ids(project.registry);
            Operation operation = new Operation(id, "create", project, driver,
                    spec, configuration, beforeIds, System.currentTimeMillis());
            operation.beforeIdsByProject = beforeByProject;
            OPERATIONS.put(id, operation);
            activeOperationId = id;
            dispatchWizard(operation);
            return operation;
        }
    }

    private static Operation enqueueEdit(DBeaverWorkspace.ProjectContext project,
            Object descriptor, DBeaverWorkspace.Identity identity,
            EditRequest request, boolean saved) throws Exception {
        synchronized (OPERATION_LOCK) {
            pruneOperations(System.currentTimeMillis());
            assertWizardAvailableLocked();
            requireOperationCapacityLocked();
            String id = newOperationIdLocked();
            Operation operation = new Operation(id, "edit", project, null,
                    null, null, Collections.<String>emptySet(),
                    System.currentTimeMillis());
            operation.targetDescriptor = descriptor;
            operation.identity = identity;
            operation.editRequest = request;
            operation.saved = saved;
            Object existingDriver = DataExportPlugin.call(descriptor, "getDriver");
            Object existingConfiguration = DataExportPlugin.call(
                    descriptor, "getConnectionConfiguration");
            operation.initialSettings = ConnectionSettings.snapshot(
                    existingDriver, existingConfiguration);
            OPERATIONS.put(id, operation);
            activeOperationId = id;
            dispatchWizard(operation);
            return operation;
        }
    }

    private static Operation enqueueTest(DBeaverWorkspace.ProjectContext project,
            Object descriptor, DBeaverWorkspace.Identity identity, boolean saved,
            long timeoutMillis) {
        Operation operation;
        synchronized (OPERATION_LOCK) {
            pruneOperations(System.currentTimeMillis());
            requireOperationCapacityLocked();
            String id = newOperationIdLocked();
            operation = new Operation(id, "test", project, null, null, null,
                    Collections.<String>emptySet(), System.currentTimeMillis());
            operation.targetDescriptor = descriptor;
            operation.identity = identity;
            operation.saved = saved;
            operation.timeoutMillis = timeoutMillis;
            OPERATIONS.put(id, operation);
        }
        DBeaverConnectionTester.start(operation);
        return operation;
    }

    private static void assertWizardAvailableLocked() {
        if (activeOperationId != null) {
            Operation active = OPERATIONS.get(activeOperationId);
            if (active != null && !active.terminal()) {
                throw new DataExportPlugin.HttpException(
                        409, "a guided connection wizard is already active");
            }
            activeOperationId = null;
        }
    }

    private static void requireOperationCapacityLocked() {
        if (OPERATIONS.size() >= MAX_OPERATIONS) {
            throw new DataExportPlugin.HttpException(
                    503, "connection operation capacity is full");
        }
    }

    private static String newOperationIdLocked() {
        String id;
        do { id = UUID.randomUUID().toString(); }
        while (OPERATIONS.containsKey(id));
        return id;
    }

    private static void dispatchWizard(Operation operation) throws Exception {
        OPERATIONS.put(operation.id, operation);
        try {
            dispatchGuided(operation);
            startQueuedWatchdog(operation);
        } catch (Exception failure) {
            OPERATIONS.remove(operation.id);
            releaseActive(operation);
            throw new DataExportPlugin.HttpException(
                    503, "DBeaver UI could not schedule the connection wizard");
        }
    }

    private static void dispatchGuided(final Operation operation) throws Exception {
        Object workbench = DataExportPlugin.call(
                uiClass("org.eclipse.ui.PlatformUI"), "getWorkbench");
        Object display = DataExportPlugin.call(workbench, "getDisplay");
        if (display == null) {
            fail(operation, "ui_unavailable", "DBeaver UI is unavailable");
            return;
        }
        final String dialogClass = "edit".equals(operation.kind)
                ? "org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionDialog"
                : "org.jkiss.dbeaver.ui.dialogs.connection.NewConnectionDialog";
        Runnable task = new Runnable() {
            @Override public void run() {
                ClassLoader original = Thread.currentThread().getContextClassLoader();
                try {
                    ClassLoader dbeaverLoader = uiClass(dialogClass).getClassLoader();
                    Thread.currentThread().setContextClassLoader(dbeaverLoader);
                    if ("edit".equals(operation.kind)) {
                        openEditGuided(operation);
                    } else {
                        openGuided(operation);
                    }
                } catch (DataExportPlugin.HttpException failure) {
                    CleanupOutcome cleanup = abandonUnattachedWizard(operation);
                    synchronized (OPERATION_LOCK) {
                        operation.diagnostic = DBeaverConnectionTester.diagnose(failure);
                        fail(operation, "wizard_conflict",
                                failure.getMessage(),
                                cleanup);
                    }
                } catch (Exception failure) {
                    CleanupOutcome cleanup = abandonUnattachedWizard(operation);
                    synchronized (OPERATION_LOCK) {
                        operation.diagnostic = DBeaverConnectionTester.diagnose(failure);
                        fail(operation, "wizard_open_failed",
                                "DBeaver could not open the requested native connection dialog",
                                cleanup);
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(original);
                }
            }
        };
        DataExportPlugin.call(display, "asyncExec", DataExportPlugin.arr(task));
    }

    private static void openGuided(Operation operation) throws Exception {
        if (!markUiStarted(operation)) return;
        assertNoNativeWizard();
        Object workbench = DataExportPlugin.call(
                uiClass("org.eclipse.ui.PlatformUI"), "getWorkbench");
        Object window = DataExportPlugin.call(workbench, "getActiveWorkbenchWindow");
        if (window == null) throw new DataExportPlugin.HttpException(
                503, "DBeaver workbench window is unavailable");

        selectNavigatorProject(window, operation.project);
        Object wizard = DataExportPlugin.construct(
                uiClass("org.jkiss.dbeaver.ui.dialogs.connection.NewConnectionWizard"),
                operation.driver, operation.configuration);
        operation.wizard = wizard;
        watchWizardSubstitution(operation, wizard);
        Class<?> dialogType = uiClass(
                "org.jkiss.dbeaver.ui.dialogs.connection.NewConnectionDialog");
        java.lang.reflect.Constructor<?> constructor = dialogType.getDeclaredConstructor(
                uiClass("org.eclipse.ui.IWorkbenchWindow"),
                uiClass("org.jkiss.dbeaver.ui.dialogs.connection.ConnectionWizard"));
        constructor.setAccessible(true);
        Object dialog = constructor.newInstance(window, wizard);
        operation.dialog = dialog;
        Field instance = nativeDialogField();
        if (instance.get(null) != null) {
            throw new DataExportPlugin.HttpException(
                    409, "a native connection wizard is already open");
        }
        instance.set(null, dialog);
        // Native shell modality does not make JFace open() non-blocking.
        // Own the dialog lifecycle so completion is observed after real disposal.
        DataExportPlugin.call(dialog, "setBlockOnOpen", arr(false));
        DataExportPlugin.call(dialog, "create");
        Object shell = DataExportPlugin.call(dialog, "getShell");
        if (shell == null) throw new IllegalStateException("native wizard has no shell");
        operation.shell = shell;
        requireWizardProject(wizard, operation.project);
        if (operation.spec.name != null) {
            prefillWizardName(wizard, operation.spec.name, true);
        }
        attachDisposeListener(shell, operation);
        DataExportPlugin.call(dialog, "open");
        if (Boolean.TRUE.equals(DataExportPlugin.call(shell, "isDisposed"))) {
            scheduleShellCompletion(operation, shell);
        } else {
            transitionToAwaitingInput(operation);
        }
    }
    private static void openEditGuided(final Operation operation)
            throws Exception {
        if (!markUiStarted(operation)) return;
        assertNoNativeWizard();
        assertNoNativeEditDialog();
        Object workbench = DataExportPlugin.call(
                uiClass("org.eclipse.ui.PlatformUI"), "getWorkbench");
        Object window = DataExportPlugin.call(workbench, "getActiveWorkbenchWindow");
        if (window == null) throw new DataExportPlugin.HttpException(
                503, "DBeaver workbench window is unavailable");
        final Object target = operation.targetDescriptor;
        Class<?> dialogType = uiClass(
                "org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionDialog");
        Class<?> consumerType = java.util.function.Consumer.class;
        Object consumer = Proxy.newProxyInstance(consumerType.getClassLoader(),
                new Class<?>[]{consumerType}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method,
                            Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            return listenerObjectMethod(proxy, method, args);
                        }
                        if ("accept".equals(method.getName())
                                && args != null && args.length == 1) {
                            Object wizard = args[0];
                            operation.wizard = wizard;
                            requireWizardProject(wizard, operation.project);
                            if (DataExportPlugin.call(wizard,
                                    "getOriginalDataSource") != target) {
                                throw new DataExportPlugin.HttpException(
                                        409, "native edit wizard selected a different profile");
                            }
                            applyEditPrefills(wizard, operation);
                            if (operation.editRequest.name == null) {
                                transitionToAwaitingInput(operation);
                            } else {
                                scheduleEditNamePrefill(operation, wizard);
                            }
                        }
                        return null;
                    }
                });
        boolean accepted = Boolean.TRUE.equals(DataExportPlugin.call(dialogType,
                "openEditConnectionDialog", DataExportPlugin.arr(window, target,
                        null, consumer)));
        completeEditGuided(operation, accepted);
    }

    private static void assertNoNativeEditDialog() throws Exception {
        Class<?> dialogType = uiClass(
                "org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionDialog");
        Field field = dialogType.getDeclaredField("openDialogs");
        field.setAccessible(true);
        Object dialogs = field.get(null);
        if (!(dialogs instanceof Map)) return;
        for (Object dialog : ((Map<?, ?>) dialogs).values()) {
            Object shell = DataExportPlugin.call(dialog, "getShell");
            if (shell == null
                    || !Boolean.TRUE.equals(DataExportPlugin.call(shell, "isDisposed"))) {
                throw new DataExportPlugin.HttpException(
                        409, "a native connection edit dialog is already open");
            }
        }
    }

    private static void applyEditPrefills(Object wizard, Operation operation)
            throws Exception {
        EditRequest request = operation.editRequest;
        Object draft = DataExportPlugin.call(wizard, "getActiveDataSource");
        if (draft == null) throw new IllegalStateException(
                "native edit wizard has no working profile");
        if (request.name != null) {
            DataExportPlugin.call(draft, "setName",
                    DataExportPlugin.arr(request.name));
            if (!request.name.equals(DataExportPlugin.call(draft, "getName"))) {
                throw new IllegalStateException("native edit name prefill was rejected");
            }
        }
        if (!request.connection.isEmpty()) {
            Object driver = DataExportPlugin.call(draft, "getDriver");
            Object configuration = DataExportPlugin.call(
                    draft, "getConnectionConfiguration");
            ConnectionSettings.apply(configuration, driver, request.connection);
        }
    }

    private static void scheduleEditNamePrefill(final Operation operation,
            final Object wizard) throws Exception {
        Object workbench = DataExportPlugin.call(
                uiClass("org.eclipse.ui.PlatformUI"), "getWorkbench");
        Object display = DataExportPlugin.call(workbench, "getDisplay");
        DataExportPlugin.call(display, "asyncExec", DataExportPlugin.arr(new Runnable() {
            @Override public void run() {
                if (operation.terminal()) return;
                try {
                    Field pageField = wizard.getClass().getDeclaredField("pageGeneral");
                    pageField.setAccessible(true);
                    Object page = pageField.get(wizard);
                    if (page == null) throw new IllegalStateException("native general page unavailable");
                    String pageName = string(DataExportPlugin.call(page, "getName"));
                    if (!Boolean.TRUE.equals(DataExportPlugin.call(wizard,
                            "openSettingsPage", DataExportPlugin.arr(pageName)))) {
                        throw new IllegalStateException("native general page could not be shown");
                    }
                    Field textField = page.getClass().getDeclaredField("connectionNameText");
                    textField.setAccessible(true);
                    Object text = textField.get(page);
                    if (text == null) throw new IllegalStateException("native name control unavailable");
                    String name = operation.editRequest.name;
                    DataExportPlugin.call(text, "setText", DataExportPlugin.arr(name));
                    if (!name.equals(DataExportPlugin.call(text, "getText"))) {
                        throw new IllegalStateException("native name control rejected the prefill");
                    }
                    transitionToAwaitingInput(operation);
                } catch (Exception failure) {
                    synchronized (OPERATION_LOCK) {
                        operation.diagnostic = DBeaverConnectionTester.diagnose(failure);
                        fail(operation, "edit_name_prefill_failed",
                                "DBeaver could not populate the native connection name control.", null);
                    }
                    try {
                        Class<?> type = uiClass(
                                "org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionDialog");
                        Field dialogsField = type.getDeclaredField("openDialogs");
                        dialogsField.setAccessible(true);
                        Object dialogs = dialogsField.get(null);
                        if (dialogs instanceof Map) {
                            for (Object dialog : ((Map<?, ?>) dialogs).values()) {
                                if (DataExportPlugin.call(dialog, "getWizard") == wizard) {
                                    DataExportPlugin.call(dialog, "setReturnCode", DataExportPlugin.arr(1));
                                    DataExportPlugin.call(wizard, "performCancel");
                                    DataExportPlugin.call(dialog, "close");
                                    break;
                                }
                            }
                        }
                    } catch (Exception closeFailure) {
                        // The operation is failed; an existing profile is never removed.
                    }
                }
            }
        }));
    }

    private static void completeEditGuided(Operation operation, boolean accepted)
            throws Exception {
        if (accepted && operation.wizard == null) {
            finish(operation, "failed", false, operation.saved,
                    operation.identity, "edit_dialog_unowned",
                    "DBeaver did not create a request-owned native edit dialog.", null);
            return;
        }
        Object original = operation.targetDescriptor;
        String originalId = operation.identity.id;
        Object current = DataExportPlugin.call(operation.project.registry,
                "getDataSource", DataExportPlugin.arr(originalId));
        if (current != original) {
            DBeaverWorkspace.Identity currentIdentity = current == null
                    ? operation.identity : DBeaverWorkspace.identity(current);
            finish(operation, "failed", false, false, currentIdentity,
                    "profile_changed", "The existing profile changed during native editing.",
                    new CleanupOutcome(false, false, false, current == null));
            return;
        }
        DBeaverWorkspace.Identity identity = DBeaverWorkspace.identity(current);
        NativeDirectStore store = new NativeDirectStore(operation.project);
        ConnectionSettings.StoredProfile stored = store.readPersisted(
                new Profile(current, identity));
        if (stored == null) {
            finish(operation, "failed", false, false, identity,
                    "persistence_unavailable",
                    "DBeaver could not read back the existing profile configuration.",
                    new CleanupOutcome(false, false, false, false));
            return;
        }
        Object driver = DataExportPlugin.call(current, "getDriver");
        Object configuration = DataExportPlugin.call(
                current, "getConnectionConfiguration");
        Map<String, Object> currentSettings =
                ConnectionSettings.snapshot(driver, configuration);

        if (!accepted) {
            boolean unchanged = operation.initialSettings != null
                    && operation.identity.name.equals(identity.name)
                    && operation.initialSettings.equals(currentSettings);
            if (unchanged) {
                finish(operation, "cancelled", false, stored.matches, identity,
                        "cancelled", "The native connection edit was cancelled.", null);
            } else {
                finish(operation, "failed", false, stored.matches, identity,
                        "cancel_changed_profile",
                        "DBeaver changed the existing profile while cancelling the native edit.",
                        new CleanupOutcome(false, stored.matches, false, false));
            }
            return;
        }

        // Native UI review is authoritative; prefills are not immutable requested values.
        boolean settingsMatch = matchesSettings(currentSettings, stored.settings);
        boolean collision = nameExistsExcept(operation.project.registry,
                identity.name, identity.id);
        if (stored.matches && settingsMatch && !collision) {
            finish(operation, "saved", false, true, identity,
                    null, null, null);
        } else {
            String code = collision ? "name_collision"
                    : !stored.matches ? "persistence_failed" : "settings_mismatch";
            finish(operation, "failed", false, stored.matches, identity,
                    code, "DBeaver could not confirm the requested existing-profile edit.",
                    new CleanupOutcome(false, stored.matches, false, true));
        }
    }

    private static boolean matchesSettings(Map<String, Object> requested,
            Map<String, Object> actual) {
        if (requested == null || requested.isEmpty()) return true;
        if (actual == null) return false;
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            Object persisted = actual.get(entry.getKey());
            Object supplied = entry.getValue();
            if (supplied instanceof Map) {
                if (((Map<?, ?>) supplied).isEmpty()) continue;
                if (!(persisted instanceof Map)) return false;
                Map<?, ?> persistedMap = (Map<?, ?>) persisted;
                for (Map.Entry<?, ?> property : ((Map<?, ?>) supplied).entrySet()) {
                    if (!property.getValue().equals(
                            persistedMap.get(property.getKey()))) return false;
                }
            } else if (!supplied.equals(persisted)) {
                return false;
            }
        }
        return true;
    }

    private static void selectNavigatorProject(Object window,
            DBeaverWorkspace.ProjectContext project) throws Exception {
        DataExportPlugin.call(project.workspace, "setActiveProject",
                DataExportPlugin.arr(project.project));
        Object page = DataExportPlugin.call(window, "getActivePage");
        if (page == null) throw new DataExportPlugin.HttpException(
                503, "DBeaver workbench page is unavailable");
        Class<?> navigatorType = uiClass(
                "org.jkiss.dbeaver.ui.navigator.project.ProjectNavigatorView");
        String viewId = string(navigatorType.getField("VIEW_ID").get(null));
        Object view = DataExportPlugin.call(page, "findView",
                DataExportPlugin.arr(viewId));
        if (view == null) {
            view = DataExportPlugin.call(page, "showView",
                    DataExportPlugin.arr(viewId));
        }
        Object viewer = DataExportPlugin.call(view, "getNavigatorViewer");
        Object root = DataExportPlugin.call(view, "getRootNode");
        if (root == null) {
            Object model = DataExportPlugin.call(navigatorType,
                    "getGlobalNavigatorModel");
            root = model == null ? null : DataExportPlugin.call(model, "getRoot");
        }
        Object projectNode = root == null ? null : DataExportPlugin.call(root,
                "getProjectNode", DataExportPlugin.arr(project.project));
        if (viewer == null || projectNode == null) {
            throw new DataExportPlugin.HttpException(
                    503, "DBeaver navigator project selection is unavailable");
        }
        DataExportPlugin.call(page, "activate", DataExportPlugin.arr(view));
        DataExportPlugin.call(view, "setFocus");
        Object selection = DataExportPlugin.construct(
                uiClass("org.eclipse.jface.viewers.StructuredSelection"),
                projectNode);
        DataExportPlugin.call(viewer, "setSelection",
                DataExportPlugin.arr(selection, Boolean.TRUE));
        Object current = DataExportPlugin.call(viewer, "getSelection");
        Object selected = current == null ? null
                : DataExportPlugin.call(current, "getFirstElement");
        Object selectedProject = selected == null ? null
                : DataExportPlugin.call(selected, "getProject");
        String selectedId = selectedProject == null ? null
                : string(DataExportPlugin.call(selectedProject, "getId"));
        if (!project.id.equals(selectedId)) {
            throw new DataExportPlugin.HttpException(
                    409, "DBeaver navigator did not select the requested project");
        }
    }

    private static void requireWizardProject(Object wizard,
            DBeaverWorkspace.ProjectContext expected) throws Exception {
        Object registry = DataExportPlugin.call(wizard, "getDataSourceRegistry");
        Method selectedMethod = wizard.getClass().getDeclaredMethod(
                "getSelectedProject");
        selectedMethod.setAccessible(true);
        Object selectedProject = selectedMethod.invoke(wizard);
        String selectedId = selectedProject == null ? null
                : string(DataExportPlugin.call(selectedProject, "getId"));
        if (registry != expected.registry || !expected.id.equals(selectedId)) {
            throw new DataExportPlugin.HttpException(
                    409, "native connection wizard selected a different project");
        }
    }


    private static void prefillWizardName(Object wizard, String name,
            boolean requireGeneralPage) throws Exception {
        Object activeDataSource = DataExportPlugin.call(wizard, "getActiveDataSource");
        if (activeDataSource == null) {
            throw new IllegalStateException("wizard has no active profile to prefill");
        }
        DataExportPlugin.call(activeDataSource, "setName",
                DataExportPlugin.arr(name));
        if (!name.equals(DataExportPlugin.call(activeDataSource, "getName"))) {
            throw new IllegalStateException("wizard profile name was not applied");
        }
        Field pageGeneral = wizard.getClass().getDeclaredField("pageGeneral");
        pageGeneral.setAccessible(true);
        Object page = pageGeneral.get(wizard);
        if (page == null) {
            if (requireGeneralPage) {
                throw new IllegalStateException("native general page is unavailable");
            }
            return;
        }
        Method getActiveDataSource = page.getClass().getDeclaredMethod(
                "getActiveDataSource");
        getActiveDataSource.setAccessible(true);
        Object pageDataSource = getActiveDataSource.invoke(page);
        if (pageDataSource == null) {
            throw new IllegalStateException("native general page has no profile");
        }
        DataExportPlugin.call(pageDataSource, "setName",
                DataExportPlugin.arr(name));
        if (!name.equals(DataExportPlugin.call(pageDataSource, "getName"))) {
            throw new IllegalStateException("native profile name was not applied");
        }
        Field nameText = page.getClass().getDeclaredField("connectionNameText");
        nameText.setAccessible(true);
        Object text = nameText.get(page);
        if (text != null) {
            DataExportPlugin.call(text, "setText", DataExportPlugin.arr(name));
            if (!name.equals(DataExportPlugin.call(text, "getText"))) {
                throw new IllegalStateException("wizard name field rejected the prefill");
            }
        }
    }

    private static void attachDisposeListener(Object shell,
            final Operation operation) throws Exception {
        Class<?> listenerType = uiClass("org.eclipse.swt.widgets.Listener");
        final Object ownedDialog = operation.dialog;
        final Field dialogInstance = nativeDialogField();
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                new Class<?>[]{listenerType}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method,
                            Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            return listenerObjectMethod(proxy, method, args);
                        }
                        if ("handleEvent".equals(method.getName())) {
                            if (dialogInstance.get(null) == ownedDialog) {
                                dialogInstance.set(null, null);
                            }
                            scheduleShellCompletion(operation, shell);
                        }
                        return null;
                    }
                });
        int dispose = uiClass("org.eclipse.swt.SWT")
                .getField("Dispose").getInt(null);
        DataExportPlugin.call(shell, "addListener",
                DataExportPlugin.arr(dispose, listener));
    }
    private static void watchWizardSubstitution(final Operation operation,
            Object wizard) throws Exception {
        Class<?> listenerType = uiClass(
                "org.eclipse.jface.util.IPropertyChangeListener");
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                new Class<?>[]{listenerType}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method,
                            Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            return listenerObjectMethod(proxy, method, args);
                        }
                        if ("propertyChange".equals(method.getName())
                                && args != null && args.length == 1) {
                            Object event = args[0];
                            if ("driver-substitution".equals(
                                    DataExportPlugin.call(event, "getProperty"))) {
                                Object substitution =
                                        DataExportPlugin.call(event, "getNewValue");
                                synchronized (OPERATION_LOCK) {
                                    if (!operation.terminal()) {
                                        operation.pendingSubstitution = substitution;
                                        operation.continuationPending = true;
                                    }
                                }
                            }
                        }
                        return null;
                    }
                });
        DataExportPlugin.call(wizard, "addPropertyChangeListener",
                DataExportPlugin.arr(listener));
    }

    private static void scheduleShellCompletion(final Operation operation,
            final Object closedShell) {
        try {
            Object workbench = DataExportPlugin.call(
                    uiClass("org.eclipse.ui.PlatformUI"), "getWorkbench");
            Object display = DataExportPlugin.call(workbench, "getDisplay");
            Runnable resolve = new Runnable() {
                @Override public void run() {
                    resolveShellCompletion(operation, closedShell, 0);
                }
            };
            DataExportPlugin.call(display, "timerExec",
                    DataExportPlugin.arr(SUBSTITUTION_POLL_DELAY_MILLIS, resolve));
        } catch (Exception failure) {
            if (operation.continuationPending) {
                failUnresolvedContinuation(operation);
            } else {
                completeGuided(operation);
            }
        }
    }

    private static void resolveShellCompletion(Operation operation,
            Object closedShell, int attempts) {
        if (operation.terminal() || operation.shell != closedShell) return;
        if (!operation.continuationPending) {
            completeGuided(operation);
            return;
        }
        try {
            WizardDialogState replacement =
                    findSubstitutionReplacement(operation);
            if (replacement != null) {
                if (replacement.registry != operation.project.registry) {
                    operation.wizard = replacement.wizard;
                    operation.shell = replacement.shell;
                    CleanupOutcome cleanup = abandonUnattachedWizard(operation);
                    fail(operation, "project_changed",
                            "the native replacement wizard selected another project",
                            cleanup);
                    return;
                }
                operation.wizard = replacement.wizard;
                operation.shell = replacement.shell;
                operation.dialog = nativeDialogField().get(null);
                operation.pendingSubstitution = null;
                operation.continuationPending = false;
                watchWizardSubstitution(operation, replacement.wizard);
                if (operation.spec.name != null) {
                    prefillWizardName(replacement.wizard,
                            operation.spec.name, true);
                }
                attachDisposeListener(replacement.shell, operation);
                return;
            }
            if (attempts + 1 < MAX_SUBSTITUTION_POLLS) {
                Object workbench = DataExportPlugin.call(
                        uiClass("org.eclipse.ui.PlatformUI"),
                        "getWorkbench");
                Object display = DataExportPlugin.call(workbench, "getDisplay");
                Runnable resolve = new Runnable() {
                    @Override public void run() {
                        resolveShellCompletion(operation, closedShell, attempts + 1);
                    }
                };
                DataExportPlugin.call(display, "timerExec",
                        DataExportPlugin.arr(SUBSTITUTION_POLL_DELAY_MILLIS, resolve));
                return;
            }
            failUnresolvedContinuation(operation);
        } catch (Exception failure) {
            CleanupOutcome cleanup = abandonUnattachedWizard(operation);
            fail(operation, "continuation_open_failed",
                    "DBeaver could not resume the request-owned wizard", cleanup);
        }
    }

    private static WizardDialogState findSubstitutionReplacement(
            Operation operation) throws Exception {
        Object expected = operation.pendingSubstitution;
        if (expected == null) return null;
        Object dialog = nativeDialogField().get(null);
        if (dialog == null) return null;
        Object wizard = DataExportPlugin.call(dialog, "getWizard");
        if (wizard == null || wizard == operation.wizard) return null;
        Object actual = DataExportPlugin.call(wizard, "getDriverSubstitution");
        if (!sameSubstitution(expected, actual)) return null;
        Object shell = DataExportPlugin.call(dialog, "getShell");
        if (Boolean.TRUE.equals(DataExportPlugin.call(shell, "isDisposed"))) {
            return null;
        }
        Object registry = DataExportPlugin.call(wizard, "getDataSourceRegistry");
        return new WizardDialogState(wizard, shell, registry);
    }

    private static boolean sameSubstitution(Object expected, Object actual)
            throws Exception {
        if (expected == actual) return true;
        if (expected == null || actual == null) return false;
        Object expectedId = DataExportPlugin.call(expected, "getId");
        Object actualId = DataExportPlugin.call(actual, "getId");
        return expectedId != null && expectedId.equals(actualId);
    }

    private static void failUnresolvedContinuation(Operation operation) {
        finish(operation, "failed", false, false, null,
                "continuation_unresolved",
                "DBeaver's replacement wizard did not resolve; reconcile /datasources for residual state",
                new CleanupOutcome(false, false, false, false));
    }

    private static void completeGuided(final Operation operation) {
        synchronized (OPERATION_LOCK) {
            if (operation.terminal() || operation.closing) return;
            operation.closing = true;
        }
        Profile profile = null;
        NativeDirectStore store = null;
        try {
            Object descriptor = operation.wizard == null ? null
                    : DataExportPlugin.call(
                            operation.wizard, "getOriginalDataSource");
            if (descriptor == null) {
                finish(operation, "cancelled", false, false, null,
                        "cancelled", "connection wizard was closed without saving", null);
                return;
            }
            profile = nativeProfile(descriptor);
            if (profile.identity.id == null || profile.identity.id.isEmpty()
                    || operation.beforeIds.contains(profile.identity.id)) {
                finish(operation, "failed", false, false, null,
                        "identity_collision",
                        "wizard did not produce a new request-owned profile", null);
                return;
            }
            Object descriptorRegistry = DataExportPlugin.call(descriptor, "getRegistry");
            boolean projectMatches = operation.project.id.equals(
                    profile.identity.projectId)
                    && descriptorRegistry == operation.project.registry;
            if (!projectMatches) {
                CleanupOutcome outcome = cleanupUnexpectedProfile(
                        operation, descriptor, profile);
                finish(operation, "failed", outcome.created, outcome.saved,
                        profile.identity, "identity_mismatch",
                        "DBeaver saved the request-owned profile in a different project.",
                        outcome);
                return;
            }
            store = nativeStoreForDescriptor(operation.project, descriptor);
            boolean registered = store.isRegistered(profile);
            Exception saveFailure = null;
            try {
                store.checkForErrors();
            } catch (Exception failure) {
                saveFailure = failure;
            }
            boolean persisted = false;
            try {
                persisted = store.isPersisted(profile);
            } catch (Exception failure) {
                if (saveFailure == null) saveFailure = failure;
            }
            if (registered && persisted && saveFailure == null) {
                finish(operation, "saved", true, true, profile.identity,
                        null, null, null);
                return;
            }
            CleanupOutcome outcome = cleanup(store, profile);
            String code = !registered ? "registration_failed" : "persistence_failed";
            String error = "DBeaver did not confirm the requested profile and its persistence";
            finish(operation, "failed", outcome.created, outcome.saved,
                    profile.identity, code, error, outcome);
        } catch (Exception failure) {
            CleanupOutcome outcome = profile != null && store != null
                    ? cleanup(store, profile) : null;
            finish(operation, "failed",
                    outcome != null && outcome.created,
                    outcome != null && outcome.saved,
                    profile == null ? null : profile.identity,
                    "completion_failed",
                    "DBeaver could not confirm wizard completion",
                    outcome);
        }
    }

    private static NativeDirectStore nativeStoreForDescriptor(
            DBeaverWorkspace.ProjectContext expected, Object descriptor)
            throws Exception {
        Object actualProject = DataExportPlugin.call(descriptor, "getProject");
        Object registry = DataExportPlugin.call(descriptor, "getRegistry");
        String projectId = actualProject == null ? null
                : string(DataExportPlugin.call(actualProject, "getId"));
        if (registry != expected.registry || !expected.id.equals(projectId)) {
            throw new IllegalStateException("profile belongs to a different project registry");
        }
        return new NativeDirectStore(expected);
    }

    private static CleanupOutcome cleanupUnexpectedProfile(Operation operation,
            Object descriptor, Profile profile) {
        try {
            Object actualProject = DataExportPlugin.call(descriptor, "getProject");
            Object registry = DataExportPlugin.call(descriptor, "getRegistry");
            String projectId = actualProject == null ? null
                    : string(DataExportPlugin.call(actualProject, "getId"));
            if (projectId == null || registry == null) {
                return new CleanupOutcome(false, false, false, false);
            }
            Object registered = DataExportPlugin.call(registry, "getDataSource",
                    DataExportPlugin.arr(profile.identity.id));
            if (registered != descriptor) {
                return new CleanupOutcome(false, false, false, registered == null);
            }
            Set<String> before = operation.beforeIdsByProject.get(projectId);
            if (before == null || before.contains(profile.identity.id)) {
                boolean persisted = false;
                try {
                    DBeaverWorkspace.ProjectContext actual =
                            DBeaverWorkspace.requireProject(
                                    operation.project.workspace, projectId);
                    if (actual.registry == registry) {
                        persisted = new NativeDirectStore(actual)
                                .isPersisted(profile);
                    }
                } catch (Exception ignored) { }
                return new CleanupOutcome(false, persisted, false, false);
            }
            DBeaverWorkspace.ProjectContext actual =
                    DBeaverWorkspace.requireProject(
                            operation.project.workspace, projectId);
            if (actual.registry != registry) {
                return new CleanupOutcome(false, false, false, false);
            }
            return cleanup(new NativeDirectStore(actual), profile);
        } catch (Exception failure) {
            return new CleanupOutcome(false, false, false, false);
        }
    }

    private static void startQueuedWatchdog(final Operation operation) {
        if (!"queued".equals(operation.state) || operation.uiStarted) return;
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(QUEUED_TIMEOUT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                    if ("queued".equals(operation.state) && !operation.uiStarted) {
                        fail(operation, "ui_unavailable",
                                "DBeaver UI did not start the request-owned wizard", null);
                    }
                }
        }, "dbeaver-connection-watchdog-" + operation.id);
        watchdog.setDaemon(true);
        watchdog.setContextClassLoader(DataExportPlugin.class.getClassLoader());
        watchdog.start();
    }

    private static void fail(Operation operation, String code, String error) {
        fail(operation, code, error, null);
    }

    private static void fail(Operation operation, String code, String error,
            CleanupOutcome cleanup) {
        synchronized (OPERATION_LOCK) {
            if (operation.terminal()) return;
            operation.state = "failed";
            operation.code = code;
            operation.error = error;
            if ("create".equals(operation.kind)) {
                operation.created = cleanup != null && cleanup.created;
                operation.saved = cleanup != null && cleanup.saved;
            } else {
                operation.created = false;
            }
            operation.cleanup = cleanup;
            operation.updatedAt = System.currentTimeMillis();
            releaseActive(operation);
            clearOperationReferences(operation);
        }
    }

    private static void clearOperationReferences(Operation operation) {
        operation.project = null;
        operation.driver = null;
        operation.configuration = null;
        operation.spec = null;
        operation.editRequest = null;
        operation.initialSettings = null;
        operation.targetDescriptor = null;
        operation.beforeIds = Collections.emptySet();
        operation.beforeIdsByProject = Collections.emptyMap();
        operation.wizard = null;
        operation.shell = null;
        operation.dialog = null;
        operation.pendingSubstitution = null;
        operation.continuationPending = false;
        operation.timeoutMillis = 0;
    }

    static boolean transitionToAwaitingInput(Operation operation) {
        synchronized (OPERATION_LOCK) {
            if (!"queued".equals(operation.state)) return false;
            operation.state = "awaiting_input";
            operation.updatedAt = System.currentTimeMillis();
            return true;
        }
    }

    static boolean transitionToRunning(Operation operation) {
        synchronized (OPERATION_LOCK) {
            if (!"queued".equals(operation.state)) return false;
            operation.state = "running";
            operation.updatedAt = System.currentTimeMillis();
            return true;
        }
    }

    private static boolean markUiStarted(Operation operation) {
        synchronized (OPERATION_LOCK) {
            if (!"queued".equals(operation.state)) return false;
            operation.uiStarted = true;
            return true;
        }
    }
    private static CleanupOutcome abandonUnattachedWizard(Operation operation) {
        boolean attempted = false;
        boolean completed = true;
        boolean shellClosed = false;
        Object shell = operation.shell;
        if (shell == null && operation.wizard != null) {
            try {
                Object dialog = operation.dialog;
                if (dialog != null && DataExportPlugin.call(dialog, "getWizard")
                        == operation.wizard) {
                    shell = DataExportPlugin.call(dialog, "getShell");
                }
            } catch (Exception failure) {
                attempted = true;
                completed = false;
            }
        }
        if (shell != null) {
            attempted = true;
            try {
                shellClosed = Boolean.TRUE.equals(
                        DataExportPlugin.call(shell, "isDisposed"));
                if (!shellClosed) {
                    DataExportPlugin.call(shell, "close");
                    shellClosed = Boolean.TRUE.equals(
                            DataExportPlugin.call(shell, "isDisposed"));
                    if (!shellClosed) completed = false;
                }
            } catch (Exception failure) {
                completed = false;
            }
        }
        if (operation.wizard != null && !shellClosed) {
            attempted = true;
            try {
                DataExportPlugin.call(operation.wizard, "dispose");
            } catch (Exception failure) {
                completed = false;
            }
        }
        if (operation.dialog != null) {
            attempted = true;
            try {
                Field field = nativeDialogField();
                if (field.get(null) == operation.dialog) field.set(null, null);
            } catch (Exception failure) {
                completed = false;
            }
        }
        return attempted
                ? new CleanupOutcome(false, false, true, completed) : null;
    }

    static void finishTest(Operation operation,
            DBeaverConnectionTester.TestOutcome outcome) {
        synchronized (OPERATION_LOCK) {
            if (operation.terminal()) return;
            operation.test = outcome.test;
            operation.diagnostic = outcome.diagnostic;
            String code = outcome.diagnostic == null ? null
                    : (String) outcome.diagnostic.get("code");
            if (code == null && outcome.diagnostic != null) {
                code = (String) outcome.diagnostic.get("category");
            }
            String message = outcome.diagnostic == null ? null
                    : (String) outcome.diagnostic.get("message");
            String state = outcome.passed ? "succeeded"
                    : outcome.cancelled ? "cancelled" : "failed";
            finish(operation, state, false, operation.saved, operation.identity,
                    code, message, outcome.cleanup);
        }
    }

    static void finish(Operation operation, String state, boolean created,
            boolean saved, DBeaverWorkspace.Identity identity, String code, String error,
            CleanupOutcome cleanup) {
        synchronized (OPERATION_LOCK) {
            if (operation.terminal()) return;
            if (!("saved".equals(state) || "succeeded".equals(state)
                    || "cancelled".equals(state) || "failed".equals(state))) {
                throw new IllegalArgumentException("invalid terminal operation state");
            }
            if ("saved".equals(state)) {
                boolean validCreate = "create".equals(operation.kind)
                        && created && saved && identity != null;
                boolean validEdit = "edit".equals(operation.kind)
                        && !created && saved && identity != null;
                if (!validCreate && !validEdit) {
                    throw new IllegalArgumentException(
                            "saved operation lacks profile persistence proof");
                }
            }
            if ("succeeded".equals(state)
                    && (!"test".equals(operation.kind) || created
                    || identity == null || operation.test == null
                    || !Boolean.TRUE.equals(operation.test.get("passed"))
                    || cleanup == null || !cleanup.completed)) {
                throw new IllegalArgumentException(
                        "successful test lacks native session and cleanup proof");
            }
            operation.state = state;
            operation.created = created;
            operation.saved = saved;
            operation.code = code;
            operation.error = error;
            operation.identity = identity;
            operation.cleanup = cleanup;
            operation.updatedAt = System.currentTimeMillis();
            releaseActive(operation);
            clearOperationReferences(operation);
        }
    }

    private static void releaseActive(Operation operation) {
        if (operation.id.equals(activeOperationId)) activeOperationId = null;
    }

    private static void pruneOperations(long now) {
        java.util.Iterator<Map.Entry<String, Operation>> iterator =
                OPERATIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Operation operation = iterator.next().getValue();
            if (operation.terminal()
                    && now - operation.updatedAt > OPERATION_RETENTION_MILLIS) {
                iterator.remove();
            }
        }
    }

    static Class<?> uiClass(String name) throws Exception {
        Object platformUi = call(
                DataExportPlugin.c("org.jkiss.dbeaver.runtime.DBWorkbench"),
                "getPlatformUI");
        if (platformUi == null) {
            throw new DataExportPlugin.HttpException(503, "DBeaver UI is unavailable");
        }
        try {
            return DataExportPlugin.providerClass(platformUi, name);
        } catch (ClassNotFoundException unavailable) {
            throw new DataExportPlugin.HttpException(
                    503, "DBeaver native connection UI is unavailable");
        }
    }

    private static Field nativeDialogField() throws Exception {
        Field field = nativeDialogInstance;
        if (field == null) {
            field = uiClass("org.jkiss.dbeaver.ui.dialogs.connection.NewConnectionDialog")
                    .getDeclaredField("dialogInstance");
            field.setAccessible(true);
            nativeDialogInstance = field;
        }
        return field;
    }

    private static boolean isNativeWizardOpen() throws Exception {
        return nativeDialogField().get(null) != null;
    }

    private static Object listenerObjectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "DBeaverConnectionListener";
            default: throw new IllegalArgumentException("unsupported listener method");
        }
    }

    private static void assertNoNativeWizard() throws Exception {
        if (isNativeWizardOpen()) {
            throw new DataExportPlugin.HttpException(
                    409, "a native connection wizard is already open");
        }
    }

    static Result registerDirect(DirectStore store, String name,
            Object driver, Object configuration) throws Exception {
        Set<String> before = store.ids();
        if (store.nameExists(name)) {
            throw new DataExportPlugin.HttpException(
                    409, "a data source with that name already exists");
        }
        Profile profile = null;
        try {
            profile = store.create(name, driver, configuration);
            if (profile == null || profile.identity == null
                    || profile.identity.id == null || profile.identity.id.isEmpty()) {
                throw new IllegalStateException("DBeaver did not create a profile descriptor");
            }
            if (before.contains(profile.identity.id)) {
                store.dispose(profile);
                throw new DataExportPlugin.HttpException(
                        409, "DBeaver generated a colliding data source identity");
            }
            if (store.nameExists(name)) {
                store.dispose(profile);
                throw new DataExportPlugin.HttpException(
                        409, "a data source with that name already exists");
            }
            store.add(profile);
            store.checkForErrors();
            if (!store.isRegistered(profile)) {
                throw new IllegalStateException("DBeaver did not register the profile");
            }
            if (!store.isPersisted(profile)) {
                throw new IllegalStateException("DBeaver did not confirm profile persistence");
            }
            return directSuccess(profile.identity);
        } catch (DataExportPlugin.HttpException collision) {
            throw collision;
        } catch (Exception failure) {
            CleanupOutcome cleanup = profile == null
                    ? new CleanupOutcome(false, false, false, true)
                    : cleanup(store, profile);
            DBeaverWorkspace.Identity identity = profile == null ? null : profile.identity;
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("error", "DBeaver could not create and persist the requested disconnected profile");
            body.put("code", "registration_failed");
            body.put("created", cleanup.created);
            body.put("saved", cleanup.saved);
            body.put("connected", false);
            if (identity != null) body.put("datasource", identity.asMap());
            body.put("cleanup", cleanup.asMap());
            return new Result(500, body);
        }
    }

    private static Result directSuccess(DBeaverWorkspace.Identity identity) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("created", true);
        body.put("saved", true);
        body.put("connected", false);
        body.put("datasource", identity.asMap());
        return new Result(201, body);
    }

    private static CleanupOutcome cleanup(DirectStore store, Profile profile) {
        boolean initiallyRegistered = false;
        boolean removeAttempted = false;
        boolean cleanupComplete = false;
        try {
            initiallyRegistered = store.isRegistered(profile);
            if (initiallyRegistered) {
                removeAttempted = true;
                store.remove(profile);
                store.checkForErrors();
            } else {
                store.dispose(profile);
            }
            boolean registered = store.isRegistered(profile);
            boolean persisted = store.isPersisted(profile);
            cleanupComplete = !registered && !persisted;
            return new CleanupOutcome(registered || persisted, persisted,
                    removeAttempted, cleanupComplete);
        } catch (Exception failure) {
            boolean registered = initiallyRegistered;
            boolean persisted = false;
            try { registered = store.isRegistered(profile); }
            catch (Exception ignored) { }
            try { persisted = store.isPersisted(profile); }
            catch (Exception ignored) { }
            return new CleanupOutcome(registered || persisted, persisted,
                    removeAttempted, false);
        }
    }


    private static boolean nameExists(Object registry, String name)
            throws Exception {
        Object dataSources = DataExportPlugin.call(registry, "getDataSources");
        if (!(dataSources instanceof Iterable)) return false;
        for (Object dataSource : (Iterable<?>) dataSources) {
            Object currentName = DataExportPlugin.call(dataSource, "getName");
            if (currentName instanceof String
                    && name.equalsIgnoreCase((String) currentName)) return true;
        }
        return false;
    }

    private static Set<String> ids(Object registry) throws Exception {
        Set<String> ids = new HashSet<String>();
        Object dataSources = DataExportPlugin.call(registry, "getDataSources");
        if (!(dataSources instanceof Iterable)) {
            throw new IllegalStateException("data source registry returned no profile list");
        }
        for (Object dataSource : (Iterable<?>) dataSources) {
            Object id = DataExportPlugin.call(dataSource, "getId");
            if (id instanceof String) ids.add((String) id);
        }
        return ids;
    }

    private static Map<String, Set<String>> snapshotIdsByProject()
            throws Exception {
        Map<String, Set<String>> result =
                new LinkedHashMap<String, Set<String>>();
        for (DBeaverWorkspace.ProjectContext project : DBeaverWorkspace.projects()) {
            if (project.registry != null) {
                result.put(project.id, ids(project.registry));
            }
        }
        return result;
    }


    private static Profile nativeProfile(Object descriptor) throws Exception {
        return new Profile(descriptor, DBeaverWorkspace.identity(descriptor));
    }

    private static final class NativeDirectStore implements DirectStore {
        private final DBeaverWorkspace.ProjectContext project;
        private NativeDirectStore(DBeaverWorkspace.ProjectContext project) { this.project = project; }

        @Override public Set<String> ids() throws Exception {
            return DBeaverConnections.ids(project.registry);
        }

        @Override public boolean nameExists(String name) throws Exception {
            return DBeaverConnections.nameExists(project.registry, name);
        }

        @Override public Profile create(String name, Object driver,
                Object configuration) throws Exception {
            Object descriptor = DataExportPlugin.call(project.registry,
                    "createDataSource", DataExportPlugin.arr(driver, configuration));
            if (descriptor == null) return null;
            DataExportPlugin.call(descriptor, "setName", DataExportPlugin.arr(name));
            return nativeProfile(descriptor);
        }

        @Override public void add(Profile profile) throws Exception {
            DataExportPlugin.call(project.registry, "addDataSource",
                    DataExportPlugin.arr(profile.handle));
        }

        @Override public boolean isRegistered(Profile profile) throws Exception {
            Object current = DataExportPlugin.call(project.registry,
                    "getDataSource", DataExportPlugin.arr(profile.identity.id));
            return current == profile.handle;
        }

        @Override public void checkForErrors() throws Exception {
            DataExportPlugin.call(project.registry, "checkForErrors");
            Object error = DataExportPlugin.call(project.registry, "getLastError");
            if (error != null) throw new IllegalStateException("registry reported a save error");
        }

        @Override public boolean isPersisted(Profile profile) throws Exception {
            ConnectionSettings.StoredProfile stored = readPersisted(profile);
            return stored != null && stored.matches;
        }

        ConnectionSettings.StoredProfile readPersisted(Profile profile)
                throws Exception {
            Object storage = DataExportPlugin.call(
                    profile.handle, "getConfigurationStorage");
            String storageName = storage == null ? null
                    : string(DataExportPlugin.call(storage, "getStorageName"));
            if (storageName == null || storageName.isEmpty()) {
                throw new IllegalStateException("profile storage name is unavailable");
            }
            Object manager = DataExportPlugin.call(project.registry,
                    "getConfigurationManager");
            if (manager == null) {
                throw new IllegalStateException("profile storage cannot be inspected");
            }
            Object stream = DataExportPlugin.call(manager, "readConfiguration",
                    DataExportPlugin.arr(storageName,
                            Collections.singleton(profile.identity.id)));
            if (!(stream instanceof InputStream)) return null;
            try (InputStream input = (InputStream) stream) {
                return ConnectionSettings.readPersisted(input, profile.identity);
            }
        }

        @Override public void remove(Profile profile) throws Exception {
            DataExportPlugin.call(project.registry, "removeDataSource",
                    DataExportPlugin.arr(profile.handle));
        }

        @Override public void dispose(Profile profile) throws Exception {
            DataExportPlugin.call(profile.handle, "dispose");
        }
    }



    private static boolean directRequirementsSupported(Object driver)
            throws Exception {
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        Object main = DataExportPlugin.call(driver, "getMainPropertyDescriptors");
        if (hasSensitiveRequiredProperty(main, providerId)) return false;
        Object provider = DataExportPlugin.call(
                driver, "getProviderPropertyDescriptors");
        if (provider == null || !provider.getClass().isArray()) return true;
        for (int i = 0; i < Array.getLength(provider); i++) {
            Object descriptor = Array.get(provider, i);
            if (!Boolean.TRUE.equals(call(descriptor, "isRequired"))) continue;
            if (safeDefault(descriptor, providerId) == null) return false;
        }
        return true;
    }

    private static boolean hasSensitiveRequiredProperty(Object descriptors,
            String providerId) throws Exception {
        if (descriptors == null || !descriptors.getClass().isArray()) return false;
        for (int i = 0; i < Array.getLength(descriptors); i++) {
            Object descriptor = Array.get(descriptors, i);
            if (!Boolean.TRUE.equals(call(descriptor, "isRequired"))) continue;
            String id = string(call(descriptor, "getId"));
            if (!ConnectionSettings.safePropertyName(providerId, id)
                    || sensitiveDescriptor(descriptor)) return true;
        }
        return false;
    }

    private static void validateDirectRequiredProperties(CreationRequest spec,
            Object driver) throws Exception {
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        Map<?, ?> supplied = spec.connection.get("properties") instanceof Map
                ? (Map<?, ?>) spec.connection.get("properties")
                : Collections.emptyMap();
        Object main = DataExportPlugin.call(driver, "getMainPropertyDescriptors");
        validateRequiredProperties(main, supplied, false, providerId);
        Object provider = DataExportPlugin.call(
                driver, "getProviderPropertyDescriptors");
        validateRequiredProperties(provider, Collections.emptyMap(), true, providerId);
    }

    private static void validateRequiredProperties(Object descriptors,
            Map<?, ?> supplied, boolean providerProperties, String providerId)
            throws Exception {
        if (descriptors == null || !descriptors.getClass().isArray()) return;
        for (int i = 0; i < Array.getLength(descriptors); i++) {
            Object descriptor = Array.get(descriptors, i);
            if (!Boolean.TRUE.equals(call(descriptor, "isRequired"))) continue;
            String id = string(call(descriptor, "getId"));
            if (supplied.containsKey(id)) {
                Object value = supplied.get(id);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    continue;
                }
                throw unsupported("a required driver property is empty; choose guided mode");
            }
            if (safeDefault(descriptor, providerId) != null) continue;
            throw unsupported(providerProperties
                    ? "a required provider property has no safe default; choose guided mode"
                    : "a required driver property is missing; choose guided mode");
        }
    }

    private static void applyPropertyDefaults(Object configuration, Object driver,
            String descriptorMethod, String setterMethod, Object supplied)
            throws Exception {
        Object descriptors = DataExportPlugin.call(driver, descriptorMethod);
        if (descriptors == null || !descriptors.getClass().isArray()) return;
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        Map<?, ?> present = supplied instanceof Map
                ? (Map<?, ?>) supplied : Collections.emptyMap();
        for (int i = 0; i < Array.getLength(descriptors); i++) {
            Object descriptor = Array.get(descriptors, i);
            String id = string(call(descriptor, "getId"));
            String value = safeDefault(descriptor, providerId);
            if (id != null && value != null && !present.containsKey(id)) {
                DataExportPlugin.call(configuration, setterMethod,
                        DataExportPlugin.arr(id, value));
            }
        }
    }

    private static String safeDefault(Object descriptor, String providerId)
            throws Exception {
        String id = string(call(descriptor, "getId"));
        if (!ConnectionSettings.safePropertyName(providerId, id)
                || sensitiveDescriptor(descriptor)) {
            return null;
        }
        Object value = call(descriptor, "getDefaultValue");
        if (!(value instanceof Number || value instanceof Boolean)) return null;
        String text = String.valueOf(value);
        return !text.trim().isEmpty() && propertyValueMatches(descriptor, text)
                ? text : null;
    }



    private static void requireKeys(Map<?, ?> object, String... allowed) {
        Set<String> keys = new HashSet<String>(Arrays.asList(allowed));
        for (Object key : object.keySet()) {
            if (!(key instanceof String) || !keys.contains(key)) {
                throw badRequest("request contains an unsupported field");
            }
        }
    }

    private static String requiredString(Map<String, Object> object, String key) {
        String value = optionalString(object, key);
        if (value == null || value.trim().isEmpty()) {
            throw badRequest("missing or invalid " + key);
        }
        return value;
    }

    private static String optionalString(Map<String, Object> object, String key) {
        if (!object.containsKey(key)) return null;
        Object value = object.get(key);
        if (!(value instanceof String)) throw badRequest(key + " must be a string");
        return (String) value;
    }

    private static boolean validId(String id) {
        return id != null && !id.trim().isEmpty() && !hasControls(id);
    }

    private static boolean validName(String name) {
        if (name.length() == 0 || name.length() > 128 || name.trim().isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }
    private static boolean hasControls(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }


    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static RuntimeException badRequest(String message) {
        return new DataExportPlugin.HttpException(400, message);
    }

    private static RuntimeException unsupported(String message) {
        return new DataExportPlugin.HttpException(422, message);
    }

    private static Object[] arr(Object... values) {
        return DataExportPlugin.arr(values);
    }

    static final class CreationRequest {
        final String projectId, mode, providerId, driverId, name;
        final Map<String, Object> connection;
        CreationRequest(String projectId, String mode, String providerId,
                String driverId, String name, Map<String, Object> connection) {
            this.projectId = projectId;
            this.mode = mode;
            this.providerId = providerId;
            this.driverId = driverId;
            this.name = name;
            this.connection = connection;
        }
    }

    private static final class DriverInfo {
        final Set<String> configurationTypes, supportedFields;
        final Map<String, Object> typeValues;
        final boolean directCapable;
        DriverInfo(Set<String> configurationTypes, Set<String> supportedFields,
                Map<String, Object> typeValues, boolean directCapable) {
            this.configurationTypes = configurationTypes;
            this.supportedFields = supportedFields;
            this.typeValues = typeValues;
            this.directCapable = directCapable;
        }
    }


    private static final class WizardDialogState {
        final Object wizard, shell, registry;
        WizardDialogState(Object wizard, Object shell, Object registry) {
            this.wizard = wizard;
            this.shell = shell;
            this.registry = registry;
        }
    }

    static final class Profile {
        final Object handle;
        final DBeaverWorkspace.Identity identity;
        Profile(Object handle, DBeaverWorkspace.Identity identity) {
            this.handle = handle;
            this.identity = identity;
        }
    }

    interface DirectStore {
        Set<String> ids() throws Exception;
        boolean nameExists(String name) throws Exception;
        Profile create(String name, Object driver, Object configuration) throws Exception;
        void add(Profile profile) throws Exception;
        boolean isRegistered(Profile profile) throws Exception;
        void checkForErrors() throws Exception;
        boolean isPersisted(Profile profile) throws Exception;
        void remove(Profile profile) throws Exception;
        void dispose(Profile profile) throws Exception;
    }

    static final class CleanupOutcome {
        final boolean created, saved, attempted, completed;
        CleanupOutcome(boolean created, boolean saved, boolean attempted,
                boolean completed) {
            this.created = created;
            this.saved = saved;
            this.attempted = attempted;
            this.completed = completed;
        }
        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("attempted", attempted);
            value.put("completed", completed);
            return value;
        }
    }

    static final class EditRequest {
        final String name;
        final Map<String, Object> connection;
        EditRequest(String name, Map<String, Object> connection) {
            this.name = name;
            this.connection = connection;
        }
    }

    static final class Operation {
        final String id, kind;
        volatile DBeaverWorkspace.ProjectContext project;
        volatile Object driver, configuration, targetDescriptor;
        volatile CreationRequest spec;
        volatile EditRequest editRequest;
        volatile Map<String, Object> initialSettings;
        volatile Set<String> beforeIds;
        volatile Map<String, Set<String>> beforeIdsByProject;
        volatile String state = "queued";
        volatile long updatedAt, timeoutMillis;
        volatile boolean created, saved, closing, uiStarted;
        volatile boolean continuationPending;
        volatile String code, error;
        volatile Object wizard, shell, dialog, pendingSubstitution;
        volatile DBeaverWorkspace.Identity identity;
        volatile CleanupOutcome cleanup;
        volatile Map<String, Object> test, diagnostic;
        Operation(String id) {
            this(id, "create", null, null, null, null,
                    Collections.<String>emptySet(), System.currentTimeMillis());
        }
        Operation(String id, String kind, DBeaverWorkspace.ProjectContext project,
                Object driver, CreationRequest spec, Object configuration,
                Set<String> beforeIds, long now) {
            this.id = id;
            this.kind = kind;
            this.project = project;
            this.driver = driver;
            this.spec = spec;
            this.configuration = configuration;
            this.beforeIds = beforeIds;
            this.updatedAt = now;
        }
        boolean terminal() {
            return "saved".equals(state) || "succeeded".equals(state)
                    || "cancelled".equals(state) || "failed".equals(state);
        }
        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("operationId", id);
            result.put("kind", kind);
            result.put("state", state);
            result.put("created", created);
            result.put("saved", saved);
            result.put("connected", identity != null && identity.connected);
            if (identity != null) result.put("datasource", identity.asMap());
            if (test != null) result.put("test", test);
            if (diagnostic != null) result.put("diagnostic", diagnostic);
            if (code != null) result.put("code", code);
            if (error != null) result.put("error", error);
            if (cleanup != null) result.put("cleanup", cleanup.asMap());
            return result;
        }
    }

    static final class Result {
        final int status;
        final Map<String, Object> body;
        Result(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }
    }

}
