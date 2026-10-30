package com.doumiot.sshconsole.rdp;

import com.doumiot.ShellEndApplication;
import com.doumiot.config.ConfigurationArchive;
import com.doumiot.sshconsole.service.SshConfigurationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;
import com.doumiot.sshconsole.service.SshGroupService;
import com.doumiot.sshconsole.service.SshHostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ShellEndApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"doumi.storage.data-dir=./target/rdp-integration-data/${random.uuid}", "doumi.ssh-console.credential-master-key="})
@AutoConfigureMockMvc
class RdpConnectionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SshGroupService groups;
    @Autowired SshHostService hosts;
    @Autowired RdpConnectionService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired SshConfigurationService configurations;
    private final ObjectMapper mapper = new ObjectMapper();

    long group() {
        SshGroupService.SshGroupSaveRequest req = new SshGroupService.SshGroupSaveRequest(); req.setName("RDP integration");
        return groups.create(req).getId();
    }
    long host(long group) {
        SshHostService.SshHostSaveRequest req = new SshHostService.SshHostSaveRequest();
        req.setGroupId(group); req.setName("Windows"); req.setHost("192.0.2.30"); req.setPort(2222);
        return hosts.create(req).getId();
    }
    long profile(long host) {
        RdpConnectionService.SaveRequest req = new RdpConnectionService.SaveRequest();
        req.setHostId(host); req.setName("Desktop"); req.setUsername("Administrator"); req.setPassword("cascade-secret");
        return profiles.create(req).getId();
    }

    @Test void crudRoutesRequireHostIdAndNeverReturnSecrets() throws Exception {
        long group = group(); long host = host(group);
        try {
            mvc.perform(get("/api/ssh-console/rdp/connections")).andExpect(jsonPath("$.success").value(false));
            String response = mvc.perform(post("/api/ssh-console/rdp/connections").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hostId\":" + host + ",\"name\":\"Desktop\",\"username\":\"Administrator\",\"password\":\"rest-secret\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasPassword").value(true))
                    .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.secretHint").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("rest-secret"));
            long id = mapper.readTree(response).get("data").get("id").asLong();
            mvc.perform(put("/api/ssh-console/rdp/connections/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Renamed\",\"password\":\"\",\"port\":3391}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.port").value(3391));
            assertEquals("rest-secret", profiles.resolve(id).getPassword());
            mvc.perform(get("/api/ssh-console/rdp/connections").param("hostId", Long.toString(host)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("Renamed"))
                    .andExpect(jsonPath("$.data[0].password").doesNotExist());
            mvc.perform(delete("/api/ssh-console/rdp/connections/" + id)).andExpect(status().isOk());
            assertTrue(profiles.list(host).isEmpty());
            assertEquals(2222, hosts.getById(host).getPort());
        } finally { groups.delete(group); }
    }

    @Test void actualHostAndGroupDeletesCascadeRdpProfiles() {
        long group = group(); long firstHost = host(group); long secondHost = host(group);
        long firstProfile = profile(firstHost); long secondProfile = profile(secondHost);
        assertEquals(1,jdbc.queryForObject("PRAGMA foreign_keys",Integer.class));
        hosts.delete(firstHost);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection WHERE id=?",Integer.class,firstProfile));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection WHERE id=?",Integer.class,secondProfile));
        groups.delete(group);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection WHERE id=?",Integer.class,secondProfile));
    }
    Set<Long> groupIds() { return new HashSet<>(jdbc.queryForList("SELECT id FROM ssh_group", Long.class)); }
    void removeNewGroups(Set<Long> before) {
        for (Long id : groupIds()) if (!before.contains(id)) groups.delete(id);
    }

    @Test void archivesRoundTripRdpPasswordsAndRemapHostIdsInPlainAndEncryptedFormats() throws Exception {
        Set<Long> before = groupIds();
        try {
            long group = group(); long host = host(group);
            RdpConnectionService.SaveRequest req = new RdpConnectionService.SaveRequest();
            req.setHostId(host); req.setName("Archive desktop"); req.setUsername("Administrator");
            req.setPassword("archive-secret"); req.setDomain("CORP"); req.setPort(3395); req.setIgnoreCertificate(true);
            long sourceId = profiles.create(req).getId();
            for (boolean encrypted : new boolean[]{false,true}) {
                ConfigurationArchive.Archive archive = configurations.exportAll("archive-password", encrypted);
                ObjectNode saved = (ObjectNode) mapper.readTree(ConfigurationArchive.decrypt(archive,"archive-password"));
                assertTrue(saved.has("rdpConnections"), "RDP connections must be present in backups");
                assertEquals("archive-secret", saved.get("rdpConnections").get(0).get("password").asText());
                Map<String,Integer> counts = configurations.importAll(archive,"archive-password");
                assertEquals(encrypted ? 2 : 1,counts.get("rdpConnections"));
            }
            List<Map<String,Object>> imported = jdbc.queryForList("SELECT id,host_id FROM rdp_connection WHERE id<>?",sourceId);
            // The second archive includes the first imported copy as well as the original.
            assertEquals(3, imported.size());
            for (Map<String,Object> row : imported) {
                assertNotEquals(host,((Number)row.get("host_id")).longValue());
                RdpTarget target = profiles.resolve(((Number)row.get("id")).longValue());
                assertEquals("archive-secret",target.getPassword()); assertEquals(3395,target.getPort());
                assertEquals("CORP",target.getDomain()); assertTrue(target.isIgnoreCertificate());
                assertEquals("192.0.2.30",target.getHost());
            }
        } finally { removeNewGroups(before); }
    }

    @Test void invalidRdpArchiveReferencesAndIdsRollbackAllImportedRows() throws Exception {
        Set<Long> before = groupIds();
        try {
            long group=group(); long host=host(group); long id=profile(host);
            ObjectNode source=(ObjectNode)mapper.readTree(ConfigurationArchive.decrypt(configurations.exportAll(null,false),null));
            int groupCount=jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group",Integer.class);
            int hostCount=jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host",Integer.class);
            int profileCount=jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection",Integer.class);
            for (String broken : Arrays.asList("host","missingId","negativeId","duplicateId","nullList")) {
                ObjectNode snapshot=source.deepCopy();
                ArrayNode rows=(ArrayNode)snapshot.get("rdpConnections");
                assertNotNull(rows,"RDP profiles must be included in archive payload");
                ObjectNode row=(ObjectNode)rows.get(0);
                if ("host".equals(broken)) row.put("hostId",999999L);
                if ("missingId".equals(broken)) row.remove("id");
                if ("negativeId".equals(broken)) row.put("id",-1);
                if ("duplicateId".equals(broken)) rows.add(row.deepCopy());
                if ("nullList".equals(broken)) snapshot.putNull("rdpConnections");
                ConfigurationArchive.Archive malformed=ConfigurationArchive.plain(mapper.writeValueAsBytes(snapshot));
                assertThrows(Exception.class,()->configurations.importAll(malformed,null),broken);
                assertEquals(groupCount,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group",Integer.class),broken);
                assertEquals(hostCount,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host",Integer.class),broken);
                assertEquals(profileCount,jdbc.queryForObject("SELECT COUNT(*) FROM rdp_connection",Integer.class),broken);
                assertEquals("cascade-secret",profiles.resolve(id).getPassword());
            }
            // Old archives omit the new collection and remain importable.
            ObjectNode legacy=source.deepCopy();legacy.remove("rdpConnections");
            Map<String,Integer> imported=configurations.importAll(ConfigurationArchive.plain(mapper.writeValueAsBytes(legacy)),null);
            assertEquals(0,imported.get("rdpConnections"));
        } finally { removeNewGroups(before); }
    }

}
