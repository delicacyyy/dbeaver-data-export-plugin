package com.janetfilter.dbeaver.export;

import org.junit.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueryParamsTest {
    @Test
    public void decodesRawQueryComponentsExactlyOnce() {
        Map<String, String> parameters = DataExportPlugin.queryParams(URI.create(
                "http://localhost/rows?name=R%26D&plus=A%2BB&literal=A+B"
                        + "&percent=100%25&unicode=%E2%9C%93&d%73=profile"));

        assertEquals(6, parameters.size());
        assertEquals("R&D", parameters.get("name"));
        assertEquals("A+B", parameters.get("plus"));
        assertEquals("A B", parameters.get("literal"));
        assertEquals("100%", parameters.get("percent"));
        assertEquals("✓", parameters.get("unicode"));
        assertEquals("profile", parameters.get("ds"));
        assertFalse(parameters.containsKey("d%73"));
        assertTrue(parameters.containsKey("name"));
    }

}
