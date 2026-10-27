package com.doumiot.sshconsole.sftp;

import org.junit.jupiter.api.Test;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SshFileFilterScannerTest {
    @Test void contextOverlapsAcrossBatchesWithoutDuplicates() throws Exception {
        SshFileFilterScanner scanner = new SshFileFilterScanner("hit", "UTF-8", true, 2);
        assertEquals(Arrays.asList("1:a:false", "2:hit:true", "3:b:false"), rows(scanner.accept("a\nhit\nb\n".getBytes(StandardCharsets.UTF_8), false)));
        assertEquals(Arrays.asList("4:hit:true", "5:c:false", "6:d:false"), rows(scanner.accept("hit\nc\nd\ne\n".getBytes(StandardCharsets.UTF_8), true)));
    }
    @Test void literalCaseSensitiveAndInsensitiveMatching() throws Exception {
        assertEquals(Collections.singletonList("2:.*hit:true"), rows(new SshFileFilterScanner(".*hit", "UTF-8", true, 0).accept(".*HIT\n.*hit\n".getBytes(StandardCharsets.UTF_8), true)));
        assertEquals(Arrays.asList("1:.*HIT:true", "2:.*hit:true"), rows(new SshFileFilterScanner(".*hit", "UTF-8", false, 0).accept(".*HIT\n.*hit\n".getBytes(StandardCharsets.UTF_8), true)));
    }
    @Test void decodesSplitMultibyteUtf8AndGbk() throws Exception {
        for (String encoding : Arrays.asList("UTF-8", "GBK")) {
            SshFileFilterScanner scanner = new SshFileFilterScanner("中文", encoding, true, 0);
            byte[] bytes = "中文\r\n".getBytes(Charset.forName(encoding));
            List<SshFileFilterScanner.Row> found = new ArrayList<>();
            for (byte value : bytes) found.addAll(scanner.accept(new byte[] {value}, false));
            assertEquals(Collections.singletonList("1:中文:true"), rows(found));
        }
    }
    @Test void provisionalLastLineIsUpdatedWithoutIncrementingLineNumber() throws Exception {
        SshFileFilterScanner scanner = new SshFileFilterScanner("hit", "UTF-8", true, 2);
        assertEquals(Arrays.asList("1:before:false", "2:hit:true"), rows(scanner.accept("before\nhit".getBytes(StandardCharsets.UTF_8), true)));
        assertEquals(Arrays.asList("2:hit more:true", "3:after:false"), rows(scanner.accept(" more\nafter".getBytes(StandardCharsets.UTF_8), true)));
        assertEquals(Arrays.asList("3:after done:false", "4:last:false"), rows(scanner.accept(" done\nlast\n".getBytes(StandardCharsets.UTF_8), true)));
    }
    @Test void failsExplicitlyOnLongLineInsteadOfDroppingSuffixMatch() {
        byte[] bytes = new byte[65537]; Arrays.fill(bytes, (byte)'a');
        Exception error = assertThrows(java.io.IOException.class, () -> new SshFileFilterScanner("hit", "UTF-8", true, 0).accept(bytes, true));
        assertTrue(error.getMessage().contains("行"));
    }
    @Test void stopsAtTenThousandUniqueRows() throws Exception {
        SshFileFilterScanner scanner = new SshFileFilterScanner("x", "UTF-8", true, 0);
        StringBuilder input = new StringBuilder(); for (int i = 0; i < 10002; i++) input.append("x\n");
        List<SshFileFilterScanner.Row> found = scanner.accept(input.toString().getBytes(StandardCharsets.UTF_8), true);
        assertEquals(10000, found.size()); assertTrue(scanner.isLimited());
        assertEquals(10000, found.get(9999).getLineNumber());
    }
    @Test void limitsTotalResultTextAndCountsProvisionalReplacementOnce() throws Exception {
        SshFileFilterScanner scanner = new SshFileFilterScanner("x", "UTF-8", true, 0);
        char[] full = new char[65536]; Arrays.fill(full, 'x');
        byte[] completed = (new String(full) + "\n").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 63; i++) { assertEquals(1,scanner.accept(completed, false).size()); assertFalse(scanner.isLimited()); }
        byte[] partial = new byte[65530]; Arrays.fill(partial,(byte)'x');
        assertEquals(64,scanner.accept(partial,true).get(0).getLineNumber()); assertFalse(scanner.isLimited());
        List<SshFileFilterScanner.Row> update = scanner.accept("xxxxxx".getBytes(StandardCharsets.UTF_8),true);
        assertEquals(64,update.get(0).getLineNumber()); assertEquals(65536,update.get(0).getText().length()); assertTrue(scanner.isLimited());
        assertTrue(scanner.accept("\nx\n".getBytes(StandardCharsets.UTF_8),true).isEmpty());
    }
    @Test void rejectsUnsupportedOptionsAndMalformedEncoding() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SshFileFilterScanner("", "UTF-8", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new SshFileFilterScanner("x", "UTF-8", true, 1));
        assertThrows(java.io.IOException.class, () -> new SshFileFilterScanner("x", "UTF-8", true, 0).accept(new byte[] {(byte)0xff}, true));
    }
    private static List<String> rows(List<SshFileFilterScanner.Row> rows) {
        List<String> result = new ArrayList<>();
        for (SshFileFilterScanner.Row row : rows) result.add(row.getLineNumber() + ":" + row.getText() + ":" + row.isMatched());
        return result;
    }
}
