package com.doumiot.sshconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class MonitorConfigPersistenceTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private SshMonitorConfigService service;

    @BeforeEach void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ssh_account (id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO ssh_account(id) VALUES (?)", 97L);
        jdbc.update("INSERT INTO ssh_account(id) VALUES (?)", 98L);
        service = new SshMonitorConfigService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        service.initialize();
    }

    @AfterEach void tearDown() { dataSource.destroy(); }

    @Test void firstSaveAndUpdateSurviveReloadAndRejectStaleWrites() {
        ObjectNode config = (ObjectNode) service.get(97L);
        assertEquals(0L, config.path("revision").asLong());
        ((ObjectNode) config.path("dashboards").get(0)).put("name", "Production");

        JsonNode saved = service.save(97L, config);
        assertEquals(1L, saved.path("revision").asLong());
        assertEquals(saved, service.get(97L));
        assertEquals(97L, jdbc.queryForObject("SELECT account_id FROM ssh_monitor_config", Long.class));
        assertTrue(jdbc.queryForObject("SELECT updated_at FROM ssh_monitor_config", Long.class) > 0);
        assertEquals(0L, service.get(98L).path("revision").asLong());

        ObjectNode update = saved.deepCopy();
        ((ObjectNode) update.path("dashboards").get(0)).put("name", "Updated");
        JsonNode updated = service.save(97L, update);
        assertEquals(2L, updated.path("revision").asLong());
        assertEquals(updated, service.get(97L));
        assertThrows(IllegalArgumentException.class, () -> service.save(97L, saved));
        assertEquals(updated, service.get(97L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_monitor_config", Integer.class));
    }

    @Test void importAndAiCardCreationCanInitializeAnAccountsConfiguration() {
        JsonNode imported = service.saveImported(97L, service.get(97L));
        assertEquals(1L, imported.path("revision").asLong());
        assertEquals(imported, service.get(97L));

        JsonNode card = service.createCard(98L, "Status", "printf '{\"value\":1}'", "value", null, "default");
        JsonNode reloaded = service.get(98L);
        assertEquals(1L, reloaded.path("revision").asLong());
        assertEquals(card.path("id"), reloaded.path("customScripts").get(0).path("id"));
        assertEquals(card.path("id"), reloaded.path("dashboards").get(0).path("customCardIds").get(0));
    }
}
