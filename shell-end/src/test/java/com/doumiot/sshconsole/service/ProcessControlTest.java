package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessControlTest {
    @Test void rejectsInitProcessAndUnexpectedSignalsBeforeSsh() {
        SshProcessService service=new SshProcessService();
        assertThrows(RuntimeException.class,()->service.killProcess("t",1,"TERM"));
        assertThrows(RuntimeException.class,()->service.killProcess("t",123,"TERM; touch /tmp/oops"));
    }
    @Test void normalAndForcedStopUseBoundedNumericSignalsAndSurfacePermissionFailure() throws Exception {
        SshProcessService service=new SshProcessService();SshSessionRegistry registry=mock(SshSessionRegistry.class);
        SSHClient client=mock(SSHClient.class);Session channel=mock(Session.class);Session.Command cmd=mock(Session.Command.class);
        SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();entry.setClient(client);
        when(registry.get("t")).thenReturn(entry);when(client.isConnected()).thenReturn(true);when(client.startSession()).thenReturn(channel);when(channel.exec(anyString())).thenReturn(cmd);
        when(cmd.getInputStream()).thenAnswer(call->new ByteArrayInputStream("Permission denied".getBytes()));
        when(cmd.getErrorStream()).thenAnswer(call->new ByteArrayInputStream(new byte[0]));when(cmd.getExitStatus()).thenReturn(1);
        ReflectionTestUtils.setField(service,"sessionRegistry",registry);
        assertFalse(service.killProcess("t",123,"TERM").isSuccess());assertTrue(service.killProcess("t",123,"KILL").getMessage().contains("Permission denied"));
        verify(channel).exec("kill -15 123 2>&1");verify(channel).exec("kill -9 123 2>&1");
    }
}
