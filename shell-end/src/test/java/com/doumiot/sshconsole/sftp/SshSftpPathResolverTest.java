package com.doumiot.sshconsole.sftp;

import com.doumiot.spring.exception.BusinessRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SshSftpPathResolverTest {

    @Test
    void testResolveHomePath() {
        // Tilde alone resolves to homeDir
        assertEquals("/root", SshSftpPathResolver.resolveForList("~", null, "/root"));
        assertEquals("/home/ubuntu", SshSftpPathResolver.resolveForList("~", null, "/home/ubuntu"));
        assertEquals("/root", SshSftpPathResolver.resolve("~", null, "/root"));

        // Tilde with trailing slash
        assertEquals("/root", SshSftpPathResolver.resolveForList("~/", null, "/root"));
        assertEquals("/root", SshSftpPathResolver.resolve("~/", null, "/root"));

        // Subpath under home
        assertEquals("/root/logs", SshSftpPathResolver.resolveForList("~/logs", null, "/root"));
        assertEquals("/root/logs", SshSftpPathResolver.resolve("~/logs", null, "/root"));
        assertEquals("/home/ubuntu/app/config.yml", SshSftpPathResolver.resolve("~/app/config.yml", null, "/home/ubuntu"));
    }

    @Test
    void testEmptyPathFallback() {
        // Empty path with homeDir
        assertEquals("/root", SshSftpPathResolver.resolveForList("", null, "/root"));
        assertEquals("/root", SshSftpPathResolver.resolveForList(null, null, "/root"));

        // Empty path with defaultCwd
        assertEquals("/data/app", SshSftpPathResolver.resolveForList("", "/data/app", null));

        // Empty path with both
        assertEquals("/data/app", SshSftpPathResolver.resolveForList("", "/data/app", "/root"));

        // Empty path with neither falls back to root
        assertEquals("/", SshSftpPathResolver.resolveForList("", null, null));
    }

    @Test
    void testAbsolutePathsUnchanged() {
        assertEquals("/var/log", SshSftpPathResolver.resolveForList("/var/log", null, "/root"));
        assertEquals("/etc", SshSftpPathResolver.resolveForList("/etc", null, "/root"));
        assertEquals("/tmp", SshSftpPathResolver.resolveForList("/tmp", null, "/root"));
        assertEquals("/", SshSftpPathResolver.resolveForList("/", null, "/root"));
    }

    @Test
    void testRejectDotDotEscapes() {
        assertThrows(BusinessRuntimeException.class, () -> SshSftpPathResolver.resolve("..", null, "/root"));
        assertThrows(BusinessRuntimeException.class, () -> SshSftpPathResolver.resolve("/root/../etc", null, "/root"));
    }
}
