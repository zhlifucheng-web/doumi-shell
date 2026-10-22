package com.doumiot.sshconsole.ssh;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SshRemoteExecTimeoutTest {
    @Test void unfinishedCommandIsATimeoutInsteadOfASuccessfulMinusOneResult() throws Exception {
        SSHClient client=mock(SSHClient.class); Session session=mock(Session.class); Session.Command cmd=mock(Session.Command.class);
        when(client.isConnected()).thenReturn(true); when(client.startSession()).thenReturn(session);
        when(session.exec("blocked")).thenReturn(cmd);
        when(cmd.getExitStatus()).thenReturn(null);
        when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        assertThrows(SocketTimeoutException.class,()->SshRemoteExec.exec(client,new Object(),"blocked",1));
    }
}
