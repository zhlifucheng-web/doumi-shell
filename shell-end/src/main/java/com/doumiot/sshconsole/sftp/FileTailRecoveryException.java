package com.doumiot.sshconsole.sftp;

import java.io.IOException;

/** Recoverable stream invalidation. The client must reload before accepting a new sequence. */
public class FileTailRecoveryException extends IOException {
    private final String code;
    public FileTailRecoveryException(String code, String message) { super(message); this.code = code; }
    public String getCode() { return code; }
}
