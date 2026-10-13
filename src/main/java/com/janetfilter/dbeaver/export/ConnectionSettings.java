package com.janetfilter.dbeaver.export;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates, applies and safely inspects explicit DBeaver connection settings. */
final class ConnectionSettings {
    private static final int MAX_PROPERTY_COUNT = 64;
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final long MAX_READBACK_CHARS = 64L * 1024L * 1024L;
    private static final int MAX_JSON_DEPTH = 32;
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|passphrase|token|secret|credential|auth|authorization|username|user|access[_-]?key|api[_-]?key|private[_-]?key|client[_-]?key).*"
    );
    private static final Pattern COMMAND_NAME = Pattern.compile(
            "(?i).*(shell|command|executable|script).*"
    );
    private static final Pattern QUERY_PARAMETER = Pattern.compile(
            "(?i)(?:^|[?&#;])\\s*([A-Za-z0-9_.-]+)\\s*="
    );
    private static final Set<String> CONNECTION_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("host", "port", "database",
                    "server", "url", "properties", "providerProperties")));
    private static final Set<String> SCALAR_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("host", "port", "database",
                    "server", "url")));
    private static final Set<String> MONGO_AUTH_MECHANISMS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("NONE", "SCRAM-SHA-1",
                    "SCRAM-SHA-256", "MONGODB-CR", "PLAIN", "GSSAPI")));

    private ConnectionSettings() { }

    static Map<String, Object> parse(String providerId, Object raw) {
        if (!(raw instanceof Map)) throw badRequest("connection must be an object");
        Map<?, ?> source = (Map<?, ?>) raw;
        for (Object key : source.keySet()) {
            if (!(key instanceof String) || !CONNECTION_KEYS.contains(key)) {
                throw badRequest("connection contains an unsupported field");
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String key : SCALAR_KEYS) {
            if (!source.containsKey(key)) continue;
            Object value = source.get(key);
            if ("port".equals(key) && value instanceof Number) {
                try {
                    BigDecimal number = value instanceof BigDecimal
                            ? (BigDecimal) value : new BigDecimal(value.toString());
                    value = Integer.toString(number.intValueExact());
                } catch (ArithmeticException | NumberFormatException invalid) {
                    throw badRequest("port must be an integer between 1 and 65535");
                }
            }
            if (!(value instanceof String)) {
                throw badRequest("connection fields must be strings, except integer port");
            }
            String text = (String) value;
            if (text.length() > MAX_VALUE_LENGTH || hasControls(text)
                    || ("url".equals(key) && containsCredentials(providerId, text))) {
                throw badRequest("connection fields must be safe strings");
            }
            if ("port".equals(key) && !validPort(text)) {
                throw badRequest("port must be between 1 and 65535");
            }
            result.put(key, text);
        }
        if (source.containsKey("properties")) {
            result.put("properties", stringMap(providerId,
                    source.get("properties"), false));
        }
        if (source.containsKey("providerProperties")) {
            result.put("providerProperties", stringMap(providerId,
                    source.get("providerProperties"), true));
        }
        return result;
    }

    static boolean safePropertyName(String providerId, String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_VALUE_LENGTH
                || hasControls(name) || COMMAND_NAME.matcher(name).matches()) return false;
        if (isMongoAuthSetting(providerId, name)) return true;
        return !SECRET_NAME.matcher(name).matches();
    }

    static boolean containsCredentials(String providerId, String value) {
        if (value == null) return false;
        if (credentialSyntax(providerId, value)) return true;
        if (value.indexOf('%') < 0) return false;
        try {
            return credentialSyntax(providerId,
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()));
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException malformed) {
            return true;
        }
    }

    static void validateForDriver(Object driver, Map<String, Object> settings)
            throws Exception {
        if (settings == null) throw badRequest("connection settings are required");
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        validateParsedForDriver(driver, providerId, parse(providerId, settings));
    }

    private static void validateParsedForDriver(Object driver, String providerId,
            Map<String, Object> settings) throws Exception {
        Set<String> supportedFields = new HashSet<String>();
        Object fields = DataExportPlugin.call(driver, "getSupportedPageFields");
        if (fields instanceof Iterable) {
            for (Object field : (Iterable<?>) fields) {
                if (field != null) supportedFields.add(String.valueOf(field));
            }
        }
        Set<String> configurationTypes = new HashSet<String>();
        Object types = DataExportPlugin.call(driver, "getSupportedConfigurationTypes");
        if (types instanceof Iterable) {
            for (Object type : (Iterable<?>) types) {
                if (type != null) configurationTypes.add(type.toString());
            }
        }
        if (configurationTypes.contains("URL")) supportedFields.add("url");
        for (String field : Arrays.asList("host", "port", "database", "server", "url")) {
            if (settings.containsKey(field) && !supportedFields.contains(field)) {
                throw unsupported("connection field '" + field
                        + "' is not supported by this driver");
            }
        }
        if (settings.containsKey("url") && !configurationTypes.contains("URL")) {
            throw unsupported("this driver does not support URL configuration");
        }
        if (!settings.containsKey("url") && hasManualHints(settings)
                && !configurationTypes.contains("MANUAL")) {
            throw unsupported("manual configuration is unavailable for these settings");
        }
        validatePropertyMap(providerId, settings.get("properties"),
                DataExportPlugin.call(driver, "getMainPropertyDescriptors"), false);
        validatePropertyMap(providerId, settings.get("providerProperties"),
                DataExportPlugin.call(driver, "getProviderPropertyDescriptors"), true);
        mongoUriDetails(providerId, settings);
    }

    static void apply(Object configuration, Object driver,
            Map<String, Object> settings) throws Exception {
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        settings = parse(providerId, settings);
        validateParsedForDriver(driver, providerId, settings);
        MongoUriDetails mongo = mongoUriDetails(providerId, settings);
        setOptional(configuration, "setHostName", settings.get("host"));
        setOptional(configuration, "setHostPort", settings.get("port"));
        setOptional(configuration, "setDatabaseName",
                mongo.database != null ? mongo.database : settings.get("database"));
        setOptional(configuration, "setServerName", settings.get("server"));
        setOptional(configuration, "setUrl", settings.get("url"));
        applyProperties(configuration, "setProperty", settings.get("properties"));
        Map<String, Object> providerProperties = new LinkedHashMap<String, Object>();
        if (settings.get("providerProperties") instanceof Map) {
            providerProperties.putAll((Map<String, Object>) settings.get("providerProperties"));
        }
        for (Map.Entry<String, String> option : mongo.options.entrySet()) {
            String supplied = (String) providerProperties.get(option.getKey());
            if (supplied != null && !supplied.equals(option.getValue())) {
                throw unsupported("MongoDB URI and provider property settings conflict");
            }
            if (supplied == null) providerProperties.put(option.getKey(), option.getValue());
        }
        applyProperties(configuration, "setProviderProperty", providerProperties);
        String configurationType = settings.containsKey("url") ? "URL"
                : hasManualHints(settings) ? "MANUAL" : null;
        if (configurationType != null) {
            Object supported = DataExportPlugin.call(driver,
                    "getSupportedConfigurationTypes");
            Object selected = null;
            if (supported instanceof Iterable) {
                for (Object type : (Iterable<?>) supported) {
                    if (type != null && configurationType.equals(type.toString())) {
                        selected = type;
                        break;
                    }
                }
            }
            if (selected == null) {
                throw unsupported(configurationType
                        + " configuration is unavailable for this driver");
            }
            DataExportPlugin.call(configuration, "setConfigurationType",
                    DataExportPlugin.arr(selected));
        }
    }

    static Map<String, Object> snapshot(Object driver, Object configuration)
            throws Exception {
        String providerId = string(DataExportPlugin.call(driver, "getProviderId"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object configurationType = optionalCall(configuration, "getConfigurationType");
        if (configurationType instanceof Enum) {
            result.put("configurationType", ((Enum<?>) configurationType).name());
        } else {
            putSafeScalar(result, "configurationType", configurationType, providerId);
        }
        putSafeScalar(result, "host", optionalCall(configuration, "getHostName"), providerId);
        putSafeScalar(result, "port", optionalCall(configuration, "getHostPort"), providerId);
        putSafeScalar(result, "database", optionalCall(configuration, "getDatabaseName"), providerId);
        putSafeScalar(result, "server", optionalCall(configuration, "getServerName"), providerId);
        MongoUriDetails mongo = MongoUriDetails.EMPTY;
        Object url = optionalCall(configuration, "getUrl");
        if (url instanceof String && exposableUrl(providerId, (String) url)) {
            try {
                mongo = mongoUriDetails(providerId, scalarSettings(result, url));
                result.put("url", url);
            } catch (DataExportPlugin.HttpException invalid) {
                // Invalid or conflicting URI settings are not safe to export.
            }
        }
        Map<String, String> main = snapshotProperties(providerId,
                optionalCall(configuration, "getProperties"),
                DataExportPlugin.call(driver, "getMainPropertyDescriptors"), false);
        Map<String, String> provider = snapshotProperties(providerId,
                optionalCall(configuration, "getProviderProperties"),
                DataExportPlugin.call(driver, "getProviderPropertyDescriptors"), true);
        if (mongo.database != null && !result.containsKey("database")) {
            result.put("database", mongo.database);
        }
        for (Map.Entry<String, String> option : mongo.options.entrySet()) {
            if (!provider.containsKey(option.getKey())) {
                provider.put(option.getKey(), option.getValue());
            }
        }
        result.put("properties", main);
        result.put("providerProperties", provider);
        return result;
    }

    static StoredProfile readPersisted(InputStream input,
            DBeaverWorkspace.Identity expected) throws Exception {
        if (input == null || expected == null) throw new IllegalArgumentException(
                "profile storage input and expected identity are required");
        Reader bounded = new BoundedReader(new InputStreamReader(input,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)), MAX_READBACK_CHARS);
        try (PushbackReader reader = new PushbackReader(new BufferedReader(bounded), 1)) {
            int first;
            do {
                first = reader.read();
            } while (first >= 0 && (Character.isWhitespace(first) || first == '\uFEFF'));
            if (first < 0) return new StoredProfile(false, Collections.<String, Object>emptyMap());
            reader.unread(first);
            if (first == '{') return readJson(reader, expected);
            if (first == '<') return readXml(reader, expected);
            while (reader.read() >= 0) { }
            return new StoredProfile(false, Collections.<String, Object>emptyMap());
        }
    }

    private static Map<String, Object> stringMap(String providerId, Object raw,
            boolean providerProperties) {
        if (!(raw instanceof Map)) throw badRequest("connection property maps must be objects");
        Map<?, ?> source = (Map<?, ?>) raw;
        if (source.size() > MAX_PROPERTY_COUNT) throw badRequest("too many connection properties");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw badRequest("connection property keys and values must be strings");
            }
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            boolean mongoAuth = providerProperties && isMongoAuthSetting(providerId, key);
            if (key.isEmpty() || key.length() > MAX_VALUE_LENGTH || hasControls(key)
                    || !safePropertyName(providerId, key) || (!providerProperties
                    && isMongoAuthSetting(providerId, key))) {
                throw badRequest("connection property key is unsupported or sensitive");
            }
            if (value.length() > MAX_VALUE_LENGTH || hasControls(value)
                    || ((urlLike(value) || mongoAuth)
                    && containsCredentials(providerId, value))) {
                throw badRequest("connection property value is invalid or sensitive");
            }
            if (mongoAuth && !validMongoAuthSetting(key, value)) {
                throw badRequest("MongoDB authentication setting is invalid");
            }
            result.put(key, value);
        }
        return result;
    }

    private static void validatePropertyMap(String providerId, Object supplied,
            Object descriptors, boolean providerProperties) throws Exception {
        if (!(supplied instanceof Map) || ((Map<?, ?>) supplied).isEmpty()) return;
        Map<String, Object> supported = new HashMap<String, Object>();
        Set<String> described = new HashSet<String>();
        if (descriptors != null && descriptors.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(descriptors); i++) {
                Object descriptor = Array.get(descriptors, i);
                String id = string(DataExportPlugin.call(descriptor, "getId"));
                if (id != null && isMongoAuthSetting(providerId, id)) described.add(id);
                if (id != null && safePropertyName(providerId, id)
                        && (!isMongoAuthSetting(providerId, id)
                        ? !sensitiveDescriptor(descriptor)
                        : safeMongoAuthDescriptor(descriptor))) {
                    supported.put(id, descriptor);
                }
            }
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) supplied).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw unsupported("connection property has an invalid type");
            }
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            boolean mongoAuth = providerProperties && isMongoAuthSetting(providerId, key);
            if ((!providerProperties && isMongoAuthSetting(providerId, key))
                    || ((urlLike(value) || mongoAuth)
                    && containsCredentials(providerId, value))) {
                throw unsupported("connection property is unsupported or sensitive");
            }
            Object descriptor = supported.get(key);
            if (mongoAuth && descriptor == null && described.contains(key)) {
                throw unsupported("MongoDB authentication setting is not safe");
            }
            if (mongoAuth) {
                if (!validMongoAuthSetting(key, value)) {
                    throw unsupported("MongoDB authentication setting is invalid");
                }
                if (descriptor != null && !propertyValueMatches(descriptor, value)) {
                    throw unsupported("connection property value has an invalid type");
                }
                continue;
            }
            if (descriptor == null) {
                throw unsupported(providerProperties
                        ? "provider property is not supported"
                        : "driver property is not supported");
            }
            if (!propertyValueMatches(descriptor, value)) {
                throw unsupported("connection property value has an invalid type");
            }
        }
    }

    private static boolean propertyValueMatches(Object descriptor, String value)
            throws Exception {
        Object typeValue = DataExportPlugin.call(descriptor, "getDataType");
        if (!(typeValue instanceof Class)) return false;
        Class<?> type = (Class<?>) typeValue;
        try {
            if (type == String.class) return true;
            if (type == Boolean.class || type == boolean.class) {
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            }
            if (type == Byte.class || type == byte.class) Byte.parseByte(value);
            else if (type == Short.class || type == short.class) Short.parseShort(value);
            else if (type == Integer.class || type == int.class) Integer.parseInt(value);
            else if (type == Long.class || type == long.class) Long.parseLong(value);
            else if (type == Float.class || type == float.class) Float.parseFloat(value);
            else if (type == Double.class || type == double.class) Double.parseDouble(value);
            else if (type == Character.class || type == char.class) return value.length() == 1;
            else if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(value)) return true;
                }
                return false;
            } else return false;
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean sensitiveDescriptor(Object descriptor) throws Exception {
        Object features = DataExportPlugin.call(descriptor, "getFeatures");
        if (features != null && features.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(features); i++) {
                Object feature = Array.get(features, i);
                if (feature != null && sensitiveFeature(feature.toString())) return true;
            }
        }
        for (String feature : Arrays.asList("secret", "sensitive", "password",
                "credential", "auth", "hidden")) {
            if (Boolean.TRUE.equals(optionalCall(descriptor, "hasFeature",
                    DataExportPlugin.arr(feature)))) return true;
        }
        return Boolean.TRUE.equals(optionalCall(descriptor, "isHidden"));
    }

    private static boolean safeMongoAuthDescriptor(Object descriptor) throws Exception {
        Object features = DataExportPlugin.call(descriptor, "getFeatures");
        if (features != null && features.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(features); i++) {
                Object feature = Array.get(features, i);
                if (feature != null && explicitSecretFeature(feature.toString())) return false;
            }
        }
        for (String feature : Arrays.asList("secret", "sensitive", "password",
                "credential", "hidden")) {
            if (Boolean.TRUE.equals(optionalCall(descriptor, "hasFeature",
                    DataExportPlugin.arr(feature)))) return false;
        }
        if (Boolean.TRUE.equals(optionalCall(descriptor, "isHidden"))) return false;
        Object type = DataExportPlugin.call(descriptor, "getDataType");
        return type == String.class;
    }

    private static boolean sensitiveFeature(String feature) {
        return explicitSecretFeature(feature) || feature.toLowerCase(Locale.ROOT).contains("auth");
    }

    private static boolean explicitSecretFeature(String feature) {
        String lower = feature.toLowerCase(Locale.ROOT);
        return lower.contains("secret") || lower.contains("sensitive")
                || lower.contains("password") || lower.contains("credential")
                || lower.contains("hidden");
    }

    private static boolean validMongoAuthSetting(String key, String value) {
        if ("authSource".equals(key)) {
            return !value.trim().isEmpty() && value.length() <= MAX_VALUE_LENGTH
                    && !hasControls(value) && !value.contains("/")
                    && !value.contains("?") && !value.contains("#");
        }
        if ("authMechanism".equals(key)) return MONGO_AUTH_MECHANISMS.contains(value);
        return false;
    }

    private static MongoUriDetails mongoUriDetails(String providerId,
            Map<String, Object> settings) {
        if (!isMongoProvider(providerId) || !(settings.get("url") instanceof String)) {
            return MongoUriDetails.EMPTY;
        }
        String value = (String) settings.get("url");
        if (!(value.regionMatches(true, 0, "mongo://", 0, 8)
                || value.regionMatches(true, 0, "mongodb://", 0, 10)
                || value.regionMatches(true, 0, "mongodb+srv://", 0, 14))) {
            return MongoUriDetails.EMPTY;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!"mongo".equalsIgnoreCase(scheme)
                    && !"mongodb".equalsIgnoreCase(scheme)
                    && !"mongodb+srv".equalsIgnoreCase(scheme)) {
                throw unsupported("MongoDB URL has an unsupported scheme");
            }
            if (uri.getRawUserInfo() != null) {
                throw unsupported("connection URLs must not contain credentials");
            }
            String path = uri.getPath();
            String database = null;
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                if (!path.startsWith("/") || path.indexOf('/', 1) >= 0) {
                    throw unsupported("MongoDB URL contains an invalid database path");
                }
                database = path.substring(1);
                if (database.trim().isEmpty() || database.length() > MAX_VALUE_LENGTH
                        || hasControls(database)) {
                    throw unsupported("MongoDB URL contains an invalid database path");
                }
            }
            if (database != null && settings.get("database") instanceof String
                    && !database.equals(settings.get("database"))) {
                throw unsupported("MongoDB URL database conflicts with the database setting");
            }
            Map<String, String> options = parseMongoUriOptions(uri.getRawQuery());
            Object rawProvider = settings.get("providerProperties");
            if (rawProvider instanceof Map) {
                for (Map.Entry<String, String> option : options.entrySet()) {
                    Object provided = ((Map<?, ?>) rawProvider).get(option.getKey());
                    if (provided != null && !provided.equals(option.getValue())) {
                        throw unsupported("MongoDB URL and provider property settings conflict");
                    }
                }
            }
            return new MongoUriDetails(database, options);
        } catch (java.net.URISyntaxException invalid) {
            throw unsupported("MongoDB URL is invalid");
        }
    }

    private static Map<String, String> parseMongoUriOptions(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (String parameter : query.split("&", -1)) {
            int equals = parameter.indexOf('=');
            if (equals < 0) continue;
            String key;
            String value;
            try {
                key = URLDecoder.decode(parameter.substring(0, equals).replace("+", "%2B"),
                        StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(parameter.substring(equals + 1).replace("+", "%2B"),
                        StandardCharsets.UTF_8.name());
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException malformed) {
                throw unsupported("MongoDB URL query is invalid");
            }
            if (!isMongoAuthSetting("mongodb", key)) continue;
            if (!validMongoAuthSetting(key, value) || options.containsKey(key)) {
                throw unsupported("MongoDB URL authentication setting is invalid");
            }
            options.put(key, value);
        }
        return options;
    }

    private static boolean credentialSyntax(String providerId, String value) {
        Matcher matcher = QUERY_PARAMETER.matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (SECRET_NAME.matcher(name).matches()
                    && !isMongoAuthSetting(providerId, name)) return true;
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int authorityStart = scheme + 3;
            int authorityEnd = value.length();
            for (char delimiter : new char[] {'/', '?', ';', '#'}) {
                int end = value.indexOf(delimiter, authorityStart);
                if (end >= 0 && end < authorityEnd) authorityEnd = end;
            }
            int userInfo = value.indexOf('@', authorityStart);
            return userInfo >= 0 && userInfo < authorityEnd;
        }
        int userInfo = value.indexOf('@');
        return userInfo >= 0 && (userInfo == 0 || value.charAt(userInfo - 1) != ':');
    }

    private static Map<String, String> snapshotProperties(String providerId,
            Object values, Object descriptors, boolean providerProperties) throws Exception {
        Map<String, Object> visible = new HashMap<String, Object>();
        Set<String> described = new HashSet<String>();
        if (descriptors != null && descriptors.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(descriptors); i++) {
                Object descriptor = Array.get(descriptors, i);
                String id = string(DataExportPlugin.call(descriptor, "getId"));
                if (id != null && isMongoAuthSetting(providerId, id)) described.add(id);
                if (id != null && safePropertyName(providerId, id)
                        && (!isMongoAuthSetting(providerId, id)
                        ? !sensitiveDescriptor(descriptor)
                        : safeMongoAuthDescriptor(descriptor))) {
                    visible.put(id, descriptor);
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!(values instanceof Map)) return result;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) values).entrySet()) {
            if (!(entry.getKey() instanceof String)) continue;
            String key = (String) entry.getKey();
            if (!safePropertyName(providerId, key)
                    || isMongoAuthSetting(providerId, key) && !providerProperties) continue;
            Object descriptor = visible.get(key);
            if (descriptor == null && !(providerProperties
                    && isMongoAuthSetting(providerId, key) && !described.contains(key))) continue;
            String value = scalarString(entry.getValue());
            if (value == null || value.length() > MAX_VALUE_LENGTH || !safeScalar(value)
                    || containsCredentials(providerId, value)) continue;
            if (isMongoAuthSetting(providerId, key)
                    && !validMongoAuthSetting(key, value)) continue;
            result.put(key, value);
        }
        return result;
    }

    private static Map<String, Object> scalarSettings(Map<String, Object> initial,
            Object url) {
        Map<String, Object> settings = new LinkedHashMap<String, Object>(initial);
        if (url instanceof String) settings.put("url", url);
        return settings;
    }

    private static void putSafeScalar(Map<String, Object> result, String key,
            Object raw, String providerId) {
        String value = scalarString(raw);
        if (value != null && !value.isEmpty() && value.length() <= MAX_VALUE_LENGTH
                && safeScalar(value) && !containsCredentials(providerId, value)) {
            result.put(key, value);
        }
    }

    private static String scalarString(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static boolean safeScalar(String value) {
        return !hasControls(value);
    }

    private static Object optionalCall(Object target, String method) throws Exception {
        return optionalCall(target, method, null);
    }

    private static Object optionalCall(Object target, String method, Object[] args)
            throws Exception {
        if (target == null) return null;
        try {
            return args == null ? DataExportPlugin.call(target, method)
                    : DataExportPlugin.call(target, method, args);
        } catch (NoSuchMethodException unavailable) {
            return null;
        }
    }

    private static void setOptional(Object target, String method, Object value)
            throws Exception {
        if (value != null) DataExportPlugin.call(target, method,
                DataExportPlugin.arr(value));
    }

    private static void applyProperties(Object configuration, String method,
            Object properties) throws Exception {
        if (!(properties instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
            DataExportPlugin.call(configuration, method,
                    DataExportPlugin.arr(entry.getKey(), entry.getValue()));
        }
    }

    private static boolean hasManualHints(Map<String, Object> settings) {
        return settings.containsKey("host") || settings.containsKey("port")
                || settings.containsKey("database") || settings.containsKey("server");
    }

    private static boolean validPort(String value) {
        if (value.isEmpty()) return false;
        if (!value.matches("[0-9]{1,5}")) return false;
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean hasControls(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean urlLike(String value) {
        return value.regionMatches(true, 0, "jdbc:", 0, 5) || value.contains("://");
    }

    private static boolean exposableUrl(String providerId, String value) {
        if (!safeScalar(value) || containsCredentials(providerId, value)) return false;
        if (isMongoProvider(providerId)
                && (value.regionMatches(true, 0, "mongo://", 0, 8)
                || value.regionMatches(true, 0, "mongodb://", 0, 10)
                || value.regionMatches(true, 0, "mongodb+srv://", 0, 14))) {
            return true;
        }
        if (!value.regionMatches(true, 0, "jdbc:", 0, 5)) return false;
        String address = value.substring(5);
        if (address.regionMatches(true, 0, "sqlite:", 0, 7)) {
            return address.indexOf(';') < 0 && address.indexOf('?') < 0
                    && address.indexOf('#') < 0;
        }
        if (address.regionMatches(true, 0, "oracle:thin:@", 0, 12)
                || address.regionMatches(true, 0, "oracle:oci:@", 0, 11)) {
            return address.indexOf('(') < 0 && address.indexOf(';') < 0
                    && address.indexOf('?') < 0 && address.indexOf('#') < 0;
        }
        try {
            URI uri = new URI(address);
            return !uri.isOpaque() && uri.getRawAuthority() != null
                    && uri.getRawAuthority().indexOf(';') < 0
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (java.net.URISyntaxException invalid) {
            return false;
        }
    }

    private static boolean isMongoProvider(String providerId) {
        return "mongodb".equals(providerId);
    }

    private static boolean isMongoAuthSetting(String providerId, String name) {
        return isMongoProvider(providerId)
                && ("authSource".equals(name) || "authMechanism".equals(name));
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

    private static StoredProfile readJson(Reader reader,
            DBeaverWorkspace.Identity expected) throws Exception {
        try (JsonReader json = new JsonReader(reader)) {
            json.setStrictness(com.google.gson.Strictness.STRICT);
            JsonProfileScan scan = scanJsonRoot(json, expected);
            if (json.peek() != JsonToken.END_DOCUMENT) throw new IOException("invalid profile storage");
            if (scan.profile == null) return new StoredProfile(false, Collections.<String, Object>emptyMap());
            boolean matches = expected.id.equals(scan.profile.id)
                    && expected.name.equals(scan.profile.name)
                    && expected.providerId.equals(scan.profile.providerId)
                    && expected.driverId.equals(scan.profile.driverId);
            if (!matches) return new StoredProfile(false,
                    Collections.<String, Object>emptyMap());
            normalizeStoredMongoSettings(scan.profile.settings, expected.providerId);
            return new StoredProfile(true, scan.profile.settings);
        }
    }

    private static JsonProfileScan scanJsonRoot(JsonReader json,
            DBeaverWorkspace.Identity expected) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("invalid profile storage");
        json.beginObject();
        JsonProfile target = null;
        boolean connectionsSeen = false;
        while (json.hasNext()) {
            String name = json.nextName();
            if (!"connections".equals(name)) {
                json.skipValue();
                continue;
            }
            if (connectionsSeen) throw new IOException("duplicate profile storage");
            connectionsSeen = true;
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            target = scanJsonConnections(json, expected);
        }
        json.endObject();
        return new JsonProfileScan(target);
    }

    private static JsonProfile scanJsonConnections(JsonReader json,
            DBeaverWorkspace.Identity expected) throws IOException {
        json.beginObject();
        JsonProfile target = null;
        boolean targetSeen = false;
        while (json.hasNext()) {
            String id = json.nextName();
            if (!expected.id.equals(id)) {
                json.skipValue();
                continue;
            }
            if (targetSeen) throw new IOException("duplicate profile identity");
            targetSeen = true;
            target = scanJsonTarget(json, id, expected.providerId);
        }
        json.endObject();
        return target;
    }

    private static JsonProfile scanJsonTarget(JsonReader json, String profileId,
            String providerId) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return null;
        }
        json.beginObject();
        JsonProfile profile = new JsonProfile(profileId);
        Set<String> seen = new HashSet<String>();
        while (json.hasNext()) {
            String key = json.nextName();
            if (!seen.add(key) && ("id".equals(key) || "name".equals(key)
                    || "provider".equals(key) || "driver".equals(key)
                    || "configuration".equals(key))) {
                throw new IOException("duplicate profile setting");
            }
            if ("id".equals(key) || "name".equals(key)
                    || "provider".equals(key) || "driver".equals(key)) {
                if (json.peek() == JsonToken.STRING) {
                    String value = json.nextString();
                    if ("id".equals(key)) profile.id = value;
                    else if ("name".equals(key)) profile.name = value;
                    else if ("provider".equals(key)) profile.providerId = value;
                    else profile.driverId = value;
                } else json.skipValue();
            } else if ("configuration".equals(key)) {
                scanJsonConfiguration(json, profile.settings, providerId, 0);
            } else {
                json.skipValue();
            }
        }
        json.endObject();
        return profile;
    }

    private static void scanJsonConfiguration(JsonReader json,
            Map<String, Object> settings, String providerId, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) throw new IOException("profile storage nesting is too deep");
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return;
        }
        json.beginObject();
        Set<String> seen = new HashSet<String>();
        while (json.hasNext()) {
            String key = json.nextName();
            if (isSettingKey(key) && !seen.add(key)) throw new IOException("duplicate profile setting");
            if (SCALAR_KEYS.contains(key) || "configurationType".equals(key)) {
                String value = readSafeJsonScalar(json);
                if (value != null) putStoredScalar(settings, key, value, providerId);
            } else if ("properties".equals(key)) {
                readJsonProperties(json, settings, "properties", providerId, false, depth + 1);
            } else if ("providerProperties".equals(key)
                    || "provider-properties".equals(key)) {
                readJsonProperties(json, settings, "providerProperties", providerId, true, depth + 1);
            } else {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readJsonProperties(JsonReader json, Map<String, Object> settings,
            String resultKey, String providerId, boolean providerProperties, int depth)
            throws IOException {
        if (depth > MAX_JSON_DEPTH) throw new IOException("profile storage nesting is too deep");
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return;
        }
        json.beginObject();
        Map<String, String> values = new LinkedHashMap<String, String>();
        Set<String> seen = new HashSet<String>();
        while (json.hasNext()) {
            String key = json.nextName();
            String value = readSafeJsonScalar(json);
            if (!safePropertyName(providerId, key)
                    || isMongoAuthSetting(providerId, key) && !providerProperties) continue;
            if (seen.contains(key)) throw new IOException("duplicate profile property");
            if (seen.size() >= MAX_PROPERTY_COUNT) continue;
            seen.add(key);
            if (value == null || value.length() > MAX_VALUE_LENGTH || !safeScalar(value)
                    || containsCredentials(providerId, value)
                    || isMongoAuthSetting(providerId, key)
                    && !validMongoAuthSetting(key, value)) continue;
            values.put(key, value);
        }
        json.endObject();
        if (!values.isEmpty()) settings.put(resultKey, values);
    }

    private static String readSafeJsonScalar(JsonReader json) throws IOException {
        JsonToken token = json.peek();
        if (token == JsonToken.STRING) return json.nextString();
        if (token == JsonToken.NUMBER || token == JsonToken.BOOLEAN) return json.nextString();
        json.skipValue();
        return null;
    }

    private static void putStoredScalar(Map<String, Object> settings,
            String key, String value, String providerId) {
        if (value.length() > MAX_VALUE_LENGTH || !safeScalar(value)
                || containsCredentials(providerId, value)) return;
        if ("port".equals(key) && !validPort(value)) return;
        if ("url".equals(key) && !exposableUrl(providerId, value)) return;
        settings.put(key, value);
    }

    private static boolean isSettingKey(String key) {
        return SCALAR_KEYS.contains(key) || "configurationType".equals(key)
                || "properties".equals(key) || "providerProperties".equals(key)
                || "provider-properties".equals(key);
    }

    private static StoredProfile readXml(Reader input,
            DBeaverWorkspace.Identity expected) throws Exception {
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("external XML entities are disabled");
        });
        javax.xml.stream.XMLStreamReader xml = factory.createXMLStreamReader(input);
        XmlProfile target = null;
        XmlProfile current = null;
        int targetDepth = -1;
        int depth = 0;
        int skippedDepth = -1;
        int settingsDepth = -1;
        int providerPropertiesDepth = -1;
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == javax.xml.stream.XMLStreamConstants.DTD) {
                    throw new IOException("DTD is not allowed in profile storage");
                }
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = xml.getLocalName();
                    if (skippedDepth >= 0) continue;
                    if (credentialSection(name)) {
                        skippedDepth = depth;
                        continue;
                    }
                    if (targetDepth >= 0 && depth == targetDepth + 1
                            && ("data-source".equals(name) || "dataSource".equals(name))) {
                        throw new IOException("nested datasource in profile storage");
                    }
                    if (targetDepth < 0 && ("data-source".equals(name)
                            || "dataSource".equals(name))) {
                        String id = attribute(xml, "id");
                        if (expected.id.equals(id)) {
                            if (target != null) throw new IOException("duplicate profile identity");
                            current = new XmlProfile(id, attribute(xml, "name"),
                                    attribute(xml, "provider"), attribute(xml, "driver"));
                            target = current;
                            targetDepth = depth;
                        }
                        continue;
                    }
                    if (current == null) continue;
                    if ("connection".equals(name) || "configuration".equals(name)) {
                        if (settingsDepth < 0) settingsDepth = depth;
                        readXmlConnectionAttributes(xml, current.settings,
                                expected.providerId);
                    } else if (settingsDepth >= 0
                            && ("provider-properties".equals(name)
                            || "providerProperties".equals(name))) {
                        if (providerPropertiesDepth < 0) providerPropertiesDepth = depth;
                    } else if (settingsDepth >= 0
                            && ("property".equals(name) || "provider-property".equals(name)
                            || "providerProperty".equals(name))) {
                        String property = attribute(xml, "name");
                        String value = attribute(xml, "value");
                        boolean providerProperty = providerPropertiesDepth >= 0
                                || "provider-property".equals(name)
                                || "providerProperty".equals(name);
                        if (property != null && value != null) {
                            putXmlProperty(current.settings, property, value,
                                    expected.providerId, providerProperty);
                        }
                    }
                } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    if (skippedDepth == depth) skippedDepth = -1;
                    if (providerPropertiesDepth == depth) providerPropertiesDepth = -1;
                    if (settingsDepth == depth) {
                        settingsDepth = -1;
                        providerPropertiesDepth = -1;
                    }
                    if (targetDepth == depth) {
                        current = null;
                        targetDepth = -1;
                        settingsDepth = -1;
                        providerPropertiesDepth = -1;
                    }
                    depth--;
                }
            }
        } finally {
            xml.close();
        }
        if (target == null) return new StoredProfile(false, Collections.<String, Object>emptyMap());
        boolean matches = expected.id.equals(target.id) && expected.name.equals(target.name)
                && expected.providerId.equals(target.providerId)
                && expected.driverId.equals(target.driverId);
        if (matches) normalizeStoredMongoSettings(target.settings, expected.providerId);
        return new StoredProfile(matches, matches
                ? target.settings : Collections.<String, Object>emptyMap());
    }

    private static void normalizeStoredMongoSettings(Map<String, Object> settings,
            String providerId) throws IOException {
        MongoUriDetails details;
        try {
            details = mongoUriDetails(providerId, settings);
        } catch (RuntimeException invalid) {
            IOException failure = new IOException("stored connection settings are inconsistent");
            failure.initCause(invalid);
            throw failure;
        }
        if (details.database != null && !settings.containsKey("database")) {
            settings.put("database", details.database);
        }
        if (!details.options.isEmpty()) {
            Map<String, String> provider = new LinkedHashMap<String, String>();
            Object existing = settings.get("providerProperties");
            if (existing instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) existing).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                        provider.put((String) entry.getKey(), (String) entry.getValue());
                    }
                }
            }
            provider.putAll(details.options);
            settings.put("providerProperties", provider);
        }
    }

    private static void readXmlConnectionAttributes(javax.xml.stream.XMLStreamReader xml,
            Map<String, Object> settings, String providerId) {
        String type = attribute(xml, "type");
        if (type == null) type = attribute(xml, "configurationType");
        if (type != null && safeScalar(type)) settings.put("configurationType", type);
        for (String key : SCALAR_KEYS) {
            String attribute = "host".equals(key) ? "host" : key;
            String value = attribute(xml, attribute);
            if (value != null) putStoredScalar(settings, key, value, providerId);
        }
    }

    private static void putXmlProperty(Map<String, Object> settings, String name,
            String value, String providerId, boolean providerProperties) throws IOException {
        if (!safePropertyName(providerId, name)
                || isMongoAuthSetting(providerId, name) && !providerProperties
                || value.length() > MAX_VALUE_LENGTH || !safeScalar(value)
                || containsCredentials(providerId, value)
                || isMongoAuthSetting(providerId, name)
                && !validMongoAuthSetting(name, value)) return;
        String mapName = providerProperties ? "providerProperties" : "properties";
        Object existing = settings.get(mapName);
        Map<String, String> map = existing instanceof Map
                ? (Map<String, String>) existing : new LinkedHashMap<String, String>();
        if (map.containsKey(name)) throw new IOException("duplicate profile property");
        if (map.size() >= MAX_PROPERTY_COUNT) return;
        map.put(name, value);
        settings.put(mapName, map);
    }

    private static String attribute(javax.xml.stream.XMLStreamReader xml, String name) {
        return xml.getAttributeValue(null, name);
    }

    private static boolean credentialSection(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("credential") || lower.contains("auth-profile")
                || lower.contains("authprofile") || "authentication".equals(lower)
                || "credentials".equals(lower);
    }


    private static final class MongoUriDetails {
        static final MongoUriDetails EMPTY = new MongoUriDetails(null,
                Collections.<String, String>emptyMap());
        final String database;
        final Map<String, String> options;
        MongoUriDetails(String database, Map<String, String> options) {
            this.database = database;
            this.options = options;
        }
    }

    private static final class JsonProfileScan {
        final JsonProfile profile;
        JsonProfileScan(JsonProfile profile) { this.profile = profile; }
    }

    private static final class JsonProfile {
        String id, name, providerId, driverId;
        final Map<String, Object> settings = new LinkedHashMap<String, Object>();
        JsonProfile(String id) {
            this.id = id;
        }
    }

    private static final class XmlProfile {
        final String id, name, providerId, driverId;
        final Map<String, Object> settings = new LinkedHashMap<String, Object>();
        XmlProfile(String id, String name, String providerId, String driverId) {
            this.id = id;
            this.name = name;
            this.providerId = providerId;
            this.driverId = driverId;
        }
    }

    static final class StoredProfile {
        final boolean matches;
        final Map<String, Object> settings;
        StoredProfile(boolean matches, Map<String, Object> settings) {
            this.matches = matches;
            this.settings = settings;
        }
    }

    private static final class BoundedReader extends FilterReader {
        private final long limit;
        private long consumed;
        BoundedReader(Reader input, long limit) {
            super(input);
            this.limit = limit;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++consumed > limit) {
                throw new IOException("profile readback exceeded its size limit");
            }
            return value;
        }
        @Override public int read(char[] buffer, int offset, int length)
                throws IOException {
            if (length == 0) return 0;
            if (consumed >= limit) {
                int extra = super.read();
                if (extra < 0) return -1;
                throw new IOException("profile readback exceeded its size limit");
            }
            int allowed = (int) Math.min((long) length, limit - consumed);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) consumed += count;
            return count;
        }
    }
}
