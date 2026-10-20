package com.doumiot.sshconsole.ai;

import com.doumiot.ShellEndApplication;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=ShellEndApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"doumi.storage.data-dir=./target/integration-data/${random.uuid}","doumi.ssh-console.credential-master-key=","logging.level.root=WARN"})
class AiIntegrationTest {
    @Autowired AiProviderService providers;@Autowired AiTools tools;@Autowired JdbcTemplate jdbc;
    @Autowired CredentialCipher cipher;@Autowired TestRestTemplate http;
    @Autowired AiChatService chats;
    @Test void realCompatibleHttpProviderCanReadRequestConfirmationAndComplete() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger rounds=new java.util.concurrent.atomic.AtomicInteger();
        JsonNode create=tools.call("test","group_create",AiJson.object("data",AiJson.object("name","ai-delete-"+UUID.randomUUID())));
        long group=tools.confirm("test",create.path("actionId").asText(),true).path("result").path("id").asLong();
        assertTrue(group>0);
        server.createContext("/v1/chat/completions",exchange->{
            JsonNode request=AiJson.JSON.readTree(exchange.getRequestBody());int round=rounds.incrementAndGet();JsonNode message;
            if(round<=2) {
                String name=round==1?"data_tables":"group_delete";
                message=AiJson.object("role","assistant","content","","tool_calls",Collections.singletonList(AiJson.object("id","step"+round,"type","function","function",AiJson.object("name",name,"arguments",round==1?"{}":AiJson.object("id",group).toString()))));
            } else {
                boolean hasTool=false;for(JsonNode m:request.path("messages"))if(m.path("tool_call_id").asText().equals("step2"))hasTool=true;
                message=AiJson.object("role","assistant","content",hasTool?"操作已完成":"缺少实际结果");
            }
            byte[] reply=AiJson.object("choices",Collections.singletonList(AiJson.object("message",message))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();String provider=null,conversation=null;
        try {
            AiProviderService.Settings settings=new AiProviderService.Settings();settings.setName("fake-openai-e2e");settings.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");settings.setModel("fake-tools-model");
            provider=((JsonNode)providers.save(settings)).path("id").asText();
            JsonNode serverContext=AiJson.object("accountId",512,"hostId",44,"name","history-test","address","192.0.2.10","port",22,"username","tester");
            conversation=chats.create(provider,"ask",serverContext).path("id").asText();
            AiChatService.MessageRequest request=new AiChatService.MessageRequest();request.setText("查询数据表并删除测试分组");chats.send(conversation,request);
            JsonNode waiting=awaitIdle(conversation);assertEquals("awaiting_confirmation",waiting.path("state").asText());assertEquals(2,rounds.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group WHERE id=?",Integer.class,group));
            chats.approve(conversation,Collections.singletonList(waiting.path("pending").path(0).path("actionId").asText()),true);
            JsonNode complete=awaitIdle(conversation);assertEquals("idle",complete.path("state").asText());assertTrue(complete.toString().contains("操作已完成"));assertEquals(3,rounds.get());
            assertTrue(complete.path("events").toString().contains("timestamp"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group WHERE id=?",Integer.class,group));
            assertEquals(conversation,chats.latest(512L,null).path("id").asText());
            assertTrue(chats.history(null).toString().contains("查询数据表并删除测试分组"));
            byte[] stored=jdbc.queryForObject("SELECT events_enc FROM ai_conversation WHERE id=?",byte[].class,conversation);
            assertFalse(new String(stored,StandardCharsets.UTF_8).contains("操作已完成"));
            assertTrue(cipher.decryptToJson(stored).contains("操作已完成"));
            AiChatService restarted=new AiChatService();
            ReflectionTestUtils.setField(restarted,"jdbc",jdbc);ReflectionTestUtils.setField(restarted,"cipher",cipher);
            ReflectionTestUtils.setField(restarted,"tools",tools);ReflectionTestUtils.setField(restarted,"files",new AiFiles());
            restarted.initialize();
            try { assertTrue(restarted.status(conversation,0).toString().contains("操作已完成")); }
            finally { restarted.close(); }
        }finally{server.stop(0);if(conversation!=null)chats.delete(conversation);if(provider!=null)providers.delete(provider);}
    }
    private JsonNode awaitIdle(String id) throws Exception {
        long until=System.currentTimeMillis()+10000;
        do{JsonNode result=chats.status(id,0);if(!result.path("running").asBoolean())return result;Thread.sleep(10);}while(System.currentTimeMillis()<until);
        throw new AssertionError("Chat did not finish");
    }
    @Test void providerKeysAreEncryptedNeverReturnedAndCanBePreservedOrCleared() throws Exception {
        AiProviderService.Settings settings=new AiProviderService.Settings();settings.setName("local-test");settings.setBaseUrl("http://127.0.0.1:12345/v1");settings.setModel("demo");settings.setApiKey("private-provider-key");
        String id=((JsonNode)providers.save(settings)).path("id").asText();
        byte[] stored=jdbc.queryForObject("SELECT api_key_enc FROM ai_provider WHERE id=?",byte[].class,id);
        assertFalse(new String(stored,StandardCharsets.UTF_8).contains("private-provider-key"));assertTrue(cipher.decryptToJson(stored).contains("private-provider-key"));
        assertFalse(providers.list().toString().contains("private-provider-key"));
        settings.setId(id);settings.setApiKey("");providers.save(settings);assertEquals("private-provider-key",providers.credentials(id).apiKey);
        settings.setClearApiKey(true);providers.save(settings);assertEquals("",providers.credentials(id).apiKey);
        providers.delete(id);assertThrows(IllegalArgumentException.class,()->providers.credentials(id));
    }
    @Test void genericImportPreservesNestedGroupsAndDisablesMissingCredentials() throws Exception {
        String marker="ai-import-"+UUID.randomUUID();
        JsonNode snapshot=AiJson.JSON.readTree("{\"groups\":[{\"id\":2,\"name\":\"child\",\"parentId\":1},{\"id\":1,\"name\":\""+marker+"\"}],\"hosts\":[{\"id\":3,\"groupId\":2,\"name\":\"demo\",\"host\":\"127.0.0.1\",\"port\":22}],\"accounts\":[{\"id\":4,\"hostId\":3,\"username\":\"demo\",\"authType\":\"PASSWORD\",\"secret\":{},\"enabled\":0}],\"scripts\":[]}");
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host",Integer.class);
        String action=tools.call("import-owner","configuration_import",AiJson.object("data",snapshot)).path("actionId").asText();
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host",Integer.class));
        JsonNode result=tools.confirm("import-owner",action,true);assertEquals(2,result.path("result").path("groups").asInt());
        Long root=jdbc.queryForObject("SELECT id FROM ssh_group WHERE name LIKE ?",Long.class,marker+"%");
        Long child=jdbc.queryForObject("SELECT id FROM ssh_group WHERE parent_id=? AND name='child'",Long.class,root);
        Long host=jdbc.queryForObject("SELECT id FROM ssh_host WHERE group_id=?",Long.class,child);
        assertEquals(0,jdbc.queryForObject("SELECT enabled FROM ssh_account WHERE host_id=?",Integer.class,host));
        int groups=jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group",Integer.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("hosts").path(0)).put("groupId",999);
        String invalid=tools.call("import-owner","configuration_import",AiJson.object("data",snapshot)).path("actionId").asText();
        assertThrows(Exception.class,()->tools.confirm("import-owner",invalid,true));
        assertEquals(groups,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group",Integer.class));
    }
    @Test void apiCatalogAndValidationUseTheExistingResponseContract() {
        org.springframework.http.ResponseEntity<JsonNode> catalog=http.getForEntity("/api/ssh-console/ai/tools",JsonNode.class);
        assertTrue(catalog.getHeaders().getCacheControl().contains("no-store"));assertTrue(catalog.getBody().path("success").asBoolean());assertTrue(catalog.getBody().path("data").size()>=45);
        JsonNode error=http.postForObject("/api/ssh-console/ai/tools/call",AiJson.object("name","unknown","arguments",AiJson.object()),JsonNode.class);
        assertFalse(error.path("success").asBoolean());assertTrue(error.toString().contains("未开放"));
    }
}
