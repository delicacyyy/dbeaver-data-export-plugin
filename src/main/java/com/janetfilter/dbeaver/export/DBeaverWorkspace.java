package com.janetfilter.dbeaver.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native workspace identities shared by administration and data operations. */
final class DBeaverWorkspace {
    private DBeaverWorkspace() { }

    static Object workspace() throws Exception {
        final Object platform;
        try {
            platform = DataExportPlugin.call(
                    DataExportPlugin.c("org.jkiss.dbeaver.runtime.DBWorkbench"),
                    "getPlatform");
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
            throw new DataExportPlugin.HttpException(503, "DBeaver runtime is unavailable");
        }
        if (platform == null) {
            throw new DataExportPlugin.HttpException(503, "DBeaver platform is unavailable");
        }
        Object workspace = DataExportPlugin.call(platform, "getWorkspace");
        if (workspace == null) {
            throw new DataExportPlugin.HttpException(503, "DBeaver workspace is unavailable");
        }
        return workspace;
    }

    static List<ProjectContext> projects() throws Exception {
        Object workspace = workspace();
        List<?> nativeProjects = projectObjects(workspace);
        List<ProjectContext> result = new ArrayList<ProjectContext>(nativeProjects.size());
        for (Object project : nativeProjects) {
            result.add(context(workspace, project));
        }
        return result;
    }

    static Map<String, Object> projectListing() throws Exception {
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

    static ProjectContext requireProject(String projectId) throws Exception {
        requireId(projectId, "projectId");
        return requireProject(workspace(), projectId);
    }

    static ProjectContext requireProject(Object workspace, String projectId)
            throws Exception {
        requireId(projectId, "projectId");
        Object match = null;
        for (Object project : projectObjects(workspace)) {
            if (projectId.equals(DataExportPlugin.call(project, "getId"))) {
                if (match != null) {
                    throw new DataExportPlugin.HttpException(409, "project identity is ambiguous");
                }
                match = project;
            }
        }
        if (match == null) {
            throw new DataExportPlugin.HttpException(404, "project not found");
        }
        ProjectContext result = context(workspace, match);
        if (result.registry == null) {
            throw new DataExportPlugin.HttpException(503, "data source registry is unavailable");
        }
        return result;
    }

    static Object requireDatasource(ProjectContext project, String datasourceId)
            throws Exception {
        requireId(datasourceId, "dsId");
        Object descriptor = DataExportPlugin.call(
                project.registry, "getDataSource", DataExportPlugin.arr(datasourceId));
        if (descriptor == null) {
            throw new DataExportPlugin.HttpException(404, "data source not found");
        }
        Object actualProject = DataExportPlugin.call(descriptor, "getProject");
        if (actualProject == null || !project.id.equals(
                DataExportPlugin.call(actualProject, "getId"))) {
            throw new DataExportPlugin.HttpException(409, "data source belongs to another project");
        }
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    static List<Object> datasources(ProjectContext project) throws Exception {
        Object values = DataExportPlugin.call(project.registry, "getDataSources");
        if (!(values instanceof List)) {
            throw new DataExportPlugin.HttpException(503, "data source registry is unavailable");
        }
        return (List<Object>) values;
    }

    static boolean writable(ProjectContext project) throws Exception {
        if (project.registry == null) return false;
        Object manager = DataExportPlugin.call(project.registry, "getConfigurationManager");
        return manager != null && !Boolean.TRUE.equals(
                DataExportPlugin.call(manager, "isReadOnly"));
    }

    static Identity identity(Object descriptor) throws Exception {
        Object driver = DataExportPlugin.call(descriptor, "getDriver");
        Object project = DataExportPlugin.call(descriptor, "getProject");
        if (driver == null || project == null) {
            throw new DataExportPlugin.HttpException(503, "data source identity is unavailable");
        }
        return new Identity(
                text(DataExportPlugin.call(descriptor, "getId")),
                text(DataExportPlugin.call(descriptor, "getName")),
                text(DataExportPlugin.call(driver, "getProviderId")),
                text(DataExportPlugin.call(driver, "getId")),
                text(DataExportPlugin.call(project, "getId")),
                Boolean.TRUE.equals(DataExportPlugin.call(descriptor, "isConnected")));
    }

    static Map<String, Object> projectMetadata(ProjectContext project) throws Exception {
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
                DataExportPlugin.call(project, "getDataSourceRegistry"));
    }

    private static List<?> projectObjects(Object workspace) throws Exception {
        Object values = DataExportPlugin.call(workspace, "getProjects");
        if (!(values instanceof List)) {
            throw new DataExportPlugin.HttpException(503, "workspace project list is unavailable");
        }
        return (List<?>) values;
    }

    private static String activeProjectId(Object workspace) throws Exception {
        Object project = DataExportPlugin.call(workspace, "getActiveProject");
        return project == null ? null : text(DataExportPlugin.call(project, "getId"));
    }

    static void requireId(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new DataExportPlugin.HttpException(400, "missing " + key);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    static final class ProjectContext {
        final Object workspace, project, registry;
        final String id, name;

        ProjectContext(Object workspace, Object project, Object registry) throws Exception {
            this.workspace = workspace;
            this.project = project;
            this.registry = registry;
            this.id = text(DataExportPlugin.call(project, "getId"));
            this.name = text(DataExportPlugin.call(project, "getName"));
            if (id == null || id.isEmpty()) {
                throw new DataExportPlugin.HttpException(503, "project identity is unavailable");
            }
        }
    }

    static final class Identity {
        final String id, name, providerId, driverId, projectId;
        final boolean connected;

        Identity(String id, String name, String providerId, String driverId,
                String projectId, boolean connected) {
            this.id = id;
            this.name = name;
            this.providerId = providerId;
            this.driverId = driverId;
            this.projectId = projectId;
            this.connected = connected;
        }

        Map<String, Object> asMap() {
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
