package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SshFileFilterRemoteCursorTest {
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    private boolean closed;
    private SshFileFilterRemoteCursor cursor(String frames, Clock clock) {
        return new SshFileFilterRemoteCursor(new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)), sent, () -> closed=true, clock);
    }
    @Test void sendsStructuredConfigurationAndOnlyReadsOnDemand() throws Exception {
        SshFileFilterRemoteCursor c=cursor("{\"type\":\"ready\",\"totalSize\":1048576}\n{\"type\":\"batch\",\"rows\":[{\"lineNumber\":3,\"text\":\"hit\",\"matched\":true}],\"offset\":262144,\"totalSize\":1048576,\"eof\":false,\"limited\":false}\n",Clock.systemUTC());
        Map<String,Object> config=new LinkedHashMap<>();config.put("path","/a'$(touch sentinel)\n.log");config.put("keyword","hit");
        assertTrue(c.initialize(config)); assertFalse(sent.toString("UTF-8").contains("\nnext\n"));
        assertEquals("/a'$(touch sentinel)\n.log",new com.fasterxml.jackson.databind.ObjectMapper().readTree(sent.toString("UTF-8")).path("path").asText());
        assertEquals("hit",c.next("scan").getRows().get(0).getText()); assertTrue(sent.toString("UTF-8").endsWith("\nnext\n"));
        c.close();c.close();assertTrue(closed);assertThrows(IOException.class,()->c.next("scan"));
    }
    @Test void propagatesRotationCodeAndClosesChannel() throws Exception {
        SshFileFilterRemoteCursor c=cursor("{\"type\":\"ready\",\"totalSize\":8}\n{\"type\":\"error\",\"code\":\"FILE_CHANGED\",\"message\":\"文件已改变\"}\n",Clock.systemUTC());
        c.initialize(Collections.emptyMap());
        com.doumiot.sshconsole.sftp.FileTailRecoveryException failure=assertThrows(com.doumiot.sshconsole.sftp.FileTailRecoveryException.class,()->c.next("scan"));
        assertEquals("FILE_CHANGED",failure.getCode()); assertTrue(closed);
    }
    @Test void rejectsOversizedOrOutOfOrderRemoteData() throws Exception {
        String ready="{\"type\":\"ready\",\"totalSize\":8}\n";
        SshFileFilterRemoteCursor c=cursor(ready+"{\"type\":\"batch\",\"rows\":[],\"offset\":9,\"totalSize\":8,\"eof\":true,\"limited\":false}\n",Clock.systemUTC());
        c.initialize(Collections.emptyMap());final SshFileFilterRemoteCursor invalid=c;assertThrows(IOException.class,()->invalid.next("scan"));assertTrue(closed);
        closed=false;char[] oversized=new char[4*1024*1024+1];Arrays.fill(oversized,'x');
        c=cursor(new String(oversized),Clock.systemUTC()); final SshFileFilterRemoteCursor huge=c;
        assertThrows(IOException.class,()->huge.initialize(Collections.emptyMap()));assertTrue(closed);
    }
    @Test void unexpectedChannelEofRequestsRecoveryRatherThanLeavingViewerStopped() throws Exception {
        SshFileFilterRemoteCursor c=cursor("{\"type\":\"ready\",\"totalSize\":8}\n",Clock.systemUTC());c.initialize(Collections.emptyMap());
        com.doumiot.sshconsole.sftp.FileTailRecoveryException error=assertThrows(com.doumiot.sshconsole.sftp.FileTailRecoveryException.class,()->c.next("scan"));
        assertEquals("TAIL_EXPIRED",error.getCode());assertTrue(closed);
    }
    @Test void missingPythonIsExplicitCompatibilityFallback() throws Exception {
        SshFileFilterRemoteCursor c=cursor("{\"type\":\"unsupported\"}\n",Clock.systemUTC());
        assertFalse(c.initialize(Collections.emptyMap()));assertTrue(closed);
    }
    @Test void deadlineClosesOnlyBlockedReadAndNeverIdleCursor() throws Exception {
        SshFileFilterRemoteCursor c=cursor("{\"type\":\"ready\",\"totalSize\":8}\n",Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        c.initialize(Collections.emptyMap()); c.expire(999999); assertFalse(closed);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);
        InputStream blocked=new InputStream(){public int read() throws IOException {entered.countDown();try{released.await();}catch(InterruptedException e){throw new IOException(e);}throw new IOException("closed");}};
        SshFileFilterRemoteCursor waiting=new SshFileFilterRemoteCursor(blocked,sent,()->{closed=true;released.countDown();},Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> result=worker.submit(()->assertThrows(IOException.class,()->waiting.initialize(Collections.emptyMap())));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));waiting.expire(10001);result.get(2,java.util.concurrent.TimeUnit.SECONDS);assertTrue(closed);
        } finally {released.countDown();worker.shutdownNow();}
    }
}
