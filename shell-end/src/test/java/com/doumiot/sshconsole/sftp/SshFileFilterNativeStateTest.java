package com.doumiot.sshconsole.sftp;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SshFileFilterNativeStateTest {
    @Test void nativePreviewFindsLateMatchWithoutRetainingEntireLine() throws Exception {
        SshFileFilterScanner.NativeLine preview=new SshFileFilterScanner.NativeLine("needle");
        byte[] block=new byte[65536]; Arrays.fill(block,(byte)'x');
        for(int i=0;i<40;i++) preview.accept(block,0,block.length);
        byte[] suffix="needle😀end\r".getBytes(StandardCharsets.UTF_8);preview.accept(suffix,0,suffix.length);
        SshFileFilterScanner.Row row=preview.finish(7);
        assertTrue(row.isMatched());assertTrue(row.isTruncated());assertTrue(row.getText().contains("needle😀end"));assertTrue(row.getText().length()<=65536);
    }
    @Test void handoffRestoresAbsoluteContextAndDoesNotCountHistoricalRowsTwice() throws Exception {
        SshFileFilterScanner oracle=new SshFileFilterScanner("hit","UTF-8",true,2);
        List<SshFileFilterScanner.Row> old=oracle.accept("old\nhit\nlast\n".getBytes(StandardCharsets.UTF_8),true);
        SshFileFilterScanner resumed=new SshFileFilterScanner("hit","UTF-8",true,2);
        for(SshFileFilterScanner.Row row:old) resumed.acceptNativeRow(row);
        resumed.seedNative("hit\nlast\n".getBytes(StandardCharsets.UTF_8),3);
        byte[] append="new partial".getBytes(StandardCharsets.UTF_8);
        assertEquals(oracle.accept(append,true),resumed.accept(append,true));
        assertEquals(oracle.accept(" done\nhit next\n".getBytes(StandardCharsets.UTF_8),true),resumed.accept(" done\nhit next\n".getBytes(StandardCharsets.UTF_8),true));
    }
    @Test void nativeRowsUseTheSameGlobalLimit() throws Exception {
        SshFileFilterScanner scanner=new SshFileFilterScanner("hit","UTF-8",true,0);
        for(int i=1;i<=10000;i++) assertNotNull(scanner.acceptNativeRow(new SshFileFilterScanner.Row(i,"hit",true)));
        assertTrue(scanner.isLimited());assertNull(scanner.acceptNativeRow(new SshFileFilterScanner.Row(10001,"hit",true)));
    }
}
