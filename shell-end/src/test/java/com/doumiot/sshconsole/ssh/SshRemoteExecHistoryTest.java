package com.doumiot.sshconsole.ssh;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshRemoteExecHistoryTest {
    @Test void byteLimitNeverReturnsAPartialLeadingLineOrSplitCharacter() throws Exception {
        SSHClient client = mock(SSHClient.class); Session session = mock(Session.class); Session.Command cmd = mock(Session.Command.class);
        when(client.isConnected()).thenReturn(true); when(client.startSession()).thenReturn(session);
        when(session.exec("capture")).thenReturn(cmd); when(cmd.getExitStatus()).thenReturn(0);
        when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream("prefix中文 line\nlast中文\n".getBytes(StandardCharsets.UTF_8)));
        when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        SshRemoteExec.ExecResult result = SshRemoteExec.execTail(client, new Object(), "capture", 3, 13, StandardCharsets.UTF_8);
        assertEquals("last中文\n", result.getStdout()); assertTrue(result.isTruncated());
    }

    @Test void historyRetainsTheNewestBytesReportsTruncationAndDecodesLegacyText() throws Exception {
        SSHClient client = mock(SSHClient.class);
        Session session = mock(Session.class);
        Session.Command cmd = mock(Session.Command.class);
        when(client.isConnected()).thenReturn(true);
        when(client.startSession()).thenReturn(session);
        when(session.exec("capture")).thenReturn(cmd);
        when(cmd.getExitStatus()).thenReturn(0);
        when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream("old\n中文\n".getBytes("GBK")));
        when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        SshRemoteExec.ExecResult result = SshRemoteExec.execTail(client, new Object(), "capture", 3, 5, Charset.forName("GBK"));
        assertEquals("中文\n", result.getStdout());
        assertTrue(result.isTruncated());
        verify(session).close();
    }

    @Test void exactLimitIsNotMarkedTruncated() throws Exception {
        SSHClient client = mock(SSHClient.class);
        Session session = mock(Session.class);
        Session.Command cmd = mock(Session.Command.class);
        when(client.isConnected()).thenReturn(true); when(client.startSession()).thenReturn(session);
        when(session.exec("capture")).thenReturn(cmd); when(cmd.getExitStatus()).thenReturn(0);
        when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)));
        when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        SshRemoteExec.ExecResult result = SshRemoteExec.execTail(client, new Object(), "capture", 3, 5, StandardCharsets.UTF_8);
        assertEquals("12345", result.getStdout()); assertFalse(result.isTruncated());
    }
}
