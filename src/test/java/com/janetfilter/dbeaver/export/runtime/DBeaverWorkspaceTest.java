package com.janetfilter.dbeaver.export.runtime;

import com.janetfilter.dbeaver.export.utils.HttpException;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class DBeaverWorkspaceTest {
    @Test
    public void datasourceLookupIsQualifiedByProjectEvenWhenIdsAndNamesRepeat() throws Exception {
        NativeProject first = new NativeProject("first", "General");
        NativeProject selected = new NativeProject("selected", "work");
        NativeDatasource original = first.add("same-id", "Same name");
        NativeDatasource target = selected.add("same-id", "Same name");
        NativeWorkspace workspace = new NativeWorkspace(first, selected);

        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(workspace, "selected");
        assertSame(target, DBeaverWorkspace.requireDatasource(project, "same-id"));
        assertSame(original, DBeaverWorkspace.requireDatasource(
                DBeaverWorkspace.requireProject(workspace, "first"), "same-id"));
    }

    @Test
    public void missingSelectorsAndUnknownTargetsCannotFallBackToFirstProject() throws Exception {
        NativeProject first = new NativeProject("first", "General");
        first.add("existing-id", "Existing");
        NativeWorkspace workspace = new NativeWorkspace(first);
        assertStatus(400, () -> DBeaverWorkspace.requireProject(workspace, null));
        assertStatus(400, () -> DBeaverWorkspace.requireProject(workspace, " "));
        assertStatus(404, () -> DBeaverWorkspace.requireProject(workspace, "General"));
        assertStatus(404, () -> DBeaverWorkspace.requireProject(workspace, "unknown"));
        DBeaverWorkspace.ProjectContext project =
                DBeaverWorkspace.requireProject(workspace, "first");
        assertStatus(400, () -> DBeaverWorkspace.requireDatasource(project, null));
        assertStatus(404, () -> DBeaverWorkspace.requireDatasource(project, "Existing"));
        assertStatus(404, () -> DBeaverWorkspace.requireDatasource(project, "unknown"));
    }

    @Test
    public void duplicatedNativeProjectIdentityIsAnExplicitConflict() {
        NativeWorkspace workspace = new NativeWorkspace(
                new NativeProject("duplicate", "A"), new NativeProject("duplicate", "B"));
        assertStatus(409, () -> DBeaverWorkspace.requireProject(workspace, "duplicate"));
    }

    @Test
    public void registryCannotReturnAProfileOwnedByAnotherProject() throws Exception {
        NativeProject requested = new NativeProject("requested", "work");
        NativeProject other = new NativeProject("other", "General");
        requested.registry.profiles.put("foreign", other.add("foreign", "Foreign"));
        DBeaverWorkspace.ProjectContext project = DBeaverWorkspace.requireProject(
                new NativeWorkspace(requested, other), "requested");
        assertStatus(409, () -> DBeaverWorkspace.requireDatasource(project, "foreign"));
    }

    private static void assertStatus(int status, ThrowingRunnable action) {
        assertEquals(status, assertThrows(HttpException.class, action).status);
    }

    public static final class NativeWorkspace {
        private final List<NativeProject> projects;
        NativeWorkspace(NativeProject... projects) { this.projects = Arrays.asList(projects); }
        public List<NativeProject> getProjects() { return projects; }
    }

    public static final class NativeProject {
        private final String id, name;
        final NativeRegistry registry = new NativeRegistry();
        NativeProject(String id, String name) { this.id = id; this.name = name; }
        NativeDatasource add(String id, String name) {
            NativeDatasource value = new NativeDatasource(this, id, name);
            registry.profiles.put(id, value);
            return value;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public NativeRegistry getDataSourceRegistry() { return registry; }
    }

    public static final class NativeRegistry {
        final Map<String, NativeDatasource> profiles = new LinkedHashMap<String, NativeDatasource>();
        public NativeDatasource getDataSource(String id) { return profiles.get(id); }
    }

    public static final class NativeDatasource {
        private final NativeProject project;
        private final String id, name;
        NativeDatasource(NativeProject project, String id, String name) {
            this.project = project; this.id = id; this.name = name;
        }
        public NativeProject getProject() { return project; }
        public String getId() { return id; }
        public String getName() { return name; }
    }
}
