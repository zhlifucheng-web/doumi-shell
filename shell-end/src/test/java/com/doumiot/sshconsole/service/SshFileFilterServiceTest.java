package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.doumiot.sshconsole.ssh.SshFileFilterRemoteFactory;
import com.doumiot.sshconsole.ssh.SshFileFilterRemoteCursor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshFileFilterServiceTest {
    private final SshSftpService files = new SshSftpService();
    private final SshFileFilterService service = new SshFileFilterService();
    private final SshSessionRegistry registry = mock(SshSessionRegistry.class);
    private final SSHClient client = mock(SSHClient.class);
    private final SFTPClient sftp = mock(SFTPClient.class);
    private final RemoteFile remote = mock(RemoteFile.class);
    private byte[] bytes = "first\nhit".getBytes(StandardCharsets.UTF_8);
    private long mtime = 1;
    private final SshFileFilterRemoteFactory remoteFactory=mock(SshFileFilterRemoteFactory.class);
    private int requestBytes;
    @BeforeEach void setup() throws Exception {
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry(); entry.setClient(client); entry.setHomeDir("/home/u");
        when(registry.get("owner")).thenReturn(entry);
        when(registry.get("other")).thenReturn(entry);
        when(client.isConnected()).thenReturn(true); when(client.newSFTPClient()).thenReturn(sftp);
        when(sftp.type(anyString())).thenReturn(FileMode.Type.REGULAR); when(sftp.open(anyString())).thenReturn(remote);
        when(remote.length()).thenAnswer(call -> (long)bytes.length);
        when(sftp.stat(anyString())).thenAnswer(call -> new FileAttributes.Builder().withSize(bytes.length).withAtimeMtime(mtime, mtime).build());
        when(remote.read(anyLong(), any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
            long pos = call.getArgument(0); byte[] to = call.getArgument(1); int off = call.getArgument(2); int len = call.getArgument(3);
            if (pos >= bytes.length) return -1;
            int count = Math.min(len, bytes.length - (int)pos); requestBytes += count;
            System.arraycopy(bytes, (int)pos, to, off, count); return count;
        });
        ReflectionTestUtils.setField(files,"sessionRegistry",registry); ReflectionTestUtils.setField(service,"files",files); ReflectionTestUtils.setField(service,"remoteScans",remoteFactory);
    }
    @AfterEach void cleanup() {service.closeAll();}
    @Test void remoteModeTransfersOnlyRowsAndRetainsCursorAcrossHistoryAndTail() throws Exception {
        SshFileFilterRemoteCursor remoteCursor=mock(SshFileFilterRemoteCursor.class);
        when(remoteFactory.open(eq("owner"),eq("/app.log"),eq("hit"),eq("UTF-8"),eq(true),eq(0))).thenReturn(remoteCursor);
        when(remoteCursor.size()).thenReturn(600000L);
        when(remoteCursor.next(anyString())).thenAnswer(c->new SshFileFilterService.Batch(c.getArgument(0),Collections.emptyList(),262144,600000,false,false))
                .thenAnswer(c->new SshFileFilterService.Batch(c.getArgument(0),Collections.singletonList(new com.doumiot.sshconsole.sftp.SshFileFilterScanner.Row(9,"hit",true)),600000,600000,true,false))
                .thenAnswer(c->new SshFileFilterService.Batch(c.getArgument(0),Collections.singletonList(new com.doumiot.sshconsole.sftp.SshFileFilterScanner.Row(10,"hit appended",true)),600013,600013,true,false));
        SshFileFilterService.Started started=service.start("owner","/app.log","hit","UTF-8",true,0);
        assertEquals("remote",started.getMode());
        SshFileFilterService.TailLease history=service.claimStream("owner",started.getScanId(),true);
        assertTrue(history.remote());assertFalse(service.nextTail(history,null).isEof());assertTrue(service.nextTail(history,null).isEof());
        service.releaseTail(history);
        SshFileFilterService.TailLease live=service.claimTail("owner",started.getScanId());
        assertEquals("hit appended",service.nextTail(live,null).getRows().get(0).getText());
        verify(remote,never()).read(anyLong(),any(byte[].class),anyInt(),anyInt());
        verify(remoteCursor,never()).close();service.cancel("owner",started.getScanId());verify(remoteCursor).close();
    }
    @Test void scansResolvedFileAndUpdatesEofPartialLine() throws Exception {
        String id = service.start("owner", "~/app.log", "hit", "UTF-8", true, 0).getScanId();
        SshFileFilterService.Batch first = service.next("owner",id);
        assertEquals(9,first.getOffset()); assertTrue(first.isEof()); assertEquals("hit",first.getRows().get(0).getText()); assertEquals(2,first.getRows().get(0).getLineNumber());
        bytes = "first\nhit again\n".getBytes(StandardCharsets.UTF_8); mtime++;
        SshFileFilterService.Batch tail = service.next("owner",id);
        assertEquals(2,tail.getRows().get(0).getLineNumber()); assertEquals("hit again",tail.getRows().get(0).getText());
        verify(sftp,atLeastOnce()).open("/home/u/app.log"); verify(remote,times(3)).close(); verify(sftp,times(3)).close();
    }
    @Test void enforcesOwnerAndCancellation() throws Exception {
        String id = service.start("owner", "/app.log", "hit", "UTF-8", false, 0).getScanId();
        assertThrows(IOException.class, () -> service.next("other",id)); assertThrows(IOException.class, () -> service.cancel("other",id));
        assertEquals(1,service.next("owner",id).getRows().size()); service.cancel("owner",id); service.cancel("owner",id);
        assertThrows(IOException.class, () -> service.next("owner",id));
    }
    @Test void rejectsTruncationAndRewrittenBoundary() throws Exception {
        String id = service.start("owner", "/app.log", "hit", "UTF-8", false, 0).getScanId(); service.next("owner",id);
        bytes = "x".getBytes(StandardCharsets.UTF_8);
        final String truncated = id; assertTrue(assertThrows(IOException.class, () -> service.next("owner",truncated)).getMessage().contains("重新"));
        bytes = "first\nhit".getBytes(StandardCharsets.UTF_8);
        id = service.start("owner", "/app.log", "hit", "UTF-8", false, 0).getScanId(); service.next("owner",id);
        bytes = "FIRST\nhit appended".getBytes(StandardCharsets.UTF_8);
        final String rewritten = id; assertThrows(IOException.class, () -> service.next("owner",rewritten));
    }
    @Test void boundsEachReadAndDoesNotClaimEofAtBatchBoundary() throws Exception {
        bytes = new byte[600000]; Arrays.fill(bytes,(byte)'\n');
        String id = service.start("owner", "/app.log", "hit", "UTF-8", true, 0).getScanId(); requestBytes=0;
        SshFileFilterService.Batch batch = service.next("owner",id);
        assertTrue(requestBytes <= 262144); assertEquals(262144,batch.getOffset()); assertFalse(batch.isEof()); assertEquals(600000,batch.getTotalSize());
    }
    @Test void expiresIdleCursorsAndBoundsOpenScans() throws Exception {
        List<String> ids = new ArrayList<>(); for(int i=0;i<32;i++) ids.add(service.start("owner", "/app.log", "hit", "UTF-8", true, 0).getScanId());
        assertThrows(IOException.class, () -> service.start("owner", "/app.log", "hit", "UTF-8", true, 0));
        Map<?,?> scans = (Map<?,?>)ReflectionTestUtils.getField(service,"scans");
        for(Object scan:scans.values()) ReflectionTestUtils.setField(scan,"touched",0L);
        service.cleanupExpired(); assertThrows(IOException.class, () -> service.next("owner",ids.get(0)));
        assertNotNull(service.start("owner", "/app.log", "hit", "UTF-8", true, 0).getScanId());
    }
    @Test void returnsClearRestartMessageWhenFileChanges() throws Exception {
        String id = service.start("owner", "/app.log", "hit", "UTF-8", true, 0).getScanId(); service.next("owner", id);
        bytes = new byte[0];
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.doumiot.sshconsole.controller.SshFileFilterController(service)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ssh-console/sftp/filter/next")
                .contentType("application/json").content("{\"sessionToken\":\"owner\",\"scanId\":\"" + id + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.externMessage", org.hamcrest.Matchers.containsString("重新")));
    }
    @Test void exposesJsonStartNextAndCancelRoutes() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.doumiot.sshconsole.controller.SshFileFilterController(service)).build();
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ssh-console/sftp/filter/start")
                .contentType("application/json").content("{\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"keyword\":\"hit\",\"encoding\":\"UTF-8\",\"caseSensitive\":true,\"contextLines\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("scanId").asText();
        String body = "{\"sessionToken\":\"owner\",\"scanId\":\"" + id + "\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ssh-console/sftp/filter/next").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rows[0].lineNumber").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rows[0].text").value("hit"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.eof").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ssh-console/sftp/filter/cancel").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThrows(IOException.class, () -> service.next("owner",id));
    }
    @Test void rejectsDirectoryBeforeCreatingCursor() throws Exception {
        when(sftp.type(anyString())).thenReturn(FileMode.Type.DIRECTORY);
        assertThrows(IOException.class, () -> service.start("owner", "/dir", "x", "UTF-8", true, 0));
    }
}
