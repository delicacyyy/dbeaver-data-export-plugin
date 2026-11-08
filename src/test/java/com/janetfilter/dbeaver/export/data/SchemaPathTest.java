package com.janetfilter.dbeaver.export.data;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class SchemaPathTest {
    @Test
    public void sameStructuralObjectInDifferentProjectsHasDifferentIdentity() {
        SchemaPath first = SchemaPath.root("General", "same-datasource")
                .append("schema", "R&D+100%✓").append("table", "Events");
        SchemaPath second = SchemaPath.root("work", "same-datasource")
                .append("schema", "R&D+100%✓").append("table", "Events");
        assertNotEquals(first.encode(), second.encode());
        SchemaPath restored = SchemaPath.decode(second.encode(), "work", "same-datasource");
        assertEquals("R&D+100%✓", restored.segments.get(0).name);
        assertEquals("Events", restored.segments.get(1).name);
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.decode(second.encode(), "General", "same-datasource"));
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.decode(second.encode(), "work", "another-datasource"));
    }

    @Test
    public void legacyNameOnlyIdentityMustBeRediscovered() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF("schema-path-v1");
            data.writeUTF("Connection name");
            data.writeInt(1);
            data.writeUTF("table");
            data.writeUTF("Events");
        }
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.decode(Base64.getUrlEncoder().encodeToString(bytes.toByteArray())));
    }

    @Test
    public void trailingOrTruncatedBytesCannotRetargetAnOtherwiseValidNode() {
        String node = SchemaPath.root("work", "connection").append("table", "Events").encode();
        byte[] original = Base64.getUrlDecoder().decode(node);
        byte[] trailing = Arrays.copyOf(original, original.length + 1);
        trailing[trailing.length - 1] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.decode(Base64.getUrlEncoder().encodeToString(trailing)));
        byte[] truncated = Arrays.copyOf(original, original.length - 1);
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.decode(Base64.getUrlEncoder().encodeToString(truncated)));
    }

    @Test
    public void structuralDepthBoundaryRejectsUnresolvableNodes() {
        SchemaPath path = SchemaPath.root("work", "connection");
        for (int i = 0; i < 16; i++) path = path.append("schema", "Scope" + i);
        SchemaPath deepest = path;
        assertEquals(16, SchemaPath.decode(deepest.encode()).segments.size());
        assertThrows(IllegalArgumentException.class, () -> deepest.append("table", "Too deep"));
        assertThrows(IllegalArgumentException.class,
                () -> SchemaPath.root("work", "connection").append("unsupported", "Events"));
    }
}
