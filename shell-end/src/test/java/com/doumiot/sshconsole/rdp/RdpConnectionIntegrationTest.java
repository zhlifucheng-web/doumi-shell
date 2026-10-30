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
    @Autowired RdpProperties rdpProperties;
    @Autowired com.doumiot.sshconsole.service.SshTreeService tree;
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

    @Test void treeContainsTypedRdpPeerAndLookupNeverExposesSecrets() throws Exception {
        long group = group(); long host = host(group);
        try {
            long id = profile(host);
            mvc.perform(get("/api/ssh-console/tree/children").param("parentType","HOST").param("parentId",Long.toString(host)))
                .andExpect(jsonPath("$.data[0].nodeType").value("RDP_ACCOUNT"))
                .andExpect(jsonPath("$.data[0].key").value("r:"+id))
                .andExpect(jsonPath("$.data[0].extra.protocol").value("RDP"));
            mvc.perform(get("/api/ssh-console/rdp/connections/"+id))
                .andExpect(jsonPath("$.data.username").value("Administrator"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        } finally { groups.delete(group); }
    }

    @Test void savedIdOnlyTestRouteUsesCredentialsWithoutSaving() throws Exception {
        long group=group(); long host=host(group);
        int oldPort=rdpProperties.getGuacdPort();
        try (RdpConnectionTestServiceTest.FakeGuacd gateway=new RdpConnectionTestServiceTest.FakeGuacd("success")) {
            long id=profile(host); jdbc.update("UPDATE rdp_connection SET enabled=0 WHERE id=?",id);
            byte[] before=jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?",byte[].class,id);
            rdpProperties.setGuacdPort(gateway.server.getLocalPort());
            String response=mvc.perform(post("/api/ssh-console/rdp/connections/test").contentType(MediaType.APPLICATION_JSON).content("{\"id\":"+id+"}"))
                .andExpect(jsonPath("$.data.success").value(true)).andExpect(jsonPath("$.data.elapsedMs").isNumber())
                .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("cascade-secret"));
            assertArrayEquals(before,jdbc.queryForObject("SELECT secret_enc FROM rdp_connection WHERE id=?",byte[].class,id));
            assertEquals(0,profiles.get(id).getEnabled());
        } finally {rdpProperties.setGuacdPort(oldPort);groups.delete(group);}
    }

    @Test void typedMoveReordersOnlySameProtocolSiblingsAndRejectsStaleOrders() throws Exception {
        long group=group(); long first=host(group); long second=host(group);
        try {
            long r1=profile(first),r2=profile(first),r3=profile(second);
            jdbc.update("INSERT INTO ssh_account(host_id,username,auth_type,secret_enc) VALUES (?,'ssh-user','PASSWORD',?)",first,new byte[]{1});
            long ssh=jdbc.queryForObject("SELECT id FROM ssh_account WHERE host_id=?",Long.class,first);
            assertEquals("SSH",tree.listChildren("HOST",first).get(0).getExtra().get("protocol"));
            com.doumiot.sshconsole.service.SshTreeService.ReorderRequest request=new com.doumiot.sshconsole.service.SshTreeService.ReorderRequest();
            request.setNodeType("RDP_ACCOUNT"); request.setId(r2); request.setParentId(second);
            request.setOrderedIds(Arrays.asList(r3,r2)); request.setSourceOrderedIds(Collections.singletonList(r1));
            tree.moveAndReorder(request);
            assertEquals(second,profiles.get(r2).getHostId()); assertEquals(1,profiles.get(r2).getSortOrder());
            request.setNodeType("ACCOUNT"); request.setId(ssh); request.setParentId(first);request.setOrderedIds(Collections.singletonList(ssh));
            tree.moveAndReorder(request); // SSH sibling validation must ignore RDP nodes.
            request.setNodeType("RDP_ACCOUNT");request.setId(r1);request.setParentId(first);request.setOrderedIds(Collections.emptyList());
            assertThrows(Exception.class,()->tree.moveAndReorder(request));
            request.setParentId(999999L);request.setOrderedIds(Collections.singletonList(r1));
            assertThrows(Exception.class,()->tree.moveAndReorder(request));
            assertEquals(first,profiles.get(r1).getHostId());
        } finally {groups.delete(group);}
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
            req.setRemark("archive remark"); req.setSortOrder(7);
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
                assertEquals("archive remark",profiles.get(((Number)row.get("id")).longValue()).getRemark());
                assertEquals(7,profiles.get(((Number)row.get("id")).longValue()).getSortOrder());
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
