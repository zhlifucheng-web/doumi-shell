package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.service.SshGroupService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiWorkflowTest {
    AiProviderService providers; AiCompletionClient completion; AiTools tools; AiFiles files; AiChatService chats;
    @BeforeEach void setup() {
        providers=mock(AiProviderService.class);completion=mock(AiCompletionClient.class);tools=new AiTools();files=new AiFiles();
        tools.initialize();chats=new AiChatService();
        ReflectionTestUtils.setField(chats,"providers",providers);ReflectionTestUtils.setField(chats,"completion",completion);
        ReflectionTestUtils.setField(chats,"tools",tools);ReflectionTestUtils.setField(chats,"files",files);
        when(providers.credentials(anyString())).thenReturn(new AiProviderService.Credentials());
    }
    @AfterEach void close(){chats.close();files.close();}
    String chat(){return chats.create("provider").path("id").asText();}
    JsonNode call(String id,String name,JsonNode args){return AiJson.object("id",id,"type","function","function",AiJson.object("name",name,"arguments",args.toString()));}
    JsonNode catalogTool(String name){for(JsonNode item:tools.catalog())if(name.equals(item.path("name").asText()))return item;return AiJson.JSON.nullNode();}
    JsonNode toolMessage(JsonNode... calls){return AiJson.object("role","assistant","content","", "tool_calls",Arrays.asList(calls));}
    void send(String id) throws Exception {AiChatService.MessageRequest request=new AiChatService.MessageRequest();request.setText("处理任务");chats.send(id,request);}
    JsonNode done(String id) throws Exception {
        long until=System.currentTimeMillis()+5000;JsonNode status;
        do{status=chats.status(id,0);if(!status.path("running").asBoolean())return status;Thread.sleep(10);}while(System.currentTimeMillis()<until);
        fail("AI worker did not finish");return null;
    }
    @Test void allFivePagesHaveToolsAndNoVendorSpecificImporter() {
        Set<String> pages=new HashSet<>();tools.catalog().forEach(t->pages.add(t.path("page").asText()));
        assertEquals(new HashSet<>(Arrays.asList("terminal","scripts","processes","disk-usage","data-management")),pages);
        assertTrue(tools.catalog().size()>=45);assertFalse(tools.catalog().toString().toLowerCase().contains("finalshell"));
    }
    @Test void conversationOrderingUsesLatestEventTimeAndFallsBackToCreationTime() {
        JsonNode events=AiJson.JSON.createArrayNode().add(AiJson.object("role","user","timestamp",200L)).add(AiJson.object("role","assistant","timestamp",350L));
        assertEquals(350L,AiChatService.latestMessageAt(events,100L));
        assertEquals(100L,AiChatService.latestMessageAt(AiJson.JSON.createArrayNode(),100L));
    }
    @Test void readToolResultIsPassedToTheNextProviderRound() throws Exception {
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(toolMessage(call("read1","cron_preview",AiJson.object("cron","0 9 * * *"))),AiJson.object("content","查询完成"));
        String id=chat();send(id);JsonNode status=done(id);
        assertEquals("idle",status.path("state").asText());assertTrue(status.toString().contains("查询完成"));
        verify(completion).stream(any(),argThat(messages->messages.stream().anyMatch(m->m.path("tool_call_id").asText().equals("read1")&&m.path("content").asText().contains("result"))),any(),any(),any());
        assertEquals(status.path("cursor").asInt(),chats.status(id,status.path("cursor").asInt()).path("cursor").asInt());
        assertEquals(0,chats.status(id,status.path("cursor").asInt()).path("events").size());
    }
    @Test void mutationRequiresApprovalAndCannotBeReplayedOrStolen() throws Exception {
        SshGroupService groups=mock(SshGroupService.class);ReflectionTestUtils.setField(tools,"groups",groups);
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(toolMessage(call("delete1","group_delete",AiJson.object("id",9))),AiJson.object("content","已处理"));
        String id=chat();send(id);JsonNode pending=done(id);String action=pending.path("pending").path(0).path("actionId").asText();
        assertEquals("awaiting_confirmation",pending.path("state").asText());verify(groups,never()).delete(anyLong());
        assertThrows(IllegalArgumentException.class,()->tools.confirm("other",action,true));
        chats.approve(id,Collections.singletonList(action),true);JsonNode result=done(id);verify(groups,times(1)).delete(9);
        assertEquals(1,result.path("revision").asInt());assertThrows(IllegalArgumentException.class,()->tools.confirm(id,action,true));
        assertThrows(IllegalArgumentException.class,()->chats.approve(id,Collections.singletonList(action),true));
    }
    @Test void askModeRunsReadsDirectlyAndRequiresApprovalForEveryWrite() throws Exception {
        JsonNode read=tools.call("owner","cron_preview",AiJson.object("cron","0 9 * * *"));
        assertFalse(read.path("requiresConfirmation").asBoolean());
        JsonNode create=tools.call("owner","group_create",AiJson.object("data",AiJson.object("name","demo")));
        assertTrue(create.path("requiresConfirmation").asBoolean());
        assertTrue(catalogTool("group_create").path("requiresConfirmation").asBoolean());
        assertFalse(catalogTool("cron_preview").path("requiresConfirmation").asBoolean());
    }
    @Test void automaticAuthorizationRunsConfirmedMutationAndKeepsAuditEvent() throws Exception {
        SshGroupService groups=mock(SshGroupService.class);ReflectionTestUtils.setField(tools,"groups",groups);
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(toolMessage(call("delete-auto","group_delete",AiJson.object("id",9))),AiJson.object("content","已完成"));
        String id=chat();chats.setAuthorizationMode(id,"auto");send(id);JsonNode status=done(id);
        verify(groups,times(1)).delete(9);assertEquals(0,status.path("pending").size());assertEquals("auto",status.path("authorizationMode").asText());
        assertTrue(status.toString().contains("auto_authorized"));assertTrue(status.toString().contains("已完成"));
    }
    @Test void authorizationAndProviderCanChangeOnlyWhenConversationIsIdle() throws Exception {
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(AiJson.object("content","第一条完成"));
        String id=chat();send(id);done(id);
        JsonNode switched=chats.setProvider(id,"provider-two");assertEquals("provider-two",switched.path("providerId").asText());
        assertEquals("ask",switched.path("authorizationMode").asText());
        JsonNode automatic=chats.setAuthorizationMode(id,"auto");assertEquals("auto",automatic.path("authorizationMode").asText());
        assertThrows(IllegalArgumentException.class,()->chats.setAuthorizationMode(id,"unknown"));
        assertTrue(automatic.toString().contains("第一条完成"));
    }
    @Test void refusalDoesNotExecuteAndResumesConversation() throws Exception {
        SshGroupService groups=mock(SshGroupService.class);ReflectionTestUtils.setField(tools,"groups",groups);
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(toolMessage(call("delete1","group_delete",AiJson.object("id",9))),AiJson.object("content","取消操作"));
        String id=chat();send(id);String action=done(id).path("pending").path(0).path("actionId").asText();
        chats.approve(id,Collections.singletonList(action),false);assertTrue(done(id).toString().contains("cancelled"));verifyNoInteractions(groups);
    }
    @Test void pendingArgumentsAreImmutableAndCredentialsMasked() throws Exception {
        SshGroupService groups=mock(SshGroupService.class);ReflectionTestUtils.setField(tools,"groups",groups);
        JsonNode args=AiJson.object("id",9);String action=tools.call("owner","group_delete",args).path("actionId").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode)args).put("id",99);tools.confirm("owner",action,true);verify(groups).delete(9);
        String masked=AiJson.mask(AiJson.object("data",AiJson.object("secret",AiJson.object("password","private-secret"),"apiKey","provider-secret"))).toString();
        assertFalse(masked.contains("private-secret"));assertFalse(masked.contains("provider-secret"));
        assertThrows(IllegalArgumentException.class,()->tools.call("owner","group_delete",AiJson.object("id",9,"sql","DROP TABLE x")));
    }
    @Test void stoppingInFlightProviderPreventsNewToolActions() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        when(completion.stream(any(),anyList(),any(),any(),any())).thenAnswer(inv->{started.countDown();release.await(3,TimeUnit.SECONDS);return toolMessage(call("delete1","group_delete",AiJson.object("id",9)));});
        String id=chat();send(id);assertTrue(started.await(2,TimeUnit.SECONDS));chats.stop(id);release.countDown();
        JsonNode status=done(id);assertEquals(0,status.path("pending").size());assertEquals("idle",status.path("state").asText());assertTrue(status.toString().contains("已停止"));
        verify(completion,times(1)).stream(any(),anyList(),any(),any(),any());
    }
    @Test void malformedToolArgumentsProduceAnErrorResultAndNoAction() throws Exception {
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(toolMessage(AiJson.object("id","bad","function",AiJson.object("name","group_delete","arguments","{bad"))),AiJson.object("content","参数错误"));
        String id=chat();send(id);JsonNode status=done(id);assertTrue(status.toString().contains("error"));assertEquals(0,status.path("pending").size());
    }
    @Test void attachmentsPreserveDirectoriesAndEnforceConversationOwnership() throws Exception {
        JsonNode added=files.add("owner",Collections.singletonList(new MockMultipartFile("files","config.json","application/json","{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8))),Collections.singletonList("root/dev/config.json"));
        String id=added.path(0).path("id").asText();JsonNode context=files.context("owner",Collections.singletonList(id));
        assertEquals("root/dev/config.json",context.path(0).path("path").asText());assertTrue(context.toString().contains("demo"));
        assertThrows(IllegalArgumentException.class,()->files.context("other",Collections.singletonList(id)));
        assertThrows(IllegalArgumentException.class,()->AiFiles.relativePath("../config.json"));
        assertThrows(IllegalArgumentException.class,()->AiFiles.relativePath("C:/config.json"));
        files.removeOwner("owner");assertThrows(IllegalArgumentException.class,()->files.read("owner",id,0,10));
    }
    @Test void binaryAttachmentsAreMetadataOnly() throws Exception {
        JsonNode added=files.add("owner",Collections.singletonList(new MockMultipartFile("files","x.bin","application/octet-stream",new byte[]{0,1,2})),Collections.singletonList("x.bin"));
        String id=added.path(0).path("id").asText();assertFalse(files.context("owner",Collections.singletonList(id)).path(0).has("content"));
        assertThrows(IllegalArgumentException.class,()->files.read("owner",id,0,10));
    }
    @Test void refusalsAndEmptyProviderRepliesAreVisibleToTheUser() throws Exception {
        when(completion.stream(any(),anyList(),any(),any(),any())).thenReturn(AiJson.object("content",null,"refusal","不能完成此请求"),AiJson.object("content",null));
        String id=chat();send(id);assertTrue(done(id).toString().contains("不能完成此请求"));
        send(id);JsonNode error=done(id);assertEquals("error",error.path("state").asText());assertTrue(error.toString().contains("未返回文本"));
    }
    @Test void expiredConfirmationIsNeverExecuted() throws Exception {
        SshGroupService groups=mock(SshGroupService.class);ReflectionTestUtils.setField(tools,"groups",groups);
        String action=tools.call("owner","group_delete",AiJson.object("id",9)).path("actionId").asText();
        Map<?,?> pending=(Map<?,?>)ReflectionTestUtils.getField(tools,"pending");ReflectionTestUtils.setField(pending.get(action),"expires",0L);
        assertThrows(IllegalArgumentException.class,()->tools.confirm("owner",action,true));verifyNoInteractions(groups);
    }
}
