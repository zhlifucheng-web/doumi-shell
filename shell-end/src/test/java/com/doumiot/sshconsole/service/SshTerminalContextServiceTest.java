package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.*;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshTerminalContextServiceTest {
    @Test void cachesPerEntryWithControllableClockAndNeverTouchesInteractiveInput() throws Exception {
        SshTerminalContextService service=new SshTerminalContextService();
        SshSessionRegistry registry=mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();entry.setTerminalAttached(true);
        SSHClient client=mock(SSHClient.class);entry.setClient(client);when(client.isConnected()).thenReturn(true);
        Session session=mock(Session.class);Session.Command cmd=mock(Session.Command.class);
        when(client.startSession()).thenReturn(session);when(session.exec(anyString())).thenReturn(cmd);
        String output="{\"available\":false,\"status\":\"unavailable\",\"message\":\"not ready\"}";
        when(cmd.getInputStream()).thenAnswer(i->new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(cmd.getErrorStream()).thenAnswer(i->new ByteArrayInputStream(new byte[0]));when(cmd.getExitStatus()).thenReturn(0);
        when(registry.get("token")).thenReturn(entry);
        AtomicLong clock=new AtomicLong(100);ReflectionTestUtils.setField(service,"clock",(LongSupplier)clock::get);
        ReflectionTestUtils.setField(service,"sessions",registry);
        assertFalse(service.snapshot("token").isAvailable());
        service.snapshot("token");verify(client,times(1)).startSession();
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(501));service.snapshot("token");verify(client,times(2)).startSession();
        assertNull(entry.getShellStdin());verify(session,never()).startShell();
    }
    @Test void independentSftpSessionNeverProbesAnUnrelatedShell() {
        SshTerminalContextService service=new SshTerminalContextService();SshSessionRegistry registry=mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();when(registry.get("files")).thenReturn(entry);
        ReflectionTestUtils.setField(service,"sessions",registry);
        assertFalse(service.snapshot("files").isAvailable());assertNull(entry.getTerminalContext());
        when(registry.get("expired")).thenThrow(new IllegalStateException("expired"));
        assertThrows(IllegalStateException.class,()->service.snapshot("expired"));
    }
}
