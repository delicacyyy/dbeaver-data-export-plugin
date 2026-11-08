package com.janetfilter.dbeaver.export.data;

import com.janetfilter.dbeaver.export.runtime.DBeaverWorkspace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Stateless structural identity qualified by native project and datasource IDs. */
final class SchemaPath {
    private static final String VERSION = "schema-path-v2";
    private static final int MAX_SEGMENTS = 16;
    final String projectId, datasourceId;
    final List<Segment> segments;

    private SchemaPath(String projectId, String datasourceId, List<Segment> segments) {
        this.projectId = projectId;
        this.datasourceId = datasourceId;
        this.segments = segments;
    }

    static SchemaPath root(String projectId, String datasourceId) {
        DBeaverWorkspace.requireId(projectId, "projectId");
        DBeaverWorkspace.requireId(datasourceId, "dsId");
        return new SchemaPath(projectId, datasourceId, Collections.<Segment>emptyList());
    }

    SchemaPath append(String kind, String name) {
        if (!isKind(kind) || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("invalid node segment");
        }
        if (segments.size() >= MAX_SEGMENTS) {
            throw new IllegalArgumentException("invalid node depth");
        }
        ArrayList<Segment> next = new ArrayList<Segment>(segments.size() + 1);
        next.addAll(segments);
        next.add(new Segment(kind, name));
        return new SchemaPath(projectId, datasourceId, next);
    }

    String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeUTF(VERSION);
                data.writeUTF(projectId);
                data.writeUTF(datasourceId);
                data.writeInt(segments.size());
                for (Segment segment : segments) {
                    data.writeUTF(segment.kind);
                    data.writeUTF(segment.name);
                }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalArgumentException("node identity is too long", failure);
        }
    }

    static SchemaPath decode(String encoded, String expectedProjectId,
            String expectedDatasourceId) {
        SchemaPath path = decode(encoded);
        if (!path.projectId.equals(expectedProjectId)
                || !path.datasourceId.equals(expectedDatasourceId)) {
            throw new IllegalArgumentException("node id belongs to another project or data source");
        }
        return path;
    }

    static SchemaPath decode(String encoded) {
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("invalid node id");
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!VERSION.equals(data.readUTF())) {
                throw new IllegalArgumentException("unsupported node id; rediscover schema");
            }
            String projectId = data.readUTF();
            String datasourceId = data.readUTF();
            if (projectId.isEmpty() || datasourceId.isEmpty()) {
                throw new IllegalArgumentException("invalid node identity");
            }
            int count = data.readInt();
            if (count < 1 || count > MAX_SEGMENTS) {
                throw new IllegalArgumentException("invalid node depth");
            }
            List<Segment> segments = new ArrayList<Segment>(count);
            for (int i = 0; i < count; i++) {
                String kind = data.readUTF();
                String name = data.readUTF();
                if (!isKind(kind) || name.isEmpty()) {
                    throw new IllegalArgumentException("invalid node segment");
                }
                segments.add(new Segment(kind, name));
            }
            if (data.available() != 0) {
                throw new IllegalArgumentException("invalid node id");
            }
            return new SchemaPath(projectId, datasourceId, segments);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("invalid node id", invalid);
        }
    }

    private static boolean isKind(String kind) {
        return "catalog".equals(kind) || "schema".equals(kind)
                || "table".equals(kind) || "view".equals(kind) || "column".equals(kind);
    }

    static final class Segment {
        final String kind, name;
        Segment(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }
}
