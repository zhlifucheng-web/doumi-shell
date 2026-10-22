package com.doumiot.sshconsole.ssh;

import net.schmizz.sshj.connection.channel.Channel;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshTmuxSessionTest {
    private static SshRemoteExec.ExecResult result(int code, String out) {
        SshRemoteExec.ExecResult r=new SshRemoteExec.ExecResult();r.setExitStatus(code);r.setStdout(out);r.setStderr("");return r;
    }
    private static Channel channel(String output) throws Exception {
        Channel c=mock(Channel.class);when(c.isOpen()).thenReturn(true);
        when(c.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));return c;
    }
    @Test void readinessRequiresTheExactClientAndPreservesScreenBytes() throws Exception {
        AtomicInteger probes=new AtomicInteger();
        SshTmuxSession manager=new SshTmuxSession((cmd,seconds)->result(0, probes.incrementAndGet()==1?"/dev/pts/9\n":"/dev/pts/42\n"));
        InputStream output=manager.awaitAttached(channel("\036DOUMI_TTY:/dev/pts/42\037screen 中文"),"persistent",3000);
        ByteArrayOutputStream screen=new ByteArrayOutputStream();int b;while((b=output.read())!=-1)screen.write(b);
        assertEquals("screen 中文",new String(screen.toByteArray(),StandardCharsets.UTF_8));
        assertEquals(2,probes.get(),"another attached client cannot make this connection ready");
    }
    @Test void markerAloneCannotMakeAnUnattachedChannelReady() throws Exception {
        SshTmuxSession manager=new SshTmuxSession((cmd,seconds)->result(0,"/dev/pts/9\n"));
        assertThrows(java.net.SocketTimeoutException.class,()->manager.awaitAttached(channel("\036DOUMI_TTY:/dev/pts/42\037"),"persistent",1100));
    }
    @Test void concurrentCreatorIsAcceptedOnlyAfterTheSessionExists() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        SshTmuxSession manager=new SshTmuxSession((cmd,seconds)->result(calls.incrementAndGet()==3?0:1,""));
        assertDoesNotThrow(()->manager.prepare("persistent",null,120,40,false));assertEquals(3,calls.get());
    }
    @Test void missingSharedSessionNeverCreatesADifferentShellOrSession() {
        AtomicInteger calls=new AtomicInteger();
        SshTmuxSession manager=new SshTmuxSession((cmd,seconds)->{calls.incrementAndGet();return result(1,"");});
        assertThrows(IOException.class,()->manager.prepare("shared",null,120,40,true));assertEquals(1,calls.get());
    }
    @Test void posixWatchdogActuallyTerminatesABlockedShortCommand() throws Exception {
        Process p=new ProcessBuilder("sh","-c",SshTmuxSession.boundedCommand("exec sleep 20",1)).start();
        try {assertTrue(p.waitFor(5,TimeUnit.SECONDS),"startup must not wait for the blocked process");assertEquals(143,p.exitValue());}
        finally {p.destroyForcibly();}
    }
    @Test void quickPreparationDoesNotWaitForTheWatchdogSleep() throws Exception {
        Process p=new ProcessBuilder("sh","-c",SshTmuxSession.boundedCommand("printf 'ok'",3)).start();
        try {assertTrue(p.waitFor(2,TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertEquals('o',p.getInputStream().read());assertEquals('k',p.getInputStream().read());assertEquals(-1,p.getInputStream().read());}
        finally {p.destroyForcibly();}
    }
}
