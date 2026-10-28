package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import com.doumiot.sshconsole.sftp.SshFileFilterScanner;

class SshFileFilterNativeFactoryTest {
    @Test void unsupportedSemanticsDoNotAttemptRemoteExec() throws Exception {
        SshSessionRegistry registry=mock(SshSessionRegistry.class);SshFileFilterRemoteFactory factory=new SshFileFilterRemoteFactory(registry);
        try {
            for(String encoding:new String[]{"GBK","UTF-16LE"})assertNull(factory.openNative("owner","/log","hit",encoding,true,0,null,new SshFileFilterScanner("hit",encoding,true,0)).cursor);
            assertNull(factory.openNative("owner","/log","hit","UTF-8",false,0,null,new SshFileFilterScanner("hit","UTF-8",false,0)).cursor);
            verifyNoInteractions(registry);
        }finally{factory.closeAll();}
    }
    @Test void deniedExecAndMissingGrepExplicitlyFallBackWithoutPython() throws Exception {
        SshSessionRegistry registry=mock(SshSessionRegistry.class);SSHClient client=mock(SSHClient.class);Session session=mock(Session.class);Session.Command process=mock(Session.Command.class);
        SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();entry.setClient(client);when(registry.get("owner")).thenReturn(entry);when(client.startSession()).thenReturn(session);
        SshFileFilterRemoteFactory factory=new SshFileFilterRemoteFactory(registry);
        byte[] bytes="hit\n".getBytes(StandardCharsets.UTF_8);NativeFilterSnapshot snapshot=NativeFilterSnapshot.create(bytes.length,1,bytes,bytes,bytes);
        try {
            when(session.exec(anyString())).thenThrow(new net.schmizz.sshj.connection.ConnectionException("denied"));
            assertNull(factory.openNative("owner","/log","hit","UTF-8",true,0,snapshot,new SshFileFilterScanner("hit","UTF-8",true,0)).cursor);
            doReturn(process).when(session).exec(anyString());when(process.getInputStream()).thenReturn(new ByteArrayInputStream("UNSUPPORTED\tmissing-grep\n".getBytes(StandardCharsets.US_ASCII)));when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
            SshFileFilterRemoteFactory.Attempt attempt=factory.openNative("owner","/log","hit","UTF-8",true,0,snapshot,new SshFileFilterScanner("hit","UTF-8",true,0));
            assertNull(attempt.cursor);assertTrue(attempt.reason.contains("missing-grep"));
        }finally{factory.closeAll();}
    }
    @Test void emptyCheckReplyWithRemoteExitThreeReportsFileChanged() throws Exception {
        Fixture f=new Fixture();SshFileFilterRemoteFactory.Attempt attempt=f.open();
        try {
            when(f.controlProcess.getInputStream()).thenAnswer(call->new ByteArrayInputStream(new byte[0]));when(f.controlProcess.isEOF()).thenReturn(true);when(f.controlProcess.getExitStatus()).thenReturn(3);
            com.doumiot.sshconsole.sftp.FileTailRecoveryException changed=assertThrows(com.doumiot.sshconsole.sftp.FileTailRecoveryException.class,attempt.cursor::verifySnapshot);
            assertEquals("FILE_CHANGED",changed.getCode());
        }finally{f.factory.closeAll();}
    }
    @Test void checkTransportFailurePreservesCauseAndUsesRecoveryInsteadOfFileChanged() throws Exception {
        Fixture f=new Fixture();SshFileFilterRemoteFactory.Attempt attempt=f.open();IOException wire=new IOException("wire broken");
        try {
            when(f.client.isConnected()).thenReturn(true);
            when(f.controlProcess.getInputStream()).thenReturn(new InputStream(){public int available() throws IOException{throw wire;}public int read(){return -1;}});
            com.doumiot.sshconsole.sftp.FileTailRecoveryException failure=assertThrows(com.doumiot.sshconsole.sftp.FileTailRecoveryException.class,attempt.cursor::verifySnapshot);
            assertEquals("TAIL_EXPIRED",failure.getCode());assertSame(wire,failure.getCause());
        }finally{f.factory.closeAll();}
    }
    @Test void emptySuccessfulCheckReplyIsAProtocolFailureAndNeverFileChanged() throws Exception {
        Fixture f=new Fixture();SshFileFilterRemoteFactory.Attempt attempt=f.open();
        try {
            when(f.client.isConnected()).thenReturn(true);when(f.controlProcess.getInputStream()).thenAnswer(call->new ByteArrayInputStream(new byte[0]));when(f.controlProcess.isEOF()).thenReturn(true);
            com.doumiot.sshconsole.sftp.FileTailRecoveryException failure=assertThrows(com.doumiot.sshconsole.sftp.FileTailRecoveryException.class,attempt.cursor::verifySnapshot);
            assertEquals("TAIL_EXPIRED",failure.getCode());assertNotNull(failure.getCause());
        }finally{f.factory.closeAll();}
    }
    @Test void closingControllersKeepTheirCapacityUntilQueuedCleanupCompletes() throws Exception {
        Fixture f=new Fixture();java.util.List<Runnable> queued=new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService executor=mock(java.util.concurrent.ExecutorService.class);
        doAnswer(call->{queued.add(call.getArgument(0));return null;}).when(executor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(f.factory,"controls",executor);
        try {
            for(int n=0;n<32;n++){SshFileFilterRemoteFactory.Attempt attempt=f.open();assertNotNull(attempt.cursor);attempt.cursor.close();}
            assertEquals(32,queued.size());assertNull(f.open().cursor);
            for(Runnable task:queued)task.run();
            assertEquals(32,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(f.factory,"nativeSlots")).availablePermits());
        }finally{f.factory.closeAll();}
    }
    @Test void shutdownReclaimsControllersWhoseQueuedDrainsWereDiscarded() throws Exception {
        Fixture f=new Fixture();java.util.List<Runnable> queued=new java.util.ArrayList<>();java.util.concurrent.ExecutorService executor=mock(java.util.concurrent.ExecutorService.class);
        doAnswer(call->{queued.add(call.getArgument(0));return null;}).when(executor).execute(any(Runnable.class));when(executor.shutdownNow()).thenAnswer(call->{java.util.List<Runnable> removed=new java.util.ArrayList<>(queued);queued.clear();return removed;});
        ReflectionTestUtils.setField(f.factory,"controls",executor);
        SshFileFilterRemoteFactory.Attempt attempt=f.open();attempt.cursor.pause(true);f.factory.closeAll();attempt.cursor.awaitCleanup();
        verify(f.nativeProcess).close();assertEquals(32,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(f.factory,"nativeSlots")).availablePermits());
    }
    @Test void shutdownExecutesEveryQueuedEmergencyCleanupAfterItsWaitExpires() throws Exception {
        Fixture f=new Fixture();java.util.List<Runnable> primaryQueue=new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService primary=mock(java.util.concurrent.ExecutorService.class);
        doAnswer(call->{primaryQueue.add(call.getArgument(0));return null;}).when(primary).execute(any(Runnable.class));
        when(primary.shutdownNow()).thenAnswer(call->{java.util.List<Runnable> dropped=new java.util.ArrayList<>(primaryQueue);primaryQueue.clear();return dropped;});
        java.util.concurrent.ThreadPoolExecutor emergency=mock(java.util.concurrent.ThreadPoolExecutor.class);
        java.util.concurrent.BlockingQueue<Runnable> cleanupQueue=new java.util.concurrent.LinkedBlockingQueue<>();
        when(emergency.getQueue()).thenReturn(cleanupQueue);
        doAnswer(call->{cleanupQueue.add(call.getArgument(0));return null;}).when(emergency).execute(any(Runnable.class));
        when(emergency.shutdownNow()).thenAnswer(call->{java.util.List<Runnable> dropped=new java.util.ArrayList<>();cleanupQueue.drainTo(dropped);return dropped;});
        ReflectionTestUtils.setField(f.factory,"controls",primary);ReflectionTestUtils.setField(f.factory,"cleanupWorkers",emergency);
        f.open().cursor.pause(true);f.open().cursor.pause(true);
        f.factory.closeAll();
        verify(f.nativeProcess,times(2)).close();assertTrue(cleanupQueue.isEmpty());
        assertEquals(32,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(f.factory,"nativeSlots")).availablePermits());
        verify(emergency,never()).shutdownNow();
    }
    @Test void interruptedShutdownDrainsCleanupBeforeRestoringTheCallerInterrupt() throws Exception {
        Fixture f=new Fixture();java.util.concurrent.ExecutorService primary=mock(java.util.concurrent.ExecutorService.class);
        java.util.concurrent.ThreadPoolExecutor emergency=mock(java.util.concurrent.ThreadPoolExecutor.class);
        java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();when(emergency.getQueue()).thenReturn(queue);
        java.util.concurrent.atomic.AtomicBoolean ran=new java.util.concurrent.atomic.AtomicBoolean();
        queue.add(()->{assertFalse(Thread.currentThread().isInterrupted(),"CLOSE channel waits must remain usable during final drain");ran.set(true);});
        ReflectionTestUtils.setField(f.factory,"controls",primary);ReflectionTestUtils.setField(f.factory,"cleanupWorkers",emergency);
        Thread.currentThread().interrupt();
        try{f.factory.closeAll();assertTrue(ran.get());assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
    }
    @Test void rejectedCloseUsesReservedCleanupWorkerAndNeverDropsTheOwnedGroup() throws Exception {
        Fixture f=new Fixture();java.util.concurrent.ExecutorService rejected=mock(java.util.concurrent.ExecutorService.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("test")).when(rejected).execute(any(Runnable.class));
        ReflectionTestUtils.setField(f.factory,"controls",rejected);
        try {
            SshFileFilterRemoteFactory.Attempt attempt=f.open();attempt.cursor.close();attempt.cursor.awaitCleanup();
            verify(f.nativeProcess).close();assertEquals(32,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(f.factory,"nativeSlots")).availablePermits());
        }finally{f.factory.closeAll();}
    }
    private static final class Fixture {
        final SshFileFilterRemoteFactory factory;final Session.Command nativeProcess=mock(Session.Command.class),controlProcess=mock(Session.Command.class);final SSHClient client=mock(SSHClient.class);
        Fixture() throws Exception {
            SshSessionRegistry registry=mock(SshSessionRegistry.class);Session nativeSession=mock(Session.class),controlSession=mock(Session.class);
            SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();entry.setClient(client);when(registry.get("owner")).thenReturn(entry);
            when(client.startSession()).thenAnswer(new org.mockito.stubbing.Answer<Session>(){int n;public Session answer(org.mockito.invocation.InvocationOnMock call){return n++%2==0?nativeSession:controlSession;}});
            // Native/probe pairs are used during startup; cleanup uses a control command as well.
            when(nativeSession.exec(anyString())).thenAnswer(call->((String)call.getArgument(0)).contains("Separate exec channel")?controlProcess:nativeProcess);
            when(controlSession.exec(anyString())).thenReturn(controlProcess);
            when(nativeProcess.getInputStream()).thenAnswer(call->new ByteArrayInputStream("READY\t123\t456\t124\n".getBytes(StandardCharsets.US_ASCII)));when(nativeProcess.getOutputStream()).thenReturn(new ByteArrayOutputStream());when(nativeProcess.isOpen()).thenReturn(true);
            when(controlProcess.getInputStream()).thenAnswer(call->new ByteArrayInputStream("OK\n".getBytes(StandardCharsets.US_ASCII)));when(controlProcess.getOutputStream()).thenAnswer(call->new ByteArrayOutputStream());when(controlProcess.getExitStatus()).thenReturn(0);
            factory=new SshFileFilterRemoteFactory(registry);
        }
        SshFileFilterRemoteFactory.Attempt open() throws Exception {byte[] bytes="hit\n".getBytes(StandardCharsets.UTF_8);return factory.openNative("owner","/log","hit","UTF-8",true,0,NativeFilterSnapshot.create(4,1,bytes,bytes,bytes),new SshFileFilterScanner("hit","UTF-8",true,0));}
    }
    @Test void commandUsesOnlyBundledNativeCodeAndNoUserParameters() throws Exception {
        SshFileFilterRemoteFactory factory=new SshFileFilterRemoteFactory(mock(SshSessionRegistry.class));
        try {String command=(String)ReflectionTestUtils.getField(factory,"command");assertFalse(command.contains("python"));assertTrue(command.contains("setsid"));}
        finally {factory.closeAll();}
    }
}
