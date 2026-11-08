package com.janetfilter.dbeaver.export.runtime;

import com.janetfilter.dbeaver.export.utils.HttpException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native workspace identities shared by administration and data operations. */
public final class DBeaverWorkspace {
    private DBeaverWorkspace() { }

    static Object workspace() throws Exception {
        final Object platform;
        try {
            platform = DBeaverRuntime.call(
                    DBeaverRuntime.c("org.jkiss.dbeaver.runtime.DBWorkbench"),
                    "getPlatform");
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
            throw new HttpException(503, "DBeaver runtime is unavailable");
        }
        if (platform == null) {
            throw new HttpException(503, "DBeaver platform is unavailable");
        }
        Object workspace = DBeaverRuntime.call(platform, "getWorkspace");
        if (workspace == null) {
            throw new HttpException(503, "DBeaver workspace is unavailable");
        }
        return workspace;
    }

    public static List<ProjectContext> projects() throws Exception {
        Object workspace = workspace();
        List<?> nativeProjects = projectObjects(workspace);
        List<ProjectContext> result = new ArrayList<ProjectContext>(nativeProjects.size());
        for (Object project : nativeProjects) {
            result.add(context(workspace, project));
        }
        return result;
    }

    public static Map<String, Object> projectListing() throws Exception {
        Object workspace = workspace();
        String activeId = activeProjectId(workspace);
        List<?> nativeProjects = projectObjects(workspace);
        List<Object> items = new ArrayList<Object>(nativeProjects.size());
        for (Object project : nativeProjects) {
            items.add(projectMetadata(context(workspace, project), activeId));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("activeProjectId", activeId);
        return result;
    }

    public static List<Object> datasourceListing(String projectId) throws Exception {
        ProjectContext project = requireProject(projectId);
        List<Object> result = new ArrayList<Object>();
        for (Object descriptor : datasources(project)) {
            Identity identity = identity(descriptor);
            Map<String, Object> item = identity.asMap();
            item.put("projectName", project.name);
            item.put("connected", identity.connected);
            result.add(item);
        }
        return result;
    }

    public static ProjectContext requireProject(String projectId) throws Exception {
        requireId(projectId, "projectId");
        return requireProject(workspace(), projectId);
    }

    public static ProjectContext requireProject(Object workspace, String projectId)
            throws Exception {
        requireId(projectId, "projectId");
        Object match = null;
        for (Object project : projectObjects(workspace)) {
            if (projectId.equals(DBeaverRuntime.call(project, "getId"))) {
                if (match != null) {
                    throw new HttpException(409, "project identity is ambiguous");
                }
                match = project;
            }
        }
        if (match == null) {
            throw new HttpException(404, "project not found");
        }
        ProjectContext result = context(workspace, match);
        if (result.registry == null) {
            throw new HttpException(503, "data source registry is unavailable");
        }
        return result;
    }

    public static Object requireDatasource(ProjectContext project, String datasourceId)
            throws Exception {
        requireId(datasourceId, "dsId");
        Object descriptor = DBeaverRuntime.call(
                project.registry, "getDataSource", DBeaverRuntime.arr(datasourceId));
        if (descriptor == null) {
            throw new HttpException(404, "data source not found");
        }
        Object actualProject = DBeaverRuntime.call(descriptor, "getProject");
        if (actualProject == null || !project.id.equals(
                DBeaverRuntime.call(actualProject, "getId"))) {
            throw new HttpException(409, "data source belongs to another project");
        }
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> datasources(ProjectContext project) throws Exception {
        Object values = DBeaverRuntime.call(project.registry, "getDataSources");
        if (!(values instanceof List)) {
            throw new HttpException(503, "data source registry is unavailable");
        }
        return (List<Object>) values;
    }

    public static boolean writable(ProjectContext project) throws Exception {
        if (project.registry == null) return false;
        Object manager = DBeaverRuntime.call(project.registry, "getConfigurationManager");
        return manager != null && !Boolean.TRUE.equals(
                DBeaverRuntime.call(manager, "isReadOnly"));
    }

    public static Identity identity(Object descriptor) throws Exception {
        Object driver = DBeaverRuntime.call(descriptor, "getDriver");
        Object project = DBeaverRuntime.call(descriptor, "getProject");
        if (driver == null || project == null) {
            throw new HttpException(503, "data source identity is unavailable");
        }
        return new Identity(
                text(DBeaverRuntime.call(descriptor, "getId")),
                text(DBeaverRuntime.call(descriptor, "getName")),
                text(DBeaverRuntime.call(driver, "getProviderId")),
                text(DBeaverRuntime.call(driver, "getId")),
                text(DBeaverRuntime.call(project, "getId")),
                Boolean.TRUE.equals(DBeaverRuntime.call(descriptor, "isConnected")));
    }

    public static Map<String, Object> projectMetadata(ProjectContext project) throws Exception {
        return projectMetadata(project, activeProjectId(project.workspace));
    }

    private static Map<String, Object> projectMetadata(
            ProjectContext project, String activeId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", project.id);
        result.put("name", project.name);
        result.put("active", project.id.equals(activeId));
        result.put("writable", writable(project));
        return result;
    }

    private static ProjectContext context(Object workspace, Object project) throws Exception {
        return new ProjectContext(workspace, project,
                DBeaverRuntime.call(project, "getDataSourceRegistry"));
    }

    private static List<?> projectObjects(Object workspace) throws Exception {
        Object values = DBeaverRuntime.call(workspace, "getProjects");
        if (!(values instanceof List)) {
            throw new HttpException(503, "workspace project list is unavailable");
        }
        return (List<?>) values;
    }

    private static String activeProjectId(Object workspace) throws Exception {
        Object project = DBeaverRuntime.call(workspace, "getActiveProject");
        return project == null ? null : text(DBeaverRuntime.call(project, "getId"));
    }

    public static void requireId(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new HttpException(400, "missing " + key);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    public static final class ProjectContext {
        public final Object workspace, project, registry;
        public final String id, name;

        ProjectContext(Object workspace, Object project, Object registry) throws Exception {
            this.workspace = workspace;
            this.project = project;
            this.registry = registry;
            this.id = text(DBeaverRuntime.call(project, "getId"));
            this.name = text(DBeaverRuntime.call(project, "getName"));
            if (id == null || id.isEmpty()) {
                throw new HttpException(503, "project identity is unavailable");
            }
        }
    }

    public static final class Identity {
        public final String id, name, providerId, driverId, projectId;
        public final boolean connected;

        public Identity(String id, String name, String providerId, String driverId,
                String projectId, boolean connected) {
            this.id = id;
            this.name = name;
            this.providerId = providerId;
            this.driverId = driverId;
            this.projectId = projectId;
            this.connected = connected;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("name", name);
            result.put("providerId", providerId);
            result.put("driverId", driverId);
            result.put("projectId", projectId);
            return result;
        }
    }
}
