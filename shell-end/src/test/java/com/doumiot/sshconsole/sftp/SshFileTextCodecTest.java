package com.doumiot.sshconsole.sftp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SshFileTextCodecTest {
    @Test void gbkAndGb18030UseExactBytes() throws Exception {
        assertArrayEquals(new byte[]{(byte)0xd6,(byte)0xd0,(byte)0xce,(byte)0xc4}, SshFileTextCodec.encode("中文".getBytes(StandardCharsets.UTF_8), "GBK", false));
        assertArrayEquals(new byte[]{(byte)0x94,0x39,(byte)0xfc,0x36}, SshFileTextCodec.encode("😀".getBytes(StandardCharsets.UTF_8), "GB18030", false));
    }
    @Test void unicodeBomIsOptionalAndHasTargetByteOrder() throws Exception {
        assertArrayEquals(new byte[]{(byte)0xff,(byte)0xfe,0x61,0}, SshFileTextCodec.encode(new byte[]{0x61}, "UTF-16LE", true));
        assertArrayEquals(new byte[]{(byte)0xfe,(byte)0xff,0,0x61}, SshFileTextCodec.encode(new byte[]{0x61}, "UTF-16BE", true));
        assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,0x61}, SshFileTextCodec.encode(new byte[]{0x61}, "UTF-8", true));
        assertArrayEquals(new byte[]{0x61,0}, SshFileTextCodec.encode(new byte[]{0x61}, "UTF-16LE", false));
        assertArrayEquals(new byte[]{0x61}, SshFileTextCodec.encode(new byte[]{0x61}, "GBK", true));
    }
    @Test void unrepresentableOrInvalidInputIsRejectedBeforeAnyWrite() {
        assertThrows(IOException.class, () -> SshFileTextCodec.encode("😀".getBytes(StandardCharsets.UTF_8), "GBK", false));
        assertThrows(IOException.class, () -> SshFileTextCodec.encode(new byte[]{(byte)0xff}, "UTF-8", false));
        assertThrows(IOException.class, () -> SshFileTextCodec.encode(new byte[]{0x61}, "not-a-charset", false));
    }
    @Test void otherOfferedEncodingsRoundTripWithoutReplacement() throws Exception {
        String[][] cases={{"Big5","中文"},{"ISO-8859-1","café"},{"Windows-1252","€"},{"Shift_JIS","日本語"},{"EUC-KR","한국어"}};
        for(String[] c:cases) assertEquals(c[1],new String(SshFileTextCodec.encode(c[1].getBytes(StandardCharsets.UTF_8),c[0],false),c[0]));
        assertArrayEquals(new byte[0], SshFileTextCodec.encode(new byte[0],"UTF-8",false));
    }
}
