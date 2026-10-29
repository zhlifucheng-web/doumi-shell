package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshCopyTargetTest {
    SshCopyService service=new SshCopyService();
    SshSessionRegistry sessions=mock(SshSessionRegistry.class);
    SshAccountService accounts=mock(SshAccountService.class);
    SshSftpService sftp=mock(SshSftpService.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"sessions",sessions);
        ReflectionTestUtils.setField(service,"accounts",accounts);
        ReflectionTestUtils.setField(service,"sftp",sftp);
        SshAccountService.SshSessionResponse target=new SshAccountService.SshSessionResponse();
        target.setSessionToken("target-only");when(accounts.openSession(2L)).thenReturn(target);
    }
    @Test void readsUsersFromReceiverAndClosesOnlyTemporarySession() throws Exception {
        SshSftpService.SystemUsersAndGroups users=new SshSftpService.SystemUsersAndGroups();
        users.setUsers(Arrays.asList("root","alice"));when(sftp.systemUsersAndGroups("target-only")).thenReturn(users);
        assertEquals(Arrays.asList("root","alice"),service.targetUsers("source",2L).getUsers());
        verify(sessions).get("source");verify(sessions).close("target-only");verify(sessions,never()).close("source");
    }
    @Test void directoryFailuresStillCloseTemporaryReceiverSession() throws Exception {
        when(sftp.directories("target-only","/missing")).thenThrow(new IOException("not found"));
        assertThrows(IOException.class,()->service.targetDirectories("source",2L,"/missing"));
        verify(sessions).close("target-only");
    }
    @Test void invalidSourceCannotOpenReceiverConnection() {
        when(sessions.get("expired")).thenThrow(new IllegalArgumentException("expired"));
        assertThrows(Exception.class,()->service.targetUsers("expired",2L));verifyNoInteractions(accounts);
    }
}
