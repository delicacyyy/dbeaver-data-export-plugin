package com.janetfilter.dbeaver.export.runtime;

import com.janetfilter.dbeaver.export.utils.HttpException;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

/** Classloader and reflective adapters for DBeaver's native runtime. */
public final class DBeaverRuntime {
    private static volatile ClassLoader dbeaverClassLoader;

    private DBeaverRuntime() { }

    public static void setClassLoader(ClassLoader classLoader) {
        dbeaverClassLoader = classLoader;
    }

    public static ClassLoader requireClassLoader() {
        ClassLoader classLoader = dbeaverClassLoader;
        if (classLoader == null) {
            throw new HttpException(503, "DBeaver runtime is unavailable");
        }
        return classLoader;
    }

    public static Class<?> c(String name) throws Exception {
        ClassLoader cl = dbeaverClassLoader;
        while (cl != null) {
            try { return Class.forName(name, true, cl); }
            catch (ClassNotFoundException ignored) { }
            cl = cl.getParent();
        }
        return Class.forName(name);
    }

    public static Class<?> providerClass(Object anchor, String name) throws Exception {
        return Class.forName(name, true, anchor.getClass().getClassLoader());
    }

    public static Object call(Object target, String method) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Method member = publicContractMethod(type, type.getMethod(method));
        return member.invoke(target instanceof Class ? null : target);
    }

    public static Object call(Object target, String method, Object[] args) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Method member : type.getMethods()) {
            if (member.getName().equals(method)
                    && member.getParameterCount() == args.length
                    && parametersCompatible(member.getParameterTypes(), args)) {
                return publicContractMethod(type, member)
                        .invoke(target instanceof Class ? null : target, args);
            }
        }
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

    public static Object construct(Class<?> type, Object... args) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == args.length
                    && parametersCompatible(constructor.getParameterTypes(), args)) {
                return constructor.newInstance(args);
            }
        }
        throw new NoSuchMethodException(type.getName() + " constructor");
    }

    private static boolean parametersCompatible(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            Class<?> parameterType = parameterTypes[i];
            if (arg == null) {
                if (parameterType.isPrimitive()) return false;
                continue;
            }
            if (parameterType.isPrimitive()) parameterType = primitiveWrapper(parameterType);
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

    public static Object[] arr(Object... values) { return values; }

    public static Object createMonitorProxy(Object container) {
        return createMonitorProxy(container, Long.MAX_VALUE);
    }

    public static Object createMonitorProxy(Object container, long deadlineNanos) {
        try {
            for (Method method : container.getClass().getMethods()) {
                if (method.getName().equals("connect") && method.getParameterCount() == 3) {
                    return Proxy.newProxyInstance(method.getParameterTypes()[0].getClassLoader(),
                            new Class[]{method.getParameterTypes()[0]}, (proxy, invoked, args) -> {
                                if (invoked.getDeclaringClass() == Object.class) {
                                    switch (invoked.getName()) {
                                        case "toString": return "DataExportProgressMonitor";
                                        case "hashCode": return System.identityHashCode(proxy);
                                        case "equals": return proxy == (args == null ? null : args[0]);
                                        default: return null;
                                    }
                                }
                                if ("isCanceled".equals(invoked.getName())
                                        && invoked.getReturnType() == boolean.class) {
                                    return System.nanoTime() >= deadlineNanos;
                                }
                                return primitiveDefault(invoked.getReturnType());
                            });
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        return null;
    }

    public static String stringMethod(Object target, String method, String fallback) {
        try {
            Object value = call(target, method);
            return value == null ? fallback : String.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static boolean isMongoDataSource(Object dataSource) {
        for (Class<?> type = dataSource.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.dbeaver.db.mongodb.model.MGDataSource".equals(type.getName())) return true;
        }
        return false;
    }

    public static Exception closeIteratorAfter(Object iterator, Exception failure) {
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

    public static Exception closeAfter(Object resource, Exception failure) {
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
}
