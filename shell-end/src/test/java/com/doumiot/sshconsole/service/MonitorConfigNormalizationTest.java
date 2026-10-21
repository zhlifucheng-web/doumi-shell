package com.doumiot.sshconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitorConfigNormalizationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void preservesSelectedDashboardAndFallsBackWhenItDoesNotExist() throws Exception {
        JsonNode config = mapper.readTree("{\"activeDashboardId\":\"panel-2\",\"customScripts\":[],\"dashboards\":["
                + "{\"id\":\"default\",\"name\":\"Default\",\"selectedCards\":[],\"customCardIds\":[],\"cardOrder\":[]},"
                + "{\"id\":\"panel-2\",\"name\":\"Panel 2\",\"selectedCards\":[],\"customCardIds\":[],\"cardOrder\":[]}]} ");
        SshMonitorConfigService service = new SshMonitorConfigService();

        JsonNode normalized = (JsonNode) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "normalize", config);
        assertEquals("panel-2", normalized.path("activeDashboardId").asText());

        com.fasterxml.jackson.databind.node.ObjectNode invalidSelection = (com.fasterxml.jackson.databind.node.ObjectNode) config.deepCopy();
        invalidSelection.put("activeDashboardId", "missing");
        JsonNode fallback = (JsonNode) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "normalize", invalidSelection);
        assertEquals("default", fallback.path("activeDashboardId").asText());
    }

    @Test void normalizesMissingDashboardsToDefaultPanel() throws Exception {
        JsonNode config = mapper.readTree("{\"customScripts\":[],\"dashboards\":[]}");
        SshMonitorConfigService service = new SshMonitorConfigService();

        JsonNode normalized = (JsonNode) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "normalize", config);
        assertEquals("default", normalized.path("activeDashboardId").asText());
        assertEquals(1, normalized.path("dashboards").size());
        assertEquals("默认面板", normalized.path("dashboards").get(0).path("name").asText());
    }
}
