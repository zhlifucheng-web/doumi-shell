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
    private static final byte[] WHEEL_UP = "\033[M`!!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WHEEL_DOWN = "\033[<65;10;8M".getBytes(StandardCharsets.UTF_8);

    private static SshTmuxSession mouseSession(String state) throws Exception {
        SshTmuxSession manager = new SshTmuxSession((cmd, seconds) -> result(0, state));
        manager.prepare("persistent", null, 120, 40, false);
        return manager;
    }

    @Test void shellWheelRemainsAMouseEventForTmuxHistory() throws Exception {
        assertArrayEquals(WHEEL_UP, mouseSession("mouse").filterWheelInput(WHEEL_UP));
        assertArrayEquals(WHEEL_DOWN, mouseSession("mouse").filterWheelInput(WHEEL_DOWN));
    }

    @Test void editorWithoutMouseReceivesCursorKeysInBothProtocols() throws Exception {
        SshTmuxSession manager = mouseSession("keys");
        assertArrayEquals("\033[A".getBytes(StandardCharsets.UTF_8), manager.filterWheelInput(WHEEL_UP));
        assertArrayEquals("\033[B".getBytes(StandardCharsets.UTF_8), manager.filterWheelInput(WHEEL_DOWN));
        byte[] utf8Coordinates = "\033[Md中文".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals("\033[A".getBytes(StandardCharsets.UTF_8), manager.filterWheelInput(utf8Coordinates));
    }

    @Test void binaryCoordinatesThatResembleUtf8AreStillTwoX10Coordinates() throws Exception {
        byte[] raw = {27, 91, 77, 96, (byte) 195, (byte) 169};
        assertArrayEquals("\033[A".getBytes(StandardCharsets.UTF_8), mouseSession("keys").filterWheelInput(raw));
    }

    @Test void typingAndPastingNeverBecomeMouseInputOrRunRemoteProbes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession manager = new SshTmuxSession((cmd, seconds) -> {
            calls.incrementAndGet();
            return result(0, "keys");
        });
        manager.prepare("persistent", null, 120, 40, false);
        int prepared = calls.get();
        for (String input : new String[]{"vim file\r", "\033[A", "\033[M !!", "\033[<0;1;1M", "pasted \033[M`!! text"}) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(bytes, manager.filterWheelInput(bytes));
        }
        assertEquals(prepared, calls.get());
    }

    @Test void wheelBurstSharesAProbeButTypingInvalidatesTheEditorState() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>("keys");
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession manager = new SshTmuxSession((cmd, seconds) -> {
            calls.incrementAndGet();
            return result(0, state.get());
        });
        manager.prepare("persistent", null, 120, 40, false);
        int prepared = calls.get();
        manager.filterWheelInput(WHEEL_UP);
        manager.filterWheelInput(WHEEL_UP);
        assertEquals(prepared + 1, calls.get());
        state.set("mouse");
        manager.filterWheelInput(":q\r".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(WHEEL_UP, manager.filterWheelInput(WHEEL_UP));
        assertEquals(prepared + 2, calls.get());
    }

    @Test void failedStateProbePreservesMouseRatherThanRecallingAShellCommand() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession manager = new SshTmuxSession((cmd, seconds) -> {
            if (calls.incrementAndGet() > 2) throw new IOException("probe failed");
            return result(0, "");
        });
        manager.prepare("persistent", null, 120, 40, false);
        assertArrayEquals(WHEEL_UP, manager.filterWheelInput(WHEEL_UP));
    }

    @Test void mouseConfigurationFailureDoesNotPublishAReadySession() {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession manager = new SshTmuxSession((cmd, seconds) ->
                result(calls.incrementAndGet() == 1 ? 0 : 1, ""));
        assertThrows(IOException.class, () -> manager.prepare("persistent", null, 120, 40, false));
    }

    @Test void clientEncodingControlsDecorationsWithoutTouchingPaneBytes() throws Exception {
        // Execute the actual attach shell with a fake external tmux, rather than assert command text.
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("tmux-encoding-");
        java.nio.file.Path tmux=directory.resolve("tmux");
        java.nio.file.Files.write(tmux,("#!/bin/sh\ncase \" $* \" in *' -u '*) printf '\\302\\267';; "
                +"*) test \"$LC_ALL\" = C || exit 9; printf '.';; esac\nprintf '\\326\\320\\316\\304'\n").getBytes(StandardCharsets.US_ASCII));
        tmux.toFile().setExecutable(true);
        try {
            String[] encodings={"GBK","GB18030","Big5","GB2312","ISO-8859-1","Windows-1252","UTF-8","utf8","invalid-codepage"};
            byte[] legacy={'.',(byte)0xd6,(byte)0xd0,(byte)0xce,(byte)0xc4};
            byte[] utf8={(byte)0xc2,(byte)0xb7,(byte)0xd6,(byte)0xd0,(byte)0xce,(byte)0xc4};
            for(int i=0;i<encodings.length;i++) {
                ProcessBuilder builder=new ProcessBuilder("sh","-c",SshTmuxSession.attachCommand("shared",true,encodings[i]));
                builder.environment().put("PATH",directory+java.io.File.pathSeparator+System.getenv("PATH"));
                builder.environment().put("LC_ALL","en_US.UTF-8");
                Process process=builder.start();assertTrue(process.waitFor(3,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;while((b=process.getInputStream().read())!=-1)bytes.write(b);
                byte[] output=bytes.toByteArray(),expected=i<6?legacy:utf8;
                assertArrayEquals(expected,java.util.Arrays.copyOfRange(output,output.length-expected.length,output.length),encodings[i]);
            }
        } finally {java.nio.file.Files.deleteIfExists(tmux);java.nio.file.Files.deleteIfExists(directory);}
    }
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
        SshTmuxSession manager=new SshTmuxSession((cmd,seconds)->result(calls.incrementAndGet()>=3?0:1,""));
        assertDoesNotThrow(()->manager.prepare("persistent",null,120,40,false));assertEquals(4,calls.get());
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
