package com.janetfilter.dbeaver.export.runtime.evaluation;

import com.janetfilter.dbeaver.export.runtime.DBeaverRuntime;
import com.janetfilter.dbeaver.export.utils.HttpException;
import com.janetfilter.dbeaver.export.utils.JsonRequests;

import static com.janetfilter.dbeaver.export.runtime.evaluation.RuntimeEvaluationResult.boundedTypeName;
import static com.janetfilter.dbeaver.export.runtime.evaluation.RuntimeEvaluationResult.diagnostics;
import static com.janetfilter.dbeaver.export.runtime.evaluation.RuntimeEvaluationResult.error;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiles and executes one in-process Java method body against the captured DBeaver runtime. */
public final class DBeaverRuntimeEvaluator {
    private static final String COMPILER_CLASS =
            "com.janetfilter.dbeaver.export.runtime.evaluation.JavaSnippetCompiler";
    private static final String SNIPPET_CLASS =
            "com.janetfilter.dbeaver.export.runtime.evaluation.generated.RuntimeSnippet";
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    private static CompilerBridge compilerBridge;
    private static final RuntimeEvaluationContext CONTEXT = new RuntimeEvaluationContext();
    private static volatile File pluginDirectory;
    private static String disabledPluginSuffix;

    private DBeaverRuntimeEvaluator() { }

    /** Records the runtime archive location before HTTP workers start. */
    public static void setPluginDirectory(File directory, String disabledSuffix) {
        disabledPluginSuffix = disabledSuffix;
        pluginDirectory = directory;
    }

    /** Evaluates a strict request object and returns its bounded JSON response. */
    public static RuntimeEvaluationResult evaluate(Map<String, Object> request) {
        JsonRequests.requireKeys(request, "code", "args");
        String code = JsonRequests.string(request, "code", true);
        Map<String, Object> args = JsonRequests.object(request, "args", false);
        if (args == null) {
            args = Collections.emptyMap();
        }

        if (!ACTIVE.compareAndSet(false, true)) {
            return error(409, "runtime evaluation is already active", "compile", null, null);
        }

        Thread thread = Thread.currentThread();
        ClassLoader originalContextLoader = null;
        boolean contextLoaderCaptured = false;
        try {
            try {
                originalContextLoader = thread.getContextClassLoader();
                contextLoaderCaptured = true;
            } catch (Throwable failure) {
                rethrowFatal(failure);
                return error(503, "DBeaver runtime is unavailable", "compile", null, null);
            }
            ClassLoader runtimeLoader;
            try {
                runtimeLoader = DBeaverRuntime.requireClassLoader();
            } catch (HttpException unavailable) {
                return error(503, "DBeaver runtime is unavailable", "compile", null, null);
            }

            try {
                thread.setContextClassLoader(runtimeLoader);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                return error(503, "DBeaver runtime is unavailable", "compile", null, null);
            }

            Object[] compilation;
            try {
                compilation = compile(wrapSource(code));
            } catch (Throwable failure) {
                rethrowFatal(failure);
                if (isCompilerUnavailable(failure)) {
                    return error(503, "Java compiler is unavailable", "compile", null, null);
                }
                return error(422, "snippet compilation failed", "compile", null, null);
            }

            if (compilation == null) {
                return error(503, "Java compiler is unavailable", "compile", null, null);
            }
            if (compilation.length != 3 || !(compilation[0] instanceof Boolean)) {
                return error(422, "snippet compilation failed", "compile", null, null);
            }
            String[][] compilerDiagnostics = compilation[1] instanceof String[][]
                    ? (String[][]) compilation[1] : new String[0][];
            if (!Boolean.TRUE.equals(compilation[0])) {
                return error(422, "snippet compilation failed", "compile",
                        diagnostics(compilerDiagnostics), null);
            }

            Method entryPoint;
            try {
                entryPoint = prepareSnippet(snippetParentLoader(), compilation);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                return error(422, "snippet compilation failed", "compile",
                        diagnostics(compilerDiagnostics), null);
            }

            Object value;
            try {
                value = entryPoint.invoke(null, args, CONTEXT);
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                rethrowFatal(cause);
                return error(500, "snippet execution failed", "execute", null,
                        boundedTypeName(cause));
            }

            try {
                return RuntimeEvaluationResult.success(value);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                return error(422, "snippet result violates the JSON result contract", "result",
                        null, null);
            }
        } finally {
            try {
                if (contextLoaderCaptured) {
                    thread.setContextClassLoader(originalContextLoader);
                }
            } finally {
                ACTIVE.set(false);
            }
        }
    }

    private static Object[] compile(String source) throws Exception {
        CompilerBridge bridge = compilerBridge();
        try {
            return (Object[]) bridge.entryPoint.invoke(null, source, bridge.sourceRoot);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw failure;
        }
    }

    private static ClassLoader snippetParentLoader() {
        ClassLoader parent = DBeaverRuntimeEvaluator.class.getClassLoader();
        return parent == null ? ClassLoader.getPlatformClassLoader() : parent;
    }

    @SuppressWarnings("unchecked")
    private static Method prepareSnippet(ClassLoader parent, Object[] compilation) throws Exception {
        Map<String, byte[]> classes = (Map<String, byte[]>) compilation[2];
        ClassLoader snippetLoader = new SnippetClassLoader(parent, classes);
        Class<?> snippet = Class.forName(SNIPPET_CLASS, false, snippetLoader);
        return snippet.getMethod("evaluate", Map.class, RuntimeEvaluationContext.class);
    }

