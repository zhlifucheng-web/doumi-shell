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
    private static String repeat(char ch,int count) {char[] chars=new char[count];Arrays.fill(chars,ch);return new String(chars);}
    @Test void scansBeyondLongUnmatchedLinesAndFindsSuffixMatches() throws Exception {
        SshFileFilterScanner scanner=new SshFileFilterScanner("needle","UTF-8",true,0);
        for(int i=0;i<32;i++) assertTrue(scanner.accept(repeat('a',65536).getBytes(StandardCharsets.UTF_8),false).isEmpty());
        List<SshFileFilterScanner.Row> found=scanner.accept("needle suffix\nneedle next\n".getBytes(StandardCharsets.UTF_8),true);
        assertEquals(2,found.size());assertEquals(1,found.get(0).getLineNumber());assertTrue(found.get(0).getText().contains("needle suffix"));assertTrue(found.get(0).isTruncated());
        assertEquals("needle next",found.get(1).getText());assertEquals(2,found.get(1).getLineNumber());assertFalse(found.get(1).isTruncated());assertFalse(scanner.isLimited());
    }
    @Test void findsKeywordAcrossPreviewAndBatchBoundariesWithoutCuttingIt() throws Exception {
        SshFileFilterScanner scanner=new SshFileFilterScanner("needle","UTF-8",false,0);
        assertTrue(scanner.accept((repeat('a',65534)+"Nee").getBytes(StandardCharsets.UTF_8),true).isEmpty());
        SshFileFilterScanner.Row row=scanner.accept("DLE suffix\n".getBytes(StandardCharsets.UTF_8),true).get(0);
        assertTrue(row.isMatched());assertTrue(row.isTruncated());assertTrue(row.getText().contains("NeeDLE suffix"));assertTrue(row.getText().length()<=65536);
        String keyword=repeat('k',65536);scanner=new SshFileFilterScanner(keyword,"UTF-8",true,0);
        scanner.accept(repeat('a',80000).getBytes(StandardCharsets.UTF_8),false);
        row=scanner.accept((keyword+" ending\n").getBytes(StandardCharsets.UTF_8),true).get(0);
        assertEquals(keyword,row.getText());assertTrue(row.isTruncated());
    }
    @Test void tailUpdatesTruncationFlagEvenWhenThePreviewTextStaysTheSame() throws Exception {
        SshFileFilterScanner scanner=new SshFileFilterScanner("hit","UTF-8",true,0);
        SshFileFilterScanner.Row first=scanner.accept(("hit"+repeat('a',65533)).getBytes(StandardCharsets.UTF_8),true).get(0);
        assertFalse(first.isTruncated());
        SshFileFilterScanner.Row update=scanner.accept("more".getBytes(StandardCharsets.UTF_8),true).get(0);
        assertEquals(first.getText(),update.getText());assertTrue(update.isTruncated());assertEquals(first.getLineNumber(),update.getLineNumber());
        assertTrue(scanner.accept(new byte[0],true).isEmpty());
        assertEquals(2,scanner.accept("\nhit next\n".getBytes(StandardCharsets.UTF_8),true).get(0).getLineNumber());
    }
    @Test void preservesLongContextAndCrLfAndKeepsUnicodePreviewValid() throws Exception {
        SshFileFilterScanner scanner=new SshFileFilterScanner("hit","UTF-8",true,2);
        List<SshFileFilterScanner.Row> context=scanner.accept((repeat('a',70000)+"\r\nhit\r\n"+repeat('b',70000)+"\n").getBytes(StandardCharsets.UTF_8),true);
        assertEquals(3,context.size());assertTrue(context.get(0).isTruncated());assertTrue(context.get(2).isTruncated());assertEquals("hit",context.get(1).getText());
        scanner=new SshFileFilterScanner("hit","UTF-8",true,0);
        SshFileFilterScanner.Row unicode=scanner.accept(("hit"+repeat('a',65532)+"😀tail\n").getBytes(StandardCharsets.UTF_8),true).get(0);
        assertFalse(Character.isHighSurrogate(unicode.getText().charAt(unicode.getText().length()-1)));assertTrue(unicode.isTruncated());
        scanner=new SshFileFilterScanner("\r","UTF-8",true,0);
        assertTrue(scanner.accept("a\r".getBytes(StandardCharsets.UTF_8),true).isEmpty());assertTrue(scanner.accept("\n".getBytes(StandardCharsets.UTF_8),true).isEmpty());
        assertTrue(scanner.accept("b\r".getBytes(StandardCharsets.UTF_8),true).isEmpty());assertEquals("b\rx",scanner.accept("x".getBytes(StandardCharsets.UTF_8),true).get(0).getText());
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
