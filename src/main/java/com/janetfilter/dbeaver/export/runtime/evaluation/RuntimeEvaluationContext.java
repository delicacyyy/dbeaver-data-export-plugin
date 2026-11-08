package com.janetfilter.dbeaver.export.runtime.evaluation;

import com.janetfilter.dbeaver.export.runtime.DBeaverRuntime;

/** Narrow reflective access to DBeaver classes for one runtime evaluation. */
public final class RuntimeEvaluationContext {
    public RuntimeEvaluationContext() { }

    public Class<?> c(String name) throws Exception {
        return DBeaverRuntime.c(name);
    }

    public Class<?> providerClass(Object anchor, String name) throws Exception {
        return DBeaverRuntime.providerClass(anchor, name);
    }

    public Object call(Object target, String method, Object... args) throws Exception {
        return DBeaverRuntime.call(target, method, args);
    }

    public Object construct(Class<?> type, Object... args) throws Exception {
        return DBeaverRuntime.construct(type, args);
    }
}
