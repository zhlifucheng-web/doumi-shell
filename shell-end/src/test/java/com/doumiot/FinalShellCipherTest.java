package com.doumiot;

import com.doumiot.sshconsole.crypto.FinalShellCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FinalShellCipherTest {

    @Test
    void decodesFinalShell3xPasswordSuccessfully() {
        String cipher = "MTlSVHgIIzDGSsDjK+Zz+APwnPHttcHj";
        String plaintext = FinalShellCipher.decodePassword(cipher);
        assertEquals("hs@home123", plaintext);
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(FinalShellCipher.decodePassword(null));
        assertNull(FinalShellCipher.decodePassword(""));
        assertNull(FinalShellCipher.decodePassword("   "));
    }

    @Test
    void returnsPlaintextWhenNotEncrypted() {
        assertEquals("simplePassword123", FinalShellCipher.decodePassword("simplePassword123"));
    }
}
