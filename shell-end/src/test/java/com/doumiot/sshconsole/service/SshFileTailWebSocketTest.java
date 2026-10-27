package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.doumiot.sshconsole.ws.SshFileTailWebSocketHandler;
import com.fasterxml.jackson.databind.*;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshFileTailWebSocketTest {
    private final SshSftpService files = new SshSftpService();
    private final SshFileFilterService filters = new SshFileFilterService();
    private final SshSessionRegistry registry = mock(SshSessionRegistry.class);
    private final SSHClient ssh = mock(SSHClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final List<JsonNode> frames = new ArrayList<>();
    private SshFileTailWebSocketHandler handler;
    private WebSocketSession socket;
    private byte[] bytes = "a\n".getBytes(StandardCharsets.UTF_8);
    private long mtime = 1;
    private int opened, closed, reads;
    private boolean valid = true;
    private byte[] appendOnStat;
    private final java.util.concurrent.atomic.AtomicBoolean blockOnOpen = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean blockOnRead = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
    private java.util.concurrent.CountDownLatch observedData, observedReady;

    @BeforeEach void setup() throws Exception {
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry(); entry.setClient(ssh); entry.setHomeDir("/home/u");
        when(registry.get(anyString())).thenAnswer(call -> { if(!valid) throw new IllegalArgumentException("SSH 会话已失效"); return entry; });
        when(ssh.isConnected()).thenAnswer(call -> valid);
        when(ssh.newSFTPClient()).thenAnswer(call -> {
            if(blockOnOpen.compareAndSet(true,false)){blocked.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);}
            opened++;
            SFTPClient sftp = mock(SFTPClient.class); RemoteFile remote = mock(RemoteFile.class);
            when(sftp.type(anyString())).thenReturn(FileMode.Type.REGULAR); when(sftp.open(anyString())).thenReturn(remote);
            when(sftp.stat(anyString())).thenAnswer(c -> {
                FileAttributes attrs=new FileAttributes.Builder().withSize(bytes.length).withAtimeMtime(mtime,mtime).build();
                if(appendOnStat!=null) {bytes=appendOnStat;appendOnStat=null;mtime++;}
                return attrs;
            });
            when(remote.length()).thenAnswer(c -> (long)bytes.length);
            when(remote.read(anyLong(),any(byte[].class),anyInt(),anyInt())).thenAnswer(c -> {
                if(blockOnRead.compareAndSet(true,false)) {blocked.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);}
                reads++; long offset=c.getArgument(0); byte[] to=c.getArgument(1); int begin=c.getArgument(2), length=c.getArgument(3);
                if(offset>=bytes.length)return -1; int n=Math.min(length,bytes.length-(int)offset); System.arraycopy(bytes,(int)offset,to,begin,n); return n;
            });
            doAnswer(c -> {closed++; return null;}).when(sftp).close();
            return sftp;
        });
        ReflectionTestUtils.setField(files,"sessionRegistry",registry); ReflectionTestUtils.setField(filters,"files",files);
        handler = new SshFileTailWebSocketHandler(files,filters,registry);
        ReflectionTestUtils.setField(handler,"clock",clock);
        ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)Runnable::run);
        socket = socket("first",frames);
    }
    @AfterEach void cleanup() {handler.closeAll();}
    @Test void reusesOneChannelAndWaitsForAckBeforeReadingAgain() throws Exception {
        start(2,null); assertEquals("ready",frames.get(0).path("type").asText());
        bytes="a\nhello\n".getBytes(StandardCharsets.UTF_8); mtime++;
        check(); JsonNode first=last(); assertEquals("hello\n",first.path("content").asText()); assertEquals(8,first.path("offset").asLong());
        int before=reads; check(); assertEquals(before,reads); assertEquals(2,frames.size());
        send(socket,"{\"type\":\"ack\",\"sequence\":1}"); bytes="a\nhello\nagain\n".getBytes(StandardCharsets.UTF_8); mtime++;
        check(); assertEquals("again\n",last().path("content").asText()); assertEquals(1,opened); assertEquals(0,closed);
        send(socket,"{\"type\":\"close\"}"); assertEquals(1,closed); verify(registry,never()).close(anyString()); verify(registry,never()).bindWebSocket(anyString(),any());
    }
    @Test void closesLongPausedHandlesAndReopensAtSavedOffset() throws Exception {
        start(2,null); send(socket,"{\"type\":\"pause\"}"); clock.millis+=30001; check(); assertEquals(1,closed);
        bytes="a\nnew\n".getBytes(StandardCharsets.UTF_8); mtime++;
        send(socket,"{\"type\":\"resume\"}"); check(); assertEquals("new\n",last().path("content").asText()); assertEquals(2,opened);
    }
    @Test void replaysOnlyUnacknowledgedBatchAfterReconnect() throws Exception {
        start(2,null); String id=last().path("tailId").asText(); bytes="a\nnext\n".getBytes(StandardCharsets.UTF_8); mtime++; check(); JsonNode pending=last();
        handler.afterConnectionClosed(socket,CloseStatus.NORMAL); assertEquals(1,closed);
        List<JsonNode> resumed=new ArrayList<>(); WebSocketSession replacement=socket("second",resumed);
        send(replacement,"{\"type\":\"resume\",\"sessionToken\":\"owner\",\"tailId\":\""+id+"\",\"sequence\":0}");
        assertEquals("ready",resumed.get(0).path("type").asText()); assertEquals(pending,resumed.get(1));
        int before=reads; check(); assertEquals(before,reads);
        send(replacement,"{\"type\":\"ack\",\"sequence\":1}"); bytes="a\nnext\nmore\n".getBytes(StandardCharsets.UTF_8); mtime++; check();
        assertEquals(2,resumed.get(2).path("sequence").asLong()); assertEquals("more\n",resumed.get(2).path("content").asText()); assertEquals(2,opened);
    }
    @Test void preservesUtf8DecoderAcrossBatchesAndSeedsInitialPartialCharacter() throws Exception {
        bytes=new byte[0]; start(0,null); byte[] character="中".getBytes(StandardCharsets.UTF_8); bytes=Arrays.copyOf(character,2); mtime++; check();
        assertEquals("",last().path("content").asText()); send(socket,"{\"type\":\"ack\",\"sequence\":1}");
        bytes="中\n".getBytes(StandardCharsets.UTF_8); mtime++; check(); assertEquals("中\n",last().path("content").asText());
        send(socket,"{\"type\":\"close\"}"); frames.clear(); bytes=Arrays.copyOf(character,2); start(2,null);
        bytes="中\n".getBytes(StandardCharsets.UTF_8); mtime++; check(); assertEquals("中\n",last().path("content").asText());
    }
    @Test void closingUnacknowledgedFilteredBatchInvalidatesAdvancedCursor() throws Exception {
        bytes="hit".getBytes(StandardCharsets.UTF_8);String scan=filters.start("owner","/app.log","hit","UTF-8",true,0).getScanId();filters.next("owner",scan);
        start(3,scan);bytes="hit more\n".getBytes(StandardCharsets.UTF_8);mtime++;check();
        assertEquals("hit more",last().path("rows").get(0).path("text").asText());send(socket,"{\"type\":\"close\"}");
        assertThrows(IOException.class,()->filters.claimTail("owner",scan));assertThrows(IOException.class,()->filters.next("owner",scan));
    }
    @Test void truncationIsTerminalAndExplicitCloseReleasesFilterClaim() throws Exception {
        bytes="hit".getBytes(StandardCharsets.UTF_8); String scan=filters.start("owner","/app.log","hit","UTF-8",true,0).getScanId(); filters.next("owner",scan);
        int before=opened; start(3,scan); assertThrows(IOException.class,()->filters.next("owner",scan));
        bytes="hit again\n".getBytes(StandardCharsets.UTF_8); mtime++; check();
        assertEquals("hit again",last().path("rows").get(0).path("text").asText()); assertEquals(1,last().path("rows").get(0).path("lineNumber").asLong()); assertEquals(before+1,opened);
        send(socket,"{\"type\":\"ack\",\"sequence\":1}"); send(socket,"{\"type\":\"close\"}"); assertTrue(filters.next("owner",scan).isEof());
        frames.clear(); start(bytes.length,null); bytes=new byte[0]; mtime++; check(); assertEquals("error",last().path("type").asText()); assertTrue(last().path("message").asText().contains("重新"));
    }
    @Test void expiresDisconnectedStateAndValidatesSshEvenWhilePaused() throws Exception {
        start(2,null); String id=last().path("tailId").asText(); handler.afterConnectionClosed(socket,CloseStatus.NORMAL); clock.millis+=60001; check();
        List<JsonNode> resumed=new ArrayList<>(); send(socket("second",resumed),"{\"type\":\"resume\",\"sessionToken\":\"owner\",\"tailId\":\""+id+"\",\"sequence\":0}"); assertEquals("error",resumed.get(0).path("type").asText());
        frames.clear(); start(2,null); send(socket,"{\"type\":\"pause\"}"); valid=false; check(); assertEquals("error",last().path("type").asText()); assertEquals(opened,closed);
    }
    @Test void quietAndPausedStreamsSendHeartbeatWithoutReopeningSftp() throws Exception {
        start(2,null);clock.millis+=15001;check();assertEquals("heartbeat",last().path("type").asText());assertEquals(1,opened);
        send(socket,"{\"type\":\"pause\"}");clock.millis+=15001;check();assertEquals("heartbeat",last().path("type").asText());assertEquals(0,closed);
    }
    @Test void blockedTailDoesNotBlockAnotherTailOrPauseCommand() throws Exception {
        handler.closeAll();handler=new SshFileTailWebSocketHandler(files,filters,registry);
        ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)Runnable::run);
        observedReady=new java.util.concurrent.CountDownLatch(2);
        start(2,null); List<JsonNode> second=new ArrayList<>();WebSocketSession other=socket("other",second);
        send(other,"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"offset\":2}");
        assertTrue(observedReady.await(2,java.util.concurrent.TimeUnit.SECONDS));
        ReflectionTestUtils.setField(handler,"executor",ReflectionTestUtils.getField(handler,"workers"));
        bytes="a\nmore\n".getBytes(StandardCharsets.UTF_8);mtime++;blockOnRead.set(true);observedData=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService launch=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            launch.submit(()->check());assertTrue(blocked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(observedData.await(2,java.util.concurrent.TimeUnit.SECONDS),"other tail must progress while one SFTP call is blocked");
            launch.submit(()->{try{send(socket,"{\"type\":\"pause\"}");send(other,"{\"type\":\"pause\"}");}catch(Exception e){throw new RuntimeException(e);}}).get(1,java.util.concurrent.TimeUnit.SECONDS);
        } finally {release.countDown();launch.shutdownNow();handler.closeAll();}
    }
    @Test void sendFailureRetainsPendingForReconnect() throws Exception {
        start(2,null); String id=last().path("tailId").asText();
        doThrow(new IOException("network disconnected")).when(socket).sendMessage(any());
        bytes="a\nretry\n".getBytes(StandardCharsets.UTF_8); mtime++; check(); assertEquals(1,closed);
        List<JsonNode> resumed=new ArrayList<>(); WebSocketSession replacement=socket("replacement",resumed);
        send(replacement,"{\"type\":\"resume\",\"sessionToken\":\"owner\",\"tailId\":\""+id+"\",\"sequence\":0}");
        assertEquals("ready",resumed.get(0).path("type").asText()); assertEquals("retry\n",resumed.get(1).path("content").asText());
    }
    @Test void pauseCapturedByOldSocketCannotPauseReplacementConnection() throws Exception {
        start(2,null);String id=last().path("tailId").asText();
        @SuppressWarnings("unchecked") Map<String,Object> original=(Map<String,Object>)ReflectionTestUtils.getField(handler,"sockets");
        java.util.concurrent.CountDownLatch captured=new java.util.concurrent.CountDownLatch(1), proceed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> oldThread=new java.util.concurrent.atomic.AtomicReference<>();
        Map<String,Object> delayed=new java.util.concurrent.ConcurrentHashMap<String,Object>(original) {
            @Override public Object get(Object key) {Object value=super.get(key);if(Thread.currentThread()==oldThread.get()){captured.countDown();try{proceed.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return value;}
        };
        ReflectionTestUtils.setField(handler,"sockets",delayed);
        java.util.concurrent.ExecutorService sender=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> pause=sender.submit(()->{oldThread.set(Thread.currentThread());try{send(socket,"{\"type\":\"pause\"}");}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(captured.await(2,java.util.concurrent.TimeUnit.SECONDS));List<JsonNode> replacementFrames=new ArrayList<>();WebSocketSession replacement=socket("replacement",replacementFrames);
            send(replacement,"{\"type\":\"resume\",\"sessionToken\":\"owner\",\"tailId\":\""+id+"\",\"sequence\":0}");
            proceed.countDown();pause.get(2,java.util.concurrent.TimeUnit.SECONDS);bytes="a\nstill active\n".getBytes(StandardCharsets.UTF_8);mtime++;clock.millis+=1000;check();
            assertEquals("still active\n",replacementFrames.get(replacementFrames.size()-1).path("content").asText());
        } finally {proceed.countDown();sender.shutdownNow();}
    }
    @Test void filterStartUsesConsistentSnapshotDuringAppend() throws Exception {
        appendOnStat="a\nhit\n".getBytes(StandardCharsets.UTF_8);
        String id=filters.start("owner","/app.log","hit","UTF-8",true,0).getScanId();
        SshFileFilterService.Batch result=filters.next("owner",id);
        assertEquals(2,result.getRows().get(0).getLineNumber());assertEquals("hit",result.getRows().get(0).getText());assertTrue(result.isEof());
    }
    @Test void toleratesAppendBetweenMetadataSnapshots() throws Exception {
        start(2,null); appendOnStat="a\nracing append\n".getBytes(StandardCharsets.UTF_8);
        check(); assertEquals("data",last().path("type").asText()); assertEquals("racing append\n",last().path("content").asText());
    }
    @Test void seedsSupportedMultibyteEncodingsAtHandoff() throws Exception {
        String[][] examples={{"GBK","中"},{"GB18030","中"},{"Big5","中"},{"Shift_JIS","あ"},{"EUC-KR","한"},{"UTF-16LE","😀"},{"UTF-16BE","😀"}};
        for(String[] example:examples) {
            frames.clear(); java.nio.charset.Charset charset=java.nio.charset.Charset.forName(example[0]);
            byte[] full=("before\n"+example[1]).getBytes(charset);bytes=Arrays.copyOf(full,full.length-1);mtime++;
            send(socket,"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"encoding\":\""+example[0]+"\",\"offset\":"+bytes.length+"}");
            assertEquals("ready",last().path("type").asText(),example[0]);bytes=("before\n"+example[1]+"\n").getBytes(charset);mtime++;check();
            assertEquals(example[1]+"\n",last().path("content").asText(),example[0]);send(socket,"{\"type\":\"close\"}");
        }
    }
    @Test void boundsNormalBatchesAndRejectsAmbiguousEncodingBoundary() throws Exception {
        bytes=new byte[0];start(0,null);bytes=new byte[600000];Arrays.fill(bytes,(byte)'a');mtime++;check();
        assertEquals(262144,last().path("offset").asLong());assertEquals(262144,last().path("content").asText().length());assertFalse(last().path("eof").asBoolean());
        send(socket,"{\"type\":\"close\"}");frames.clear();
        send(socket,"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"encoding\":\"GBK\",\"offset\":600000}");
        assertEquals("error",last().path("type").asText());assertTrue(last().path("message").asText().contains("边界"));assertEquals(opened,closed);
    }
    @Test void boundsGlobalTailsAndRejectsResumeFromDifferentSession() throws Exception {
        start(2,null);String id=last().path("tailId").asText();List<JsonNode> rejected=new ArrayList<>();
        send(socket("attacker",rejected),"{\"type\":\"resume\",\"sessionToken\":\"other\",\"tailId\":\""+id+"\",\"sequence\":0}");assertEquals("error",rejected.get(0).path("type").asText());
        for(int i=1;i<32;i++)send(socket("s"+i,new ArrayList<>()),"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"encoding\":\"UTF-8\",\"offset\":2}");
        List<JsonNode> over=new ArrayList<>();send(socket("over",over),"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"offset\":2}");assertEquals("error",over.get(0).path("type").asText());assertEquals(32,opened);
        handler.closeAll();assertEquals(opened,closed);
    }
    @Test void adaptsFromActiveToIdleAndResetsAfterNewBytes() throws Exception {
        start(2,null); bytes="a\nnew\n".getBytes(StandardCharsets.UTF_8); mtime++;
        handler.poll(); send(socket,"{\"type\":\"ack\",\"sequence\":1}");
        int count=reads;
        clock.millis+=199; handler.poll(); assertEquals(count,reads);
        clock.millis++; handler.poll(); assertTrue(reads>count); count=reads;
        clock.millis+=399; handler.poll(); assertEquals(count,reads);
        clock.millis++; handler.poll(); assertTrue(reads>count); count=reads;
        clock.millis+=799; handler.poll(); assertEquals(count,reads);
        clock.millis++; handler.poll(); assertTrue(reads>count); count=reads;
        clock.millis+=999; handler.poll(); assertEquals(count,reads);
        bytes="a\nnew\nreset\n".getBytes(StandardCharsets.UTF_8);mtime++;
        clock.millis++;handler.poll();assertEquals("reset\n",last().path("content").asText());
        send(socket,"{\"type\":\"ack\",\"sequence\":2}");count=reads;
        clock.millis+=199;handler.poll();assertEquals(count,reads);
        clock.millis++;handler.poll();assertTrue(reads>count);
        assertEquals(3,frames.stream().filter(f->!"heartbeat".equals(f.path("type").asText())).count(),"quiet checks must not emit data");
    }
    @Test void ackQueuesOneImmediateCatchupAndDoesNotReadOnMessageThread() throws Exception {
        bytes=new byte[0];start(0,null);bytes=new byte[600000];Arrays.fill(bytes,(byte)'x');mtime++;
        handler.poll();assertEquals(1,last().path("sequence").asInt());
        Queue<Runnable> queue=new ArrayDeque<>();ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)queue::add);
        int count=reads;send(socket,"{\"type\":\"ack\",\"sequence\":1}");
        assertEquals(count,reads);assertEquals(1,queue.size(),"ACK must immediately enqueue continuation");
        handler.poll();handler.poll();assertEquals(1,queue.size());queue.remove().run();
        assertEquals(2,last().path("sequence").asInt());assertEquals(516096,last().path("offset").asLong());
        count=reads;clock.millis+=10000;handler.poll();while(!queue.isEmpty())queue.remove().run();assertEquals(count,reads);
        send(socket,"{\"type\":\"ack\",\"sequence\":2}");send(socket,"{\"type\":\"pause\"}");
        while(!queue.isEmpty())queue.remove().run();assertEquals(count,reads);
        send(socket,"{\"type\":\"resume\"}");assertEquals(1,queue.size());queue.remove().run();
        assertEquals(600000,last().path("offset").asLong());assertEquals(3,last().path("sequence").asInt());
        send(socket,"{\"type\":\"close\"}");while(!queue.isEmpty())queue.remove().run();assertEquals(opened,closed);
    }
    @Test void filteredBacklogAlsoContinuesImmediatelyOnAck() throws Exception {
        bytes=new byte[0];String scan=filters.start("owner","/app.log","hit","UTF-8",true,0).getScanId();filters.next("owner",scan);start(0,scan);
        StringBuilder log=new StringBuilder();for(int n=0;n<40000;n++)log.append("miss line\n");log.append("hit end\n");
        bytes=log.toString().getBytes(StandardCharsets.UTF_8);mtime++;handler.poll();
        assertFalse(last().path("eof").asBoolean());assertEquals(0,last().path("rows").size());
        send(socket,"{\"type\":\"ack\",\"sequence\":1}");
        assertEquals(2,last().path("sequence").asInt());assertEquals("hit end",last().path("rows").get(0).path("text").asText());
        assertTrue(last().path("eof").asBoolean());assertEquals(3,opened,"historical start, scan read, one retained Tail handle");
    }
    @Test void queuedClosePreventsReadAndReleasesHandleWithoutWaitingForTimer() throws Exception {
        start(2,null);Queue<Runnable> queue=new ArrayDeque<>();ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)queue::add);
        bytes="a\nlate\n".getBytes(StandardCharsets.UTF_8);mtime++;handler.poll();handler.poll();assertEquals(1,queue.size());
        int count=reads;send(socket,"{\"type\":\"close\"}");assertEquals(1,queue.size());
        while(!queue.isEmpty())queue.remove().run();assertEquals(count,reads);assertEquals(opened,closed);assertEquals(1,frames.size());
    }
    @Test void ackCatchupsYieldToAnotherSubscriptionInFifoOrder() throws Exception {
        bytes=new byte[0];start(0,null);List<JsonNode> second=new ArrayList<>();WebSocketSession other=socket("fair",second);
        send(other,"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"offset\":0}");
        Queue<Runnable> queue=new ArrayDeque<>();ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)queue::add);
        bytes=new byte[600000];Arrays.fill(bytes,(byte)'x');mtime++;handler.poll();assertEquals(2,queue.size());queue.remove().run();
        WebSocketSession first=frames.size()>1?socket:other;List<JsonNode> waiting=frames.size()>1?second:frames;
        send(first,"{\"type\":\"ack\",\"sequence\":1}");assertEquals(2,queue.size());queue.remove().run();
        assertEquals(1,waiting.get(waiting.size()-1).path("sequence").asInt(),"other subscription precedes high-volume continuation");
        assertEquals(1,queue.size());queue.remove().run();
    }
    @Test void pauseBeforeInitializationStillReceivesResumableTailId() throws Exception {
        Queue<Runnable> queue=new ArrayDeque<>();ReflectionTestUtils.setField(handler,"executor",(java.util.concurrent.Executor)queue::add);
        start(2,null);send(socket,"{\"type\":\"pause\"}");while(!queue.isEmpty())queue.remove().run();
        assertFalse(frames.isEmpty(),"paused initialization still needs ready ID");assertEquals("ready",last().path("type").asText());assertFalse(last().path("tailId").asText().isEmpty());assertEquals(0,opened);
    }
    @Test void inFlightPauseHoldsExactlyOneBatchAndCloseSuppressesLateDelivery() throws Exception {
        start(2,null);java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(handler,"executor",worker);
        try {
            bytes="a\nheld\n".getBytes(StandardCharsets.UTF_8);mtime++;blockOnRead.set(true);handler.poll();
            assertTrue(blocked.await(2,java.util.concurrent.TimeUnit.SECONDS));send(socket,"{\"type\":\"pause\"}");
            release.countDown();worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,frames.size());
            send(socket,"{\"type\":\"resume\"}");worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("held\n",last().path("content").asText());assertEquals(1,last().path("sequence").asLong());
            send(socket,"{\"type\":\"ack\",\"sequence\":1}");send(socket,"{\"type\":\"close\"}");
            worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals(opened,closed);
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void disconnectDuringOpeningRetainsResumableSubscription() throws Exception {
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();ReflectionTestUtils.setField(handler,"executor",worker);
        try {
            blockOnOpen.set(true);start(2,null);String id=last().path("tailId").asText();assertTrue(blocked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            handler.afterConnectionClosed(socket,CloseStatus.NORMAL);release.countDown();worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);
            List<JsonNode> resumed=new ArrayList<>();WebSocketSession replacement=socket("opened-again",resumed);
            send(replacement,"{\"type\":\"resume\",\"sessionToken\":\"owner\",\"tailId\":\""+id+"\",\"sequence\":0}");
            worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals("ready",resumed.get(0).path("type").asText());
            bytes="a\nresumed\n".getBytes(StandardCharsets.UTF_8);mtime++;clock.millis+=1000;handler.poll();worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("resumed\n",resumed.get(resumed.size()-1).path("content").asText());
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void closeDuringReadDiscardsLateDataAndReleasesResources() throws Exception {
        start(2,null);java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();ReflectionTestUtils.setField(handler,"executor",worker);
        try {
            bytes="a\nlate\n".getBytes(StandardCharsets.UTF_8);mtime++;blockOnRead.set(true);handler.poll();assertTrue(blocked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            send(socket,"{\"type\":\"close\"}");release.countDown();worker.submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,frames.size());assertEquals(opened,closed);
        } finally {release.countDown();worker.shutdownNow();}
    }
    private void check() {clock.millis+=200;handler.poll();}
    private void start(long offset,String scan) throws Exception {send(socket,"{\"type\":\"start\",\"sessionToken\":\"owner\",\"path\":\"/app.log\",\"encoding\":\"UTF-8\",\"offset\":"+offset+(scan==null?"":",\"scanId\":\""+scan+"\"")+"}");}
    private void send(WebSocketSession target,String message)throws Exception{handler.handleMessage(target,new TextMessage(message));}
    private JsonNode last(){return frames.get(frames.size()-1);}
    private WebSocketSession socket(String id,List<JsonNode> output)throws Exception{
        WebSocketSession ws=mock(WebSocketSession.class); when(ws.getId()).thenReturn(id); when(ws.isOpen()).thenReturn(true);
        doAnswer(call->{JsonNode frame=json.readTree(((TextMessage)call.getArgument(0)).getPayload());output.add(frame);if(observedReady!=null && "ready".equals(frame.path("type").asText()))observedReady.countDown();if(observedData!=null && "data".equals(frame.path("type").asText()))observedData.countDown();return null;}).when(ws).sendMessage(any()); return ws;
    }
    private static final class MutableClock extends Clock { long millis=100000; public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return Instant.ofEpochMilli(millis);} public long millis(){return millis;} }
}
