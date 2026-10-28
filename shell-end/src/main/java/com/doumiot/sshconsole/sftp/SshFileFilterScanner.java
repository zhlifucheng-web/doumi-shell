package com.doumiot.sshconsole.sftp;

import lombok.Value;
import lombok.AllArgsConstructor;
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
    private final int context;
    private final Line line;
    private Row lastRow;
    private final Deque<Row> before = new ArrayDeque<>();
    private byte[] carry = new byte[0];
    private long lineNumber = 1, afterUntil, lastEmitted;
    private int count, resultChars, lastEmittedLength;
    private boolean limited;

    public SshFileFilterScanner(String keyword, String encoding, boolean caseSensitive, int contextLines) {
        if (keyword == null || keyword.isEmpty() || keyword.length() > MAX_LINE_CHARS)
            throw new IllegalArgumentException("关键字不能为空且不能超过 65536 字符");
        if (contextLines != 0 && contextLines != 2) throw new IllegalArgumentException("上下文只能为 0 或 2 行");
        this.line = new Line(keyword, caseSensitive); this.context = contextLines;
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
                    Row completed = line.current(lineNumber); select(completed, rows);
                    if (context > 0) { before.addLast(completed); while (before.size() > context) before.removeFirst(); }
                    line.clear(); lineNumber++;
                } else {
                    line.append(ch);
                }
            }
            chars.clear();
            if (result.isError()) throw new IOException("文件内容与所选编码不匹配，请更换编码后重新筛选");
            if (result.isUnderflow()) break;
        }
        carry = new byte[input.remaining()]; input.get(carry);
        if (eof && line.hasText() && !limited) select(line.current(lineNumber), rows);
        return new ArrayList<>(rows.values());
    }

    private void select(Row row, Map<Long, Row> rows) {
        if (row.matched) {
            for (Row previous : before) if (previous.lineNumber > lastEmitted) emit(previous, rows);
            afterUntil = row.lineNumber + context;
        }
        if (row.matched || row.lineNumber <= afterUntil) emit(row, rows);
    }

    private void emit(Row row, Map<Long, Row> rows) {
        if (limited || row.lineNumber < lastEmitted || row.equals(lastRow)) return;
        int addedChars = row.text.length() - (row.lineNumber == lastEmitted ? lastEmittedLength : 0);
        if (addedChars > MAX_RESULT_CHARS - resultChars) { limited = true; return; }
        if (row.lineNumber > lastEmitted) { count++; lastEmitted = row.lineNumber; }
        resultChars += addedChars; lastEmittedLength = row.text.length();
        rows.put(row.lineNumber, row); lastRow = row;
        if (count >= MAX_ROWS || resultChars >= MAX_RESULT_CHARS) limited = true;
    }
    public boolean isLimited() { return limited; }
    @Value @AllArgsConstructor public static class Row {
        long lineNumber; String text; boolean matched; boolean truncated;
        public Row(long lineNumber, String text, boolean matched) { this(lineNumber, text, matched, false); }
    }

    /** KMP checks the entire line while prefix/first-match preview remain bounded. */
    private static final class Line {
        private final char[] pattern, recent;
        private final int[] failure;
        private final boolean caseSensitive;
        private final StringBuilder prefix = new StringBuilder(), snippet = new StringBuilder();
        private int matchedLength, recentSize, recentCursor;
        private long units;
        private boolean matched, prefixMatch, pendingCr;
        Line(String keyword, boolean caseSensitive) {
            this.caseSensitive = caseSensitive; pattern = keyword.toCharArray(); failure = new int[pattern.length];
            for (int i = 0; i < pattern.length; i++) pattern[i] = fold(pattern[i]);
            for (int i = 1, length = 0; i < pattern.length; i++) {
                while (length > 0 && pattern[i] != pattern[length]) length = failure[length - 1];
                if (pattern[i] == pattern[length]) length++;
                failure[i] = length;
            }
            recent = new char[pattern.length + Math.min(128, MAX_LINE_CHARS - pattern.length)];
        }
        private char fold(char ch) { return caseSensitive ? ch : Character.toLowerCase(Character.toUpperCase(ch)); }
        void append(char ch) {
            if (ch == '\r') { if (pendingCr) accept('\r'); pendingCr = true; return; }
            if (pendingCr) { accept('\r'); pendingCr = false; }
            accept(ch);
        }
        private void accept(char ch) {
            units++;
            if (prefix.length() < MAX_LINE_CHARS) prefix.append(ch);
            if (matched) {
                if (!prefixMatch && snippet.length() < MAX_LINE_CHARS) snippet.append(ch);
                return;
            }
            recent[recentCursor] = ch; recentCursor = (recentCursor + 1) % recent.length;
            recentSize = Math.min(recentSize + 1, recent.length);
            char normalized = fold(ch);
            while (matchedLength > 0 && normalized != pattern[matchedLength]) matchedLength = failure[matchedLength - 1];
            if (normalized == pattern[matchedLength]) matchedLength++;
            if (matchedLength == pattern.length) {
                matched = true; prefixMatch = units <= MAX_LINE_CHARS;
                if (!prefixMatch) for (int i = 0; i < recentSize; i++) snippet.append(recent[(recentCursor - recentSize + i + recent.length) % recent.length]);
            }
        }
        boolean hasText() { return units > 0 || pendingCr; }
        Row current(long number) {
            String text = (matched && !prefixMatch ? snippet : prefix).toString();
            // Preview boundaries must never split a supplementary character.
            int begin = !text.isEmpty() && Character.isLowSurrogate(text.charAt(0)) ? 1 : 0;
            int end = !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)) ? text.length() - 1 : text.length();
            return new Row(number, text.substring(begin, Math.max(begin, end)), matched, units > MAX_LINE_CHARS);
        }
        void clear() {
            prefix.setLength(0); snippet.setLength(0); units = 0; matchedLength = 0; recentSize = 0; recentCursor = 0;
            matched = false; prefixMatch = false; pendingCr = false;
        }
    }
}
