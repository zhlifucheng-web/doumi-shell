package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.protocol.GuacamoleInstruction;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class RdpClipboardInputTest {
    @Test void textStreamsAreBoundedAndCannotBeUsedForOtherTransfers() throws Exception {
        RdpClipboardInput input=new RdpClipboardInput();
        assertFalse(input.accept(new GuacamoleInstruction("key","65","1")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("clipboard","0","image/png")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("blob","0","YQ==")));
        assertTrue(input.accept(new GuacamoleInstruction("clipboard","0","text/plain")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("clipboard","1","text/plain")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("blob","1","YQ==")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("blob","0","!invalid!")));
        String block=Base64.getEncoder().encodeToString(new byte[6048]);
        for(int i=0;i<43;i++)assertTrue(input.accept(new GuacamoleInstruction("blob","0",block)));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("blob","0",block)));
        assertTrue(input.accept(new GuacamoleInstruction("end","0")));
        assertThrows(org.apache.guacamole.GuacamoleClientException.class,()->input.accept(new GuacamoleInstruction("end","0")));
        assertTrue(input.accept(new GuacamoleInstruction("clipboard","0","text/plain")));
    }
}
