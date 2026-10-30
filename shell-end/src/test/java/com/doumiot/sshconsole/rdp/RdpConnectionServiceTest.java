package com.doumiot.sshconsole.rdp;

import com.doumiot.config.DoumiShellSqliteDataSourceConfig;
import com.doumiot.config.DoumiShellStorageProperties;
import com.doumiot.init.DatabaseInitProperties;
import com.doumiot.init.DatabaseInitService;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import com.doumiot.sshconsole.service.SshHostService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RdpConnectionServiceTest {
    @TempDir Path directory;
    HikariDataSource dataSource;
    JdbcTemplate jdbc;
    RdpConnectionService service;
    CredentialCipher cipher;

    @BeforeEach void setup() throws Exception {
        DoumiShellStorageProperties storage = new DoumiShellStorageProperties();
        storage.setDataDir(directory.toString());
        dataSource = (HikariDataSource) new DoumiShellSqliteDataSourceConfig().doumiShellDataSource(storage);
        jdbc = new JdbcTemplate(dataSource);
        // Existing installation: base tables already exist, so AUTO does not rerun sqlite_schema.sql.
        jdbc.execute("CREATE TABLE ssh_group(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute("CREATE TABLE ssh_host(id INTEGER PRIMARY KEY,group_id INTEGER,name TEXT,host TEXT,port INTEGER,enabled INTEGER)");
        jdbc.update("INSERT INTO ssh_group VALUES (1,'Windows')");
        jdbc.update("INSERT INTO ssh_host VALUES (1,1,'Desktop','192.0.2.10',22,1)");
        jdbc.update("INSERT INTO ssh_host VALUES (2,1,'Other','192.0.2.20',2222,1)");
        new DatabaseInitService(dataSource, new DatabaseInitProperties()).initializeDatabaseOnStartup();
        SshHostMapper mapper = mock(SshHostMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call -> {
            List<SshHost> rows = jdbc.query("SELECT * FROM ssh_host WHERE id=?", (rs, n) -> {
                SshHost host = new SshHost(); host.setId(rs.getLong("id")); host.setHost(rs.getString("host"));
                host.setPort(rs.getInt("port")); host.setEnabled(rs.getInt("enabled")); return host;
            }, (Object) call.getArgument(0));
            return rows.isEmpty() ? null : rows.get(0);
        });
        SshHostService hosts = new SshHostService();
        ReflectionTestUtils.setField(hosts, "sshHostMapper", mapper);
        SshConsoleProperties properties = new SshConsoleProperties();
        properties.setCredentialMasterKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        cipher = new CredentialCipher(properties, storage);
        ReflectionTestUtils.invokeMethod(cipher, "init");
        service = new RdpConnectionService(jdbc, hosts, cipher);
        service.initializeSchema();
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    RdpConnectionService.SaveRequest request() {
        RdpConnectionService.SaveRequest req = new RdpConnectionService.SaveRequest();
        req.setHostId(1L); req.setName(" Windows 桌面 "); req.setUsername(" Administrator ");
        req.setDomain(" CORP "); req.setPassword("秘密-password-123"); return req;
    }

    @Test void encryptedCrudHasNoSecretOrHintAndUsesIndependentRdpPort() throws Exception {
        RdpConnectionService.ConnectionVO created = service.create(request());
        assertEquals("Windows 桌面", created.getName());
        assertEquals("Administrator", created.getUsername());
        assertEquals(3389, created.getPort()); assertEquals(1, created.getEnabled());
        assertFalse(created.isIgnoreCertificate()); assertTrue(created.isHasPassword());
        byte[] stored = jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?", byte[].class, created.getId());
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("password-123"));
        assertTrue(cipher.decryptToJson(stored).contains("秘密-password-123"));
        JsonNode json = new ObjectMapper().valueToTree(service.list(1L));
        Set<String> fields = new HashSet<>(); json.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id","hostId","name","username","domain","port","ignoreCertificate","enabled","hasPassword","remark","sortOrder")), fields);
        assertFalse(json.toString().contains("秘密"));
        RdpTarget target = service.resolve(created.getId());
        assertEquals("192.0.2.10", target.getHost()); assertEquals(3389, target.getPort());
        assertEquals("秘密-password-123", target.getPassword()); assertEquals("CORP", target.getDomain());
        assertEquals("Administrator", target.getUsername()); assertFalse(target.isIgnoreCertificate());
        assertEquals(0, service.list(2L).size());
        assertEquals(1, service.delete(created.getId())); assertTrue(service.list(1L).isEmpty());
        assertThrows(BusinessRuntimeException.class, () -> service.resolve(created.getId()));
    }

    @Test void blankPasswordAndOmittedUpdateFieldsPreserveExistingProfile() {
        long id = service.create(request()).getId();
        byte[] before = jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?", byte[].class, id);
        RdpConnectionService.SaveRequest req = new RdpConnectionService.SaveRequest();
        req.setName("Renamed"); req.setPassword("  "); req.setIgnoreCertificate(true); req.setDomain("");
        RdpConnectionService.ConnectionVO updated = service.update(id, req);
        assertEquals("Renamed", updated.getName()); assertEquals(1L, updated.getHostId());
        assertEquals("Administrator", updated.getUsername()); assertEquals(3389, updated.getPort());
        assertEquals(1, updated.getEnabled()); assertTrue(updated.isIgnoreCertificate());
        assertEquals("", updated.getDomain());
        assertArrayEquals(before, jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?", byte[].class, id));
        assertEquals("秘密-password-123", service.resolve(id).getPassword());
        req.setPassword("new secret"); req.setHostId(2L); req.setPort(3390);
        service.update(id, req);
        assertEquals("new secret", service.resolve(id).getPassword());
        assertEquals("192.0.2.20", service.resolve(id).getHost()); assertEquals(3390, service.resolve(id).getPort());
    }

    @Test void draftUsesUnsavedValuesAndStoredBlankPasswordWithoutWrites() {
        long id = service.create(request()).getId();
        jdbc.update("UPDATE rdp_connection SET enabled=0 WHERE id=?",id);
        byte[] before = jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?",byte[].class,id);
        RdpConnectionService.TestRequest draft = new RdpConnectionService.TestRequest();
        draft.setId(id); draft.setHostId(2L); draft.setUsername("draft-user"); draft.setDomain("DRAFT");
        draft.setPassword(" "); draft.setPort(3397); draft.setIgnoreCertificate(true);
        RdpTarget target = service.resolveTest(draft);
        assertEquals("192.0.2.20",target.getHost()); assertEquals("draft-user",target.getUsername());
        assertEquals("DRAFT",target.getDomain()); assertEquals(3397,target.getPort());
        assertTrue(target.isIgnoreCertificate()); assertEquals("秘密-password-123",target.getPassword());
        assertEquals("Administrator",service.get(id).getUsername()); assertEquals(0,service.get(id).getEnabled());
        assertArrayEquals(before,jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?",byte[].class,id));
        RdpConnectionService.TestRequest saved = new RdpConnectionService.TestRequest(); saved.setId(id);
        assertEquals("Administrator", service.resolveTest(saved).getUsername());
        assertFalse(service.resolveTest(saved).isIgnoreCertificate());
        draft.setPassword("unsaved-new"); assertEquals("unsaved-new",service.resolveTest(draft).getPassword());
        jdbc.update("UPDATE ssh_host SET enabled=0 WHERE id=2");
        assertThrows(BusinessRuntimeException.class,()->service.resolveTest(draft));
    }

    @Test void disabledHostOrProfileCannotResolve() {
        long id = service.create(request()).getId();
        jdbc.update("UPDATE ssh_host SET enabled=0 WHERE id=1");
        assertThrows(BusinessRuntimeException.class, () -> service.resolve(id));
        jdbc.update("UPDATE ssh_host SET enabled=1 WHERE id=1");
        RdpConnectionService.SaveRequest req = new RdpConnectionService.SaveRequest(); req.setEnabled(0);
        service.update(id, req);
        assertThrows(BusinessRuntimeException.class, () -> service.resolve(id));
    }

    @Test void validatesRequiredFieldsLimitsAndMissingRowsWithoutLeakingPasswords() {
        for (String field : Arrays.asList("hostId","name","username","password")) {
            RdpConnectionService.SaveRequest req = request(); ReflectionTestUtils.setField(req,field,null);
            assertThrows(BusinessRuntimeException.class, () -> service.create(req),field);
        }
        for (int port : new int[]{0,65536,-1}) {
            RdpConnectionService.SaveRequest req=request(); req.setPort(port);
            assertThrows(BusinessRuntimeException.class, () -> service.create(req));
        }
        for (String field : Arrays.asList("name","username","domain")) {
            RdpConnectionService.SaveRequest req=request(); ReflectionTestUtils.setField(req,field,String.join("",Collections.nCopies(256,"x")));
            assertThrows(BusinessRuntimeException.class, () -> service.create(req),field);
        }
        RdpConnectionService.SaveRequest invalid=request(); invalid.setEnabled(2);
        assertThrows(BusinessRuntimeException.class, () -> service.create(invalid));
        invalid.setEnabled(1); invalid.setHostId(999L);
        assertThrows(BusinessRuntimeException.class, () -> service.create(invalid));
        assertThrows(BusinessRuntimeException.class, () -> service.list(999L));
        assertThrows(BusinessRuntimeException.class, () -> service.update(999L,request()));
        assertThrows(BusinessRuntimeException.class, () -> service.delete(999L));
        assertThrows(BusinessRuntimeException.class, () -> service.create(null));
        assertFalse(request().toString().contains("秘密"));
        long id=service.create(request()).getId();
        RdpConnectionService.SaveRequest blank=new RdpConnectionService.SaveRequest(); blank.setName("  ");
        assertThrows(BusinessRuntimeException.class, () -> service.update(id,blank));
    }

    @Test void migratesLegacyRdpTableWithoutChangingEncryptedSecret() {
        jdbc.execute("DROP TABLE rdp_connection");
        jdbc.execute("CREATE TABLE rdp_connection(id INTEGER PRIMARY KEY,host_id INTEGER,name TEXT,username TEXT,domain TEXT,port INTEGER,ignore_certificate INTEGER,enabled INTEGER,secret_enc BLOB)");
        byte[] secret = cipher.encryptSecretJson("{\"password\":\"legacy-secret\"}");
        jdbc.update("INSERT INTO rdp_connection VALUES(1,1,'Legacy','admin','',3389,0,1,?)",secret);
        service.initializeSchema(); service.initializeSchema();
        assertEquals("",service.get(1).getRemark()); assertEquals(0,service.get(1).getSortOrder());
        assertEquals("legacy-secret",service.resolve(1).getPassword());
        assertArrayEquals(secret,jdbc.queryForObject("SELECT secret_enc FROM rdp_connection",byte[].class));
    }

    @Test void existingDatabaseInitializationIsIdempotentAndHostDeleteCascades() {
        long id = service.create(request()).getId();
        service.initializeSchema();
        assertEquals(1,service.list(1L).size());
        assertEquals(1,jdbc.queryForObject("PRAGMA foreign_keys",Integer.class));
        jdbc.update("DELETE FROM ssh_host WHERE id=1");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection WHERE id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host WHERE id=2",Integer.class));
    }
}
