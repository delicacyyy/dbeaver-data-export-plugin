package com.janetfilter.dbeaver.export.utils;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class JsonRequestsTest {
    @Test
    public void parsesNestedValuesAndPreservesInputStrings() {
        Map<String, Object> request = JsonRequests.parseObject(
                "{\"mode\":\" guided \",\"connection\":{\"port\":5432,"
                        + "\"enabled\":true,\"optional\":null,\"labels\":[\"a\",\"b\"]}}\n");

        assertEquals(" guided ", JsonRequests.string(request, "mode", true));
        Map<String, Object> connection = JsonRequests.object(request, "connection", true);
        assertEquals(new BigDecimal("5432"), connection.get("port"));
        assertEquals(Boolean.TRUE, connection.get("enabled"));
        assertNull(connection.get("optional"));
        assertEquals(Arrays.asList("a", "b"), connection.get("labels"));
    }

    @Test
    public void rejectsDuplicateKeysAtEveryObjectDepth() {
        rejects("{\"mode\":\"guided\",\"mode\":\"direct\"}", "guided");
        rejects("{\"connection\":{\"host\":\"first\",\"host\":\"second\"}}", "first");
        rejects("{\"ignored\":[{\"key\":1,\"key\":2}]}", "key");
        rejects("{\"a\":1,\"\\u0061\":2}", "\\u0061");
    }

    @Test
    public void rejectsMalformedIgnoredValuesAndTrailingContent() {
        rejects("{\"ignored\":[1,]}", "ignored");
        rejects("{\"ignored\":tru}", "ignored");
        rejects("{} false", "false");
        rejects("{} {}", "{}");
        rejects("{\"number\":01}", "01");
        rejects("{\"number\":NaN}", "NaN");
        rejects("{\"number\":+1}", "+1");
        rejects("{\"number\":1.}", "1.");
        rejects("{\"number\":1e9999999999}", "9999999999");
    }

    @Test
    public void rejectsNonObjectRootsAndExcessiveNesting() {
        rejects("[]", "[]");
        rejects("\"request\"", "request");
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            nested.append("{\"x\":");
        }
        nested.append("null");
        for (int i = 0; i < 101; i++) {
            nested.append('}');
        }
        rejects(nested.toString(), "nested");
    }

    @Test
    public void typedAccessorsAndUnknownKeysRejectInvalidFields() {
        Map<String, Object> request = JsonRequests.parseObject(
                "{\"mode\":\"guided\",\"enabled\":false,\"nested\":{}}");

        invalidAccessor(() -> JsonRequests.string(request, "absent", true));
        invalidAccessor(() -> JsonRequests.string(request, "enabled", false));
        invalidAccessor(() -> JsonRequests.string(
                JsonRequests.parseObject("{\"mode\":\" \"}"), "mode", true));
        invalidAccessor(() -> JsonRequests.bool(request, "mode", false));
        invalidAccessor(() -> JsonRequests.object(request, "enabled", false));
        invalidAccessor(() -> JsonRequests.requireKeys(request, "mode"));
    }


    private static void rejects(String input, String payloadMarker) {
        try {
            JsonRequests.parseObject(input);
            fail("invalid request JSON was accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains(payloadMarker));
        }
    }

    private static void invalidAccessor(Runnable operation) {
        try {
            operation.run();
            fail("invalid request field was accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("guided"));
        }
    }
}
