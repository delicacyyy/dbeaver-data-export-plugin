package com.janetfilter.dbeaver.export.runtime.evaluation;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loaded in an isolated bridge loader so javax.tools is never linked by the bootstrap plugin. */
public final class JavaSnippetCompiler {
    private static final int MAX_DIAGNOSTICS = 20;
    private static final int MAX_DIAGNOSTIC_CHARS = 240;
    private static final String CLASS_NAME =
            "com.janetfilter.dbeaver.export.runtime.evaluation.generated.RuntimeSnippet";
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    private JavaSnippetCompiler() { }

    /** Returns {success, diagnostics, class bytes}, or null when no system compiler exists. */
    public static Object[] compile(String source, String pluginSourceRoot) throws IOException {
        if (COMPILER == null) {
            return null;
        }

        List<String[]> errors = new ArrayList<>();
        DiagnosticListener<JavaFileObject> listener = diagnostic -> {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR || errors.size() >= MAX_DIAGNOSTICS) {
                return;
            }
            String message;
            try {
                message = diagnostic.getMessage(Locale.ROOT);
            } catch (RuntimeException ignored) {
                message = "compiler diagnostic unavailable";
            }
            errors.add(new String[] {
                    Long.toString(diagnostic.getLineNumber()),
                    Long.toString(diagnostic.getColumnNumber()),
                    diagnostic.getCode() == null ? "compiler" : truncate(diagnostic.getCode(), 120),
                    truncate(message == null ? "compiler diagnostic unavailable" : message,
                            MAX_DIAGNOSTIC_CHARS)
            });
        };

        try (StandardJavaFileManager standard = COMPILER.getStandardFileManager(
                listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            MemoryFileManager manager = new MemoryFileManager(standard);
            MemorySource input = new MemorySource(CLASS_NAME, source);
            List<String> options = List.of(
                    "--release", "17",
                    "-proc:none",
                    "-classpath", pluginSourceRoot);
            Boolean success = COMPILER.getTask(
                    null, manager, listener, options, null, List.of(input)).call();
            if (!Boolean.TRUE.equals(success)) {
                return new Object[] {
                        Boolean.FALSE,
                        errors.toArray(new String[0][]),
                        Map.of()
                };
            }

            Map<String, MemoryOutput> outputs = manager.outputs();
            Map<String, byte[]> classes = new LinkedHashMap<>(outputs.size());
            for (Map.Entry<String, MemoryOutput> entry : outputs.entrySet()) {
                classes.put(entry.getKey(), entry.getValue().bytes());
            }
            return new Object[] {
                    Boolean.TRUE,
                    errors.toArray(new String[0][]),
                    classes
            };
        }
    }

    private static String truncate(String value, int maxChars) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxChars));
        for (int i = 0; i < value.length() && result.length() < maxChars; i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    if (result.length() + 2 > maxChars) {
                        break;
                    }
                    result.append(current).append(value.charAt(++i));
                } else {
                    result.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(current)) {
                result.append('\uFFFD');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static final class MemorySource extends SimpleJavaFileObject {
        private final String source;

        private MemorySource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class MemoryOutput extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private MemoryOutput(String className, Kind kind) {
            super(URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public ByteArrayOutputStream openOutputStream() {
            return bytes;
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    private static final class MemoryFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, MemoryOutput> outputs = new LinkedHashMap<>();

        private MemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                JavaFileManager.Location location,
                String className,
                JavaFileObject.Kind kind,
                FileObject sibling) {
            MemoryOutput output = new MemoryOutput(className, kind);
            outputs.put(className, output);
            return output;
        }

        private Map<String, MemoryOutput> outputs() {
            return outputs;
        }
    }
}
