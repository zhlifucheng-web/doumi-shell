package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.sftp.SshFileFilterScanner;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SshFileFilterRemoteCursorTest {
    private NativeFilterSnapshot snapshot(byte[] history) {return NativeFilterSnapshot.create(history.length,1,history,history,history);}
    private SshFileFilterRemoteCursor cursor(byte[] history,String wire,SshFileFilterScanner scanner) {
        return new SshFileFilterRemoteCursor(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),()->false,()->{},snapshot(history),scanner,"hit",paused->{});
    }
    @Test void parsesAbsoluteContextAndSeedsAppendedPartialLine() throws Exception {
        byte[] history="old\nhit\nlast\n".getBytes(StandardCharsets.UTF_8);
        SshFileFilterScanner scanner=new SshFileFilterScanner("hit","UTF-8",true,2);
        SshFileFilterRemoteCursor cursor=cursor(history,"COUNT\t3\n1-0-old\n2:4:hit\n3-8-last\nEND\tOK\n",scanner);
        assertEquals(3,cursor.next("id").getRows().size());assertTrue(cursor.finished());
        cursor.seedScanner();assertEquals(Collections.singletonList(new SshFileFilterScanner.Row(4,"new partial",false)),scanner.accept("new partial".getBytes(StandardCharsets.UTF_8),true));
    }
    @Test void partialInitialEofAndUnboundedContextSeedFallBack() {
        assertNull(NativeFilterSnapshot.create(3,1,new byte[0],new byte[0],"hit".getBytes(StandardCharsets.UTF_8)));
        byte[] suffix=new byte[65536];Arrays.fill(suffix,(byte)'x');suffix[suffix.length-1]='\n';
        assertNull(NativeFilterSnapshot.create(90000,1,new byte[0],new byte[0],suffix));
        assertNotNull(snapshot(new byte[0]));
    }
    @Test void consumesAtMostOneWireBudgetAndFindsLateMatchInHugeEarlierLine() throws Exception {
        char[] huge=new char[2*1024*1024];Arrays.fill(huge,'x');String line=new String(huge)+"hit end";
        byte[] history=(line+"\na\nb\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix=Arrays.copyOfRange(history,history.length-65536,history.length);
        NativeFilterSnapshot snapshot=NativeFilterSnapshot.create(history.length,1,Arrays.copyOf(history,4096),Arrays.copyOfRange(history,history.length-4096,history.length),suffix);
        ByteArrayInputStream input=new ByteArrayInputStream(("COUNT\t3\n1:0:"+line+"\nEND\tOK\n").getBytes(StandardCharsets.UTF_8));
        SshFileFilterRemoteCursor cursor=new SshFileFilterRemoteCursor(input,()->false,()->{},snapshot,new SshFileFilterScanner("hit","UTF-8",true,0),"hit",paused->{});
        int before=input.available();assertTrue(cursor.next("id").getRows().isEmpty());assertTrue(before-input.available()<=262144);
        List<SshFileFilterScanner.Row> rows=new ArrayList<>();for(int n=0;n<100&&!cursor.finished();n++)rows.addAll(cursor.next("id").getRows());
        assertTrue(cursor.finished());assertEquals(1,rows.size());assertTrue(rows.get(0).getText().contains("hit end"));assertTrue(rows.get(0).isTruncated());
    }
    @Test void idleInputNeverBlocksAndUnexpectedExitRequestsRecovery() throws Exception {
        InputStream idle=new InputStream(){public int available(){return 0;}public int read(){fail("must not block in read");return -1;}};
        SshFileFilterRemoteCursor cursor=new SshFileFilterRemoteCursor(idle,()->false,()->{},snapshot(new byte[0]),new SshFileFilterScanner("hit","UTF-8",true,0),"hit",paused->{});
        assertFalse(cursor.next("id").isEof());
        SshFileFilterRemoteCursor exited=new SshFileFilterRemoteCursor(idle,()->true,()->{},snapshot(new byte[0]),new SshFileFilterScanner("hit","UTF-8",true,0),"hit",paused->{});
        assertThrows(IOException.class,()->exited.next("id"));
    }
    @Test void returnsAlreadyCountedRowsBeforeDeferringNativeFailureToNextPoll() throws Exception {
        SshFileFilterRemoteCursor c=cursor("hit\n".getBytes(StandardCharsets.UTF_8),"COUNT\t1\n1:0:hit\nEND\tFAILED\n",new SshFileFilterScanner("hit","UTF-8",true,0));
        assertEquals(1,c.next("id").getRows().size());assertThrows(IOException.class,()->c.next("id"));
    }
    @Test void invalidNativeHeadersAndFileChangeFailClosed() {
        for(String wire:Arrays.asList("COUNT\t2\n3:0:hit\n", "COUNT\t0\nEND\tCHANGED\n", "COUNT\t2\n2:0:miss\n")) {
            SshFileFilterRemoteCursor c=cursor("hit\n".getBytes(StandardCharsets.UTF_8),wire,new SshFileFilterScanner("hit","UTF-8",true,0));assertThrows(IOException.class,()->c.next("id"));
        }
    }
}
