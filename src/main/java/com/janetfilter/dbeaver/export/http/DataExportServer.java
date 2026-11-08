package com.janetfilter.dbeaver.export.http;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the loopback server and its execution resources. */
public final class DataExportServer {
    private final int port;
    private Object httpServer;
    private ExecutorService executor;

    public DataExportServer(int port) {
        this.port = port;
    }

    public void startAsync(ClassLoader classLoader) {
        executor = Executors.newFixedThreadPool(4);
        new Thread(() -> {
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                start();
            } catch (Exception error) {
                System.err.println("[dbeaver-data-export] SERVER FAILED: " + error);
                error.printStackTrace();
            }
        }, "dbeaver-data-export-server").start();
    }

    private void start() throws Exception {
        ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();
        Class<?> serverClass = Class.forName(
                "com.sun.net.httpserver.HttpServer", true, platformLoader);
        httpServer = serverClass.getMethod("create", InetSocketAddress.class, int.class)
                .invoke(null, new InetSocketAddress("127.0.0.1", port), 0);
        new DataExportController().registerWith(httpServer);
        serverClass.getMethod("setExecutor", Executor.class).invoke(httpServer, executor);
        serverClass.getMethod("start").invoke(httpServer);
        System.out.println("[dbeaver-data-export] HTTP on http://127.0.0.1:" + port);
    }
}
