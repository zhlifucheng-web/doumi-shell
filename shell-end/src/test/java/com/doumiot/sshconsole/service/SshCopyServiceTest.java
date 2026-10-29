package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshCopyServiceTest {
    SshCopyService service=new SshCopyService();
    List<Runnable> work=new ArrayList<>();
    @BeforeEach void setup() {
        SshSessionRegistry sessions=mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry source=new SshSessionRegistry.SessionEntry();source.setAccountId(1L);
        when(sessions.get("source-token")).thenReturn(source);
        when(sessions.get("other-token")).thenReturn(source);
        ReflectionTestUtils.setField(service,"sessions",sessions);
        ReflectionTestUtils.setField(service,"executor",(Executor)work::add);
    }
    SshCopyService.Request request() {
        SshCopyService.Request request=new SshCopyService.Request();
        request.setSessionToken("source-token");request.setSourcePath("/src");request.setTargetAccountId(2L);
        request.setTargetPath("/dest");request.setMode("RELAY");return request;
    }
    @Test void rejectsInvalidModePathsAndOwnerBeforeScheduling() {
        SshCopyService.Request request=request();request.setMode("unknown");
        assertThrows(Exception.class,()->service.start(request));
        request.setMode("RELAY");request.setTargetPath("relative");
        assertThrows(Exception.class,()->service.start(request));
        request.setTargetPath("/dest/../escape");assertThrows(Exception.class,()->service.start(request));
        request.setTargetPath("/dest");request.setOwner("alice; touch /tmp/bad");
        assertThrows(Exception.class,()->service.start(request));assertTrue(work.isEmpty());
    }
    @Test void statusAndCancellationAreBoundToTheOriginalSession() throws Exception {
        String id=service.start(request()).getId();
        assertEquals("QUEUED",service.status("source-token",id).getStatus());
        assertThrows(Exception.class,()->service.status("other-token",id));
        assertThrows(Exception.class,()->service.cancel("other-token",id));
        assertEquals("QUEUED",service.status("source-token",id).getStatus());
    }
    @Test void queuedCancellationDoesNotOpenAnySshConnection() throws Exception {
        SshAccountService accounts=mock(SshAccountService.class);ReflectionTestUtils.setField(service,"accounts",accounts);
        String id=service.start(request()).getId();service.cancel("source-token",id);work.get(0).run();
        assertEquals("CANCELLED",service.status("source-token",id).getStatus());verifyNoInteractions(accounts);
    }
    @Test void boundsQueuedWorkInsteadOfOpeningUnboundedConnections() throws Exception {
        for(int i=0;i<32;i++)service.start(request());
        assertThrows(Exception.class,()->service.start(request()));assertEquals(32,work.size());
    }
}
