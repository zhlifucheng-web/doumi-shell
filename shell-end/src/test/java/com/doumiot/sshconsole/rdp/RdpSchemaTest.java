package com.doumiot.sshconsole.rdp;

import com.doumiot.config.DoumiShellSqliteDataSourceConfig;
import com.doumiot.config.DoumiShellStorageProperties;
import com.doumiot.init.DatabaseInitProperties;
import com.doumiot.init.DatabaseInitService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RdpSchemaTest {
    @TempDir Path directory;

    @Test void freshSchemaCreatesRdpProfilesWithForeignKeysEnabled() throws Exception {
        DoumiShellStorageProperties storage = new DoumiShellStorageProperties();
        storage.setDataDir(directory.toString());
        try (HikariDataSource ds = (HikariDataSource) new DoumiShellSqliteDataSourceConfig().doumiShellDataSource(storage)) {
            new DatabaseInitService(ds, new DatabaseInitProperties()).manualInitialize();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
            jdbc.update("INSERT INTO ssh_group(id,name) VALUES (1,'Windows')");
            jdbc.update("INSERT INTO ssh_host(id,group_id,name,host) VALUES (1,1,'Desktop','192.0.2.10')");
            jdbc.update("INSERT INTO rdp_connection(host_id,name,username,secret_enc) VALUES (1,'Admin','Administrator',?)", new byte[]{1,2,3});
            assertEquals(3389, jdbc.queryForObject("SELECT port FROM rdp_connection", Integer.class));
            jdbc.update("DELETE FROM ssh_host WHERE id=1");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection", Integer.class));
        }
    }
}