    private static URL compilerResource() throws IOException, ClassNotFoundException {
        String resource = COMPILER_CLASS.replace('.', '/') + ".class";
        File directory = pluginDirectory;
        if (directory == null) {
            return DBeaverRuntimeEvaluator.class.getResource("/" + resource);
        }

        // Instrumentation appends bootstrap classes, but not bootstrap resources.
        File[] candidates = directory.listFiles((dir, name) ->
                name.endsWith(".jar") && !name.endsWith(disabledPluginSuffix));
        if (candidates == null) {
            throw new ClassNotFoundException("plugin directory is unavailable");
        }
        URL found = null;
        for (File candidate : candidates) {
            if (!candidate.isFile()) {
                continue;
            }
            try (JarFile archive = new JarFile(candidate)) {
                Manifest manifest = archive.getManifest();
                if (manifest == null || !"com.janetfilter.dbeaver.export.DataExportPlugin".equals(
                        manifest.getMainAttributes().getValue("JANF-Plugin-Entry"))) {
                    continue;
                }
                if (archive.getJarEntry(resource) == null) {
                    throw new ClassNotFoundException(COMPILER_CLASS);
                }
                if (found != null) {
                    throw new ClassNotFoundException("plugin archive is ambiguous");
                }
                found = new URL("jar:" + candidate.toURI().toURL().toExternalForm() + "!/" + resource);
            }
        }
        return found;
    }

    private static String pluginSourceRoot(URL compilerResource) throws Exception {
        if ("jar".equals(compilerResource.getProtocol())) {
            URLConnection connection = compilerResource.openConnection();
            if (!(connection instanceof JarURLConnection)) {
                throw new IOException("unsupported compiler resource");
            }
            URL jar = ((JarURLConnection) connection).getJarFileURL();
            if (!"file".equals(jar.getProtocol())) {
                throw new IOException("unsupported compiler archive");
            }
            return Paths.get(jar.toURI()).toString();
        }
        if ("file".equals(compilerResource.getProtocol())) {
            Path root = Paths.get(compilerResource.toURI());
            String resourcePath = COMPILER_CLASS.replace('.', '/') + ".class";
            int segments = resourcePath.split("/").length;
            for (int i = 0; i < segments; i++) {
                root = root.getParent();
                if (root == null) {
                    throw new IOException("invalid compiler resource path");
                }
            }
            return root.toString();
        }
        throw new IOException("unsupported compiler resource");
    }

    private static CompilerBridge compilerBridge() throws Exception {
        // The ACTIVE guard serializes initialization and subsequent compiler use.
        CompilerBridge bridge = compilerBridge;
        if (bridge == null) {
            URL resource = compilerResource();
            if (resource == null) {
                throw new ClassNotFoundException(COMPILER_CLASS);
            }
            ClassLoader loader = new CompilerBridgeLoader(snippetParentLoader(), resource);
            Method entryPoint = Class.forName(COMPILER_CLASS, true, loader)
                    .getMethod("compile", String.class, String.class);
            bridge = new CompilerBridge(entryPoint, pluginSourceRoot(resource));
            compilerBridge = bridge;
        }
        return bridge;
    }

    private static final class CompilerBridge {
        private final Method entryPoint;
        private final String sourceRoot;

        private CompilerBridge(Method entryPoint, String sourceRoot) {
            this.entryPoint = entryPoint;
            this.sourceRoot = sourceRoot;
        }
    }

    private static String wrapSource(String code) {
        return "package com.janetfilter.dbeaver.export.runtime.evaluation.generated;\n"
                + "public final class RuntimeSnippet {\n"
                + "    public static Object evaluate(java.util.Map<java.lang.String, java.lang.Object> args,\n"
                + "            com.janetfilter.dbeaver.export.runtime.evaluation.RuntimeEvaluationContext runtime) throws java.lang.Exception {\n"
                + code + "\n    }\n}\n";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof InvocationTargetException) && ((InvocationTargetException) result).getCause() != null) {
            result = ((InvocationTargetException) result).getCause();
        }
        return result;
    }

    private static boolean isCompilerUnavailable(Throwable failure) {
        Throwable current = unwrap(failure);
        return current instanceof ClassNotFoundException
                || current instanceof NoClassDefFoundError
                || current instanceof UnsupportedClassVersionError;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
    }

    private static final class CompilerBridgeLoader extends ClassLoader {
        private final URL compilerResource;
        private final String compilerResourceName = "JavaSnippetCompiler.class";

        private CompilerBridgeLoader(ClassLoader parent, URL compilerResource) {
            super(parent);
            this.compilerResource = compilerResource;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (isCompilerBridgeClass(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!isCompilerBridgeClass(name)) {
                throw new ClassNotFoundException(name);
            }
            String suffix = name.substring(COMPILER_CLASS.length());
            String nestedFile = "JavaSnippetCompiler" + suffix + ".class";
            String resource = compilerResource.toExternalForm();
            if (!resource.endsWith(compilerResourceName)) {
                throw new ClassNotFoundException(name);
            }
            try {
                URL classResource = new URL(resource.substring(
                        0, resource.length() - compilerResourceName.length()) + nestedFile);
                byte[] bytes;
                try (InputStream input = classResource.openStream()) {
                    bytes = input.readAllBytes();
                }
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException | LinkageError failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }

        private static boolean isCompilerBridgeClass(String name) {
            return name.equals(COMPILER_CLASS) || name.startsWith(COMPILER_CLASS + "$" );
        }
    }

    private static final class SnippetClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private SnippetClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

}
