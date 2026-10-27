package com.doumiot.sshconsole.sftp;

import lombok.Value;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Incremental literal line matcher. Retains only decoder carry, current line and preceding context. */
public final class SshFileFilterScanner {
    private static final int MAX_LINE_CHARS = 65536;
    private static final int MAX_ROWS = 10000;
    private static final int MAX_RESULT_CHARS = 4 * 1024 * 1024;
    private final CharsetDecoder decoder;
    private final String keyword;
    private final boolean caseSensitive;
    private final int context;
    private final StringBuilder line = new StringBuilder();
    private final Deque<Row> before = new ArrayDeque<>();
    private byte[] carry = new byte[0];
    private long lineNumber = 1, afterUntil, lastEmitted;
    private int count, resultChars, lastEmittedLength;
    private boolean limited;

    public SshFileFilterScanner(String keyword, String encoding, boolean caseSensitive, int contextLines) {
        if (keyword == null || keyword.isEmpty() || keyword.length() > MAX_LINE_CHARS)
            throw new IllegalArgumentException("关键字不能为空且不能超过 65536 字符");
        if (contextLines != 0 && contextLines != 2) throw new IllegalArgumentException("上下文只能为 0 或 2 行");
        this.keyword = keyword; this.caseSensitive = caseSensitive; this.context = contextLines;
        decoder = Charset.forName(encoding == null || encoding.isEmpty() ? "UTF-8" : encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    public List<Row> accept(byte[] bytes, boolean eof) throws IOException {
        LinkedHashMap<Long, Row> rows = new LinkedHashMap<>();
        if (limited) return new ArrayList<>();
        ByteBuffer input = ByteBuffer.allocate(carry.length + bytes.length); input.put(carry).put(bytes).flip();
        CharBuffer chars = CharBuffer.allocate(8192);
        while (!limited) {
            CoderResult result = decoder.decode(input, chars, false); // EOF is a snapshot: the same decoder continues after append.
            chars.flip();
            while (chars.hasRemaining() && !limited) {
                char ch = chars.get();
                if (ch == '\n') {
                    Row completed = current(); select(completed, rows);
                    if (context > 0) { before.addLast(completed); while (before.size() > context) before.removeFirst(); }
                    line.setLength(0); lineNumber++;
                } else {
                    if (line.length() >= MAX_LINE_CHARS) throw new IOException("单行超过 65536 字符，无法继续筛选，请使用其他工具");
                    line.append(ch);
                }
            }
            chars.clear();
            if (result.isError()) throw new IOException("文件内容与所选编码不匹配，请更换编码后重新筛选");
            if (result.isUnderflow()) break;
        }
        carry = new byte[input.remaining()]; input.get(carry);
        if (eof && line.length() > 0 && !limited) select(current(), rows);
        return new ArrayList<>(rows.values());
    }

    private Row current() {
        String text = line.toString(); if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        boolean matched = false;
        if (caseSensitive) matched = text.contains(keyword);
        else for (int i = 0; i <= text.length() - keyword.length(); i++) {
            if (text.regionMatches(true, i, keyword, 0, keyword.length())) { matched = true; break; }
        }
        return new Row(lineNumber, text, matched);
    }

    private void select(Row row, Map<Long, Row> rows) {
        if (row.matched) {
            for (Row previous : before) if (previous.lineNumber > lastEmitted) emit(previous, rows);
            afterUntil = row.lineNumber + context;
        }
        if (row.matched || row.lineNumber <= afterUntil) emit(row, rows);
    }

    private void emit(Row row, Map<Long, Row> rows) {
        if (limited || row.lineNumber < lastEmitted) return;
        int addedChars = row.text.length() - (row.lineNumber == lastEmitted ? lastEmittedLength : 0);
        if (addedChars > MAX_RESULT_CHARS - resultChars) { limited = true; return; }
        if (row.lineNumber > lastEmitted) { count++; lastEmitted = row.lineNumber; }
        resultChars += addedChars; lastEmittedLength = row.text.length();
        rows.put(row.lineNumber, row);
        if (count >= MAX_ROWS || resultChars >= MAX_RESULT_CHARS) limited = true;
    }
    public boolean isLimited() { return limited; }
    @Value public static class Row { long lineNumber; String text; boolean matched; }
}
