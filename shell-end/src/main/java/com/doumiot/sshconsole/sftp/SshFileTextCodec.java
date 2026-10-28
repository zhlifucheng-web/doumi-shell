package com.doumiot.sshconsole.sftp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Editor input is UTF-8. Validate the complete conversion before an upload can begin. */
public final class SshFileTextCodec {
    public static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final Set<String> ENCODINGS = new HashSet<>(Arrays.asList(
            "UTF-8", "GBK", "GB18030", "Big5", "ISO-8859-1", "Windows-1252",
            "Shift_JIS", "EUC-KR", "UTF-16LE", "UTF-16BE"));
    private SshFileTextCodec() {}

    public static byte[] encode(byte[] utf8, String encoding, boolean bom) throws IOException {
        if (!ENCODINGS.contains(encoding)) throw new IOException("不支持的文件编码: " + encoding);
        if (utf8.length > MAX_BYTES) throw new IOException("在线编辑文件超过 50 MB 限制");
        try {
            CharBuffer text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8));
            ByteBuffer encoded = Charset.forName(encoding).newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(text);
            byte[] prefix = new byte[0];
            if (bom) {
                if ("UTF-8".equals(encoding)) prefix = new byte[]{(byte)0xef, (byte)0xbb, (byte)0xbf};
                else if ("UTF-16LE".equals(encoding)) prefix = new byte[]{(byte)0xff, (byte)0xfe};
                else if ("UTF-16BE".equals(encoding)) prefix = new byte[]{(byte)0xfe, (byte)0xff};
            }
            if ((long) prefix.length + encoded.remaining() > MAX_BYTES) throw new IOException("编码后的文件超过 50 MB 限制");
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + encoded.remaining());
            encoded.get(bytes, prefix.length, encoded.remaining());
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IOException("无法使用 " + encoding + " 保存：部分字符无法表示或文本编码无效，请选择其他保存编码", e);
        }
    }
}
