package com.doumiot.sshconsole.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.doumiot.sshconsole.service.SshDiskUsageService.*;

class DiskUsageTest {
    @TempDir Path temp;

    private String requireGnuScanTools() throws Exception {
        String bash=Files.exists(Paths.get("D:/dev/Git/bin/bash.exe"))?"D:/dev/Git/bin/bash.exe":"/bin/bash";
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Paths.get(bash)),"真实目录扫描测试需要 Bash");
        // Probe host prerequisites independently of command(); do not turn scan regressions into skips.
        String probe="export PATH=/usr/bin:/bin:$PATH\n"
                +"for tool in timeout du find sort; do \"$tool\" --version >/dev/null 2>&1 || exit 1; done\n"
                +"command -v mktemp >/dev/null 2>&1 || exit 1\n"
                +"printf 'a\\000b\\000' | LC_ALL=C awk 'BEGIN { RS=\"\\0\" } END { exit (NR!=2) }'";
        Process process=new ProcessBuilder(bash,"--noprofile","--norc","-c",probe).start();
        boolean finished=process.waitFor(5,TimeUnit.SECONDS);
        if(!finished)process.destroyForcibly();
        org.junit.jupiter.api.Assumptions.assumeTrue(finished&&process.exitValue()==0,
                "真实目录扫描测试需要 GNU timeout/du/find/sort 及支持 NUL 记录的 awk；请在 Linux 环境运行");
        return bash;
    }

    @Test void realGnuScanHandlesQuotedPathsAndHomeAndSizeThreshold() throws Exception {
        String bash=requireGnuScanTools();
        Path root=Files.createDirectory(temp.resolve("space ' and $HOME"));
        Files.createDirectory(root.resolve("sub folder"));
        Files.write(root.resolve("large file.bin"),new byte[2*1024*1024]);
        Files.write(root.resolve("sub folder/nested.bin"),new byte[3*1024*1024]);
        Files.write(root.resolve("small.bin"),new byte[3]);
        Path script=temp.resolve("scan.sh");Files.write(script,("export PATH=/usr/bin:/bin:$PATH\n"+command(root.toString().replace('\\','/'),1)).getBytes(StandardCharsets.UTF_8));
        ProcessBuilder builder=new ProcessBuilder(bash,"--noprofile","--norc",script.toString().replace('\\','/'));
        Path out=temp.resolve("stdout"),err=temp.resolve("stderr");builder.redirectOutput(out.toFile());builder.redirectError(err.toFile());
        Process process=builder.start();assertTrue(process.waitFor(15,TimeUnit.SECONDS));
        String stderr=new String(Files.readAllBytes(err),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),stderr);
        ScanResult result=parse(new String(Files.readAllBytes(out),StandardCharsets.UTF_8),stderr);
        assertEquals(1,result.getFiles().size());assertEquals(2*1024*1024L,result.getFiles().get(0).getBytes());
        assertEquals(1,result.getDirectories().size());assertTrue(result.getPath().endsWith("space ' and $HOME"));
        assertFalse(result.isPartial());
    }
    @Test void parserPreservesSpacesTabsNewlinesAndReportsPermissionFailures() throws Exception {
        String text=String.join("\0","__PATH__","/home/user","__DIRS__","4096\t/home/user","2048\t/home/user/a b","__FILES__","2000\t/home/user/a\tb\nc","__STATUS__","1 1","__END__","");
        ScanResult result=parse(text,"Permission denied");
        assertEquals("/home/user",result.getPath());assertEquals(4096L,result.getTotalBytes());
        assertEquals("/home/user/a b",result.getDirectories().get(0).getPath());
        assertEquals("/home/user/a\tb\nc",result.getFiles().get(0).getPath());assertTrue(result.isPartial());
        assertTrue(result.getWarning().contains("Permission denied"));
    }
    @Test void incompleteOrUnsupportedOutputDoesNotBecomeSuccessfulEmptyResult() {
        assertThrows(IOException.class,()->parse("", ""));
        assertThrows(IOException.class,()->parse("__PATH__\0/home/user\0__END__\0", ""));
        IOException error=assertThrows(IOException.class,()->parse("__PATH__\0/home/user\0__END__\0", "du: unrecognized option '--null'"));
        assertTrue(error.getMessage().contains("du: unrecognized option '--null'"));
    }
    @Test void realScanSupportsOldHeadAndKeepsRootWhenZeroSizeDirectoriesTie() throws Exception {
        String bash=requireGnuScanTools();
        Path root=Files.createDirectory(temp.resolve("zero-space"));
        Path bin=Files.createDirectory(temp.resolve("bin"));
        // Model filesystems with zero allocated bytes for empty directories; the root sorts last.
        Files.write(bin.resolve("du"),("#!/bin/sh\nfor p do :; done\ni=0\nwhile [ $i -lt 250 ]; do printf '0\\t%s/child%s\\000' \"$p\" \"$i\"; i=$((i+1)); done\nprintf '0\\t%s\\000' \"$p\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(bin.resolve("head"),"#!/bin/sh\necho 'head: invalid option -- z' >&2\nexit 1\n".getBytes(StandardCharsets.UTF_8));
        Path script=temp.resolve("compat.sh");
        String binPath=bin.toString().replace('\\','/');
        Files.write(script,("export PATH=/usr/bin:/bin:$PATH\nchmod +x "+quote(binPath)+"/*\nscan_bin=$(cd "+quote(binPath)+" && pwd -P)\nexport PATH=\"$scan_bin:$PATH\"\n"+command(root.toString().replace('\\','/'),1)).getBytes(StandardCharsets.UTF_8));
        Path out=temp.resolve("compat-out"),err=temp.resolve("compat-err");
        Process process=new ProcessBuilder(bash,"--noprofile","--norc",script.toString().replace('\\','/')).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));
        String stderr=new String(Files.readAllBytes(err),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),stderr);
        ScanResult result=parse(new String(Files.readAllBytes(out),StandardCharsets.UTF_8),stderr);
        assertEquals(0L,result.getTotalBytes());assertEquals(200,result.getDirectories().size());
        assertTrue(result.isLimited());assertFalse(result.isPartial());assertTrue(result.getFiles().isEmpty());
    }
    @Test void commandsQuoteInputsAndUseTimeoutAndFilesystemBoundary() {
        String cmd=command("/home/a'; touch /tmp/unsafe; echo '",100);
        assertTrue(cmd.startsWith("command -v timeout"));assertTrue(cmd.contains("timeout -k 3s 85s sh -c"));
        assertTrue(cmd.contains("-xdev"));assertTrue(cmd.contains("--max-depth=1"));
        assertTrue(cmd.contains("-mindepth 1 -maxdepth 1"));
        assertFalse(cmd.contains("head -z"));
        assertTrue(cmd.contains("104857599c"));assertEquals("'a'\"'\"'b'",quote("a'b"));
        assertTrue(command(null,1).contains("$HOME"));
    }
}
