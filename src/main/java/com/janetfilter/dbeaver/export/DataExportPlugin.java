package com.janetfilter.dbeaver.export;

import com.janetfilter.core.Environment;
import com.janetfilter.core.plugin.MyTransformer;
import com.janetfilter.core.plugin.PluginConfig;
import com.janetfilter.core.plugin.PluginEntry;
import com.janetfilter.dbeaver.export.http.DataExportServer;
import com.janetfilter.dbeaver.export.instrumentation.ClassLoaderBridgeTransformer;
import com.janetfilter.dbeaver.export.runtime.DBeaverRuntime;
import com.janetfilter.dbeaver.export.runtime.evaluation.DBeaverRuntimeEvaluator;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** ja-netfilter entry point and composition root for the data API. */
public final class DataExportPlugin implements PluginEntry {
    private static final String PORT_PROPERTY = "janf.dataExport.port";
    private static final int DEFAULT_PORT = 8765;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static int port;
    private static DataExportServer server;

    @Override public String getName() { return "dbeaver-data-export"; }
    @Override public String getAuthor() { return "BrainStorm"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public String getDescription() { return "HTTP API for DBeaver data export."; }

    @Override public void init(Environment env, PluginConfig config) {
        port = Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT);
        if (env != null) {
            DBeaverRuntimeEvaluator.setPluginDirectory(
                    env.getPluginsDir(), env.getDisabledPluginSuffix());
        }
    }

    @Override public List<MyTransformer> getTransformers() {
        return Arrays.asList(new ClassLoaderBridgeTransformer());
    }

    @SuppressWarnings("unused")
    public static void setDBeaverClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return;
        DBeaverRuntime.setClassLoader(classLoader);
        if (started.compareAndSet(false, true)) {
            server = new DataExportServer(port);
            server.startAsync(classLoader);
        }
    }
}
