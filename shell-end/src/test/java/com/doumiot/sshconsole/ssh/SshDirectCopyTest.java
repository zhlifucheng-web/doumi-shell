package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.schmizz.concurrent.Event;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.LoggerFactory;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshDirectCopyTest {
    @TempDir Path root;
    static class RemoteCommand {
        final SSHClient client=mock(SSHClient.class);
        final Session session=mock(Session.class);
        final Session.Command command=mock(Session.Command.class);
        final SshSessionRegistry.SessionEntry source=new SshSessionRegistry.SessionEntry();
        final Event<ConnectionException> closed=new Event<>("command-close",ConnectionException.chainer,LoggerFactory.DEFAULT);
        final AtomicInteger waits=new AtomicInteger();
        RemoteCommand(int completeOnWait) throws Exception {
            source.setClient(client);when(client.startSession()).thenReturn(session);
            when(session.exec(anyString())).thenReturn(command);
            when(command.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(command.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(command.isOpen()).thenAnswer(call->!closed.isSet());
            when(command.getExitStatus()).thenAnswer(call->closed.isSet()?0:null);
            // Use SSHJ's real event wait: its timed expiry throws ConnectionException,
            // unlike a no-op mocked join, which would conceal the regression.
            doAnswer(call->{
                if(waits.incrementAndGet()==completeOnWait)closed.set();
                closed.await(call.getArgument(0),call.getArgument(1));return null;
            }).when(command).join(anyLong(),any(TimeUnit.class));
        }
        void check(Runnable tick) throws Exception {SshDirectCopy.check(source,"server",22,"alice",tick);}
    }
    @Test void commandLongerThanPollingIntervalCompletesAndKeepsSendingHeartbeats() throws Exception {
        RemoteCommand remote=new RemoteCommand(3);AtomicInteger ticks=new AtomicInteger();
        remote.check(ticks::incrementAndGet);
        assertTrue(ticks.get()>=3);assertEquals(3,remote.waits.get());
        verify(remote.command,never()).signal(any(Signal.class));verify(remote.session).close();
    }
    @Test void cancellationAfterAPollTimeoutStillTerminatesCommandAndClosesSession() throws Exception {
        RemoteCommand remote=new RemoteCommand(Integer.MAX_VALUE);AtomicInteger ticks=new AtomicInteger();
        assertThrows(CancellationException.class,()->remote.check(()->{
            if(ticks.incrementAndGet()==2)throw new CancellationException("cancelled");
        }));
        assertEquals(2,ticks.get());verify(remote.command).signal(Signal.TERM);verify(remote.session).close();
    }
    @Test void genuineConnectionFailureIsNotTreatedAsAnotherPoll() throws Exception {
        RemoteCommand remote=new RemoteCommand(Integer.MAX_VALUE);
        ConnectionException failure=new ConnectionException("connection lost");
        doThrow(failure).when(remote.command).join(anyLong(),any(TimeUnit.class));
        assertSame(failure,assertThrows(IOException.class,()->remote.check(()->{})));
        verify(remote.command).signal(Signal.TERM);verify(remote.session).close();
    }
    @Test void repeatedPollTimeoutsStillRespectTheOverallDeadline() throws Exception {
        RemoteCommand remote=new RemoteCommand(Integer.MAX_VALUE);
        Method run=SshDirectCopy.class.getDeclaredMethod("run",SshSessionRegistry.SessionEntry.class,String.class,Runnable.class,int.class);
        run.setAccessible(true);
        InvocationTargetException failure=assertThrows(InvocationTargetException.class,
                ()->run.invoke(null,remote.source,"command",(Runnable)()->{},1));
        assertTrue(failure.getCause() instanceof IOException);
        assertEquals("直连复制超时",failure.getCause().getMessage());
        verify(remote.command).signal(Signal.TERM);verify(remote.session).close();
    }
    @Test void nestedShellQuotingCopiesLiteralNamesWithoutExecutingThem() throws Exception {
        Path bin=Files.createDirectory(root.resolve("bin"));
        Path fake=bin.resolve("ssh");
        Files.write(fake,("#!/bin/sh\nfor arg do last=$arg; done\nexec sh -c \"$last\"\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        Path source=root.resolve("source ' $(touch PWNED).txt"), target=root.resolve("target ' $(touch PWNED).txt");
        Files.write(source,"hello direct\n".getBytes(StandardCharsets.UTF_8));
        ProcessBuilder process=new ProcessBuilder("sh","-c",SshDirectCopy.command("server.example",2222,"alice",source.toString(),target.toString()));
        process.directory(root.toFile());process.environment().put("PATH",bin+":/usr/bin:/bin");
        assertEquals(0,process.start().waitFor());
        assertEquals("hello direct\n",new String(Files.readAllBytes(target),StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("PWNED")));
    }
    @Test void rejectsHostAndUserOptionInjection() {
        assertThrows(IOException.class,()->SshDirectCopy.command("-oProxyCommand=touch",22,"alice","/a","/b"));
        assertThrows(IOException.class,()->SshDirectCopy.command("server",22,"alice\nroot","/a","/b"));
        assertThrows(IOException.class,()->SshDirectCopy.command("server",0,"alice","/a","/b"));
    }
    @Test void supportsIpv6Targets() throws Exception {
        assertDoesNotThrow(()->SshDirectCopy.command("::1",22,"alice","/a","/b"));
    }
}
