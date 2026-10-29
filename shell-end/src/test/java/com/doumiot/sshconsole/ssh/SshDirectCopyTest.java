package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SshDirectCopyTest {
    @TempDir Path root;
    @Test void nestedShellQuotingCopiesLiteralNamesWithoutExecutingThem() throws Exception {
        Path bin=Files.createDirectory(root.resolve("bin"));
        Path fake=bin.resolve("ssh");
        Files.write(fake,("#!/bin/sh\nfor arg do last=$arg; done\nexec sh -c \"$last\"\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        Path source=root.resolve("source ' $(touch PWNED).txt"), target=root.resolve("target ' $(touch PWNED).txt");
        Files.write(source,"hello direct\n".getBytes(StandardCharsets.UTF_8));
        ProcessBuilder process=new ProcessBuilder("sh","-c",SshDirectCopy.command("server.example",2222,"alice",source.toString(),target.toString()));
        process.directory(root.toFile());process.environment().put("PATH",bin+":/usr/bin:/bin");
        assertEquals(0,process.start().waitFor());
        assertEquals("hello direct\n",new String(Files.readAllBytes(target),StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("PWNED")));
    }
    @Test void rejectsHostAndUserOptionInjection() {
        assertThrows(IOException.class,()->SshDirectCopy.command("-oProxyCommand=touch",22,"alice","/a","/b"));
        assertThrows(IOException.class,()->SshDirectCopy.command("server",22,"alice\nroot","/a","/b"));
        assertThrows(IOException.class,()->SshDirectCopy.command("server",0,"alice","/a","/b"));
    }
    @Test void supportsIpv6Targets() throws Exception {
        assertDoesNotThrow(()->SshDirectCopy.command("::1",22,"alice","/a","/b"));
    }
}
