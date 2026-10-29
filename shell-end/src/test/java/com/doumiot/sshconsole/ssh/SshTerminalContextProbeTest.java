package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class SshTerminalContextProbeTest {
    private String valid(String cwd) {
        return "{\"available\":true,\"status\":\"ready\",\"cwd\":\""+cwd+"\",\"terminalUser\":\"app\",\"terminalUid\":1001,\"terminalGid\":1002,\"fileUser\":\"root\",\"fileUid\":0,\"fileGid\":0,\"contextId\":\"10:22:1001:1002\"}";
    }
    @Test void preservesSeparateTerminalAndFileIdentityAndAbsoluteCwd() throws Exception {
        SshTerminalContextProbe.Context c=SshTerminalContextProbe.parse("login banner\n"+valid("/srv/path with space"));
        assertTrue(c.isAvailable()); assertEquals("/srv/path with space",c.getCwd());
        assertEquals("app",c.getTerminalUser()); assertEquals("root",c.getFileUser());
        assertEquals(Long.valueOf(1001),c.getTerminalUid()); assertEquals(Long.valueOf(0),c.getFileUid());
    }
    @Test void rejectsRelativePathsMissingUsersAndInvalidIdentifiers() {
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse(valid("relative")));
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse(valid("/tmp").replace("1001","-1")));
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse(valid("/tmp").replace("1001","2147483648")));
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse(valid("/tmp").replace("\"app\"","null")));
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse("{}"));
        assertThrows(IOException.class,()->SshTerminalContextProbe.parse("garbage"));
    }
    @Test void unavailableContextCannotPublishAPath() throws Exception {
        SshTerminalContextProbe.Context c=SshTerminalContextProbe.parse("{\"available\":false,\"status\":\"unsupported\",\"message\":\"no proc\",\"cwd\":\"/wrong\"}");
        assertFalse(c.isAvailable()); assertNull(c.getCwd()); assertEquals("no proc",c.getMessage());
    }
    @Test void tmuxTargetsOnlyItsActivePaneAndTheProbeDoesNotInjectTerminalInput() throws Exception {
        SshTmuxSession tmux=new SshTmuxSession((command,timeout)->{SshRemoteExec.ExecResult r=new SshRemoteExec.ExecResult();r.setExitStatus(0);return r;});
        assertThrows(IOException.class,tmux::contextPidCommand);
        tmux.prepare("same session'quoted",null,80,24,false);
        String pid=tmux.contextPidCommand();
        assertTrue(pid.contains("#{pane_active}|#{pane_pid}")); assertTrue(pid.contains(SshTmuxSession.quote("same session'quoted:")));
        String command=SshTerminalContextProbe.command(pid);
        assertTrue(command.contains("case \"$pid\"")); assertTrue(command.contains("python3"));
        assertFalse(command.contains("send-keys")); assertFalse(command.contains("sudo"+" -n"));
        assertTrue(SshTerminalContextProbe.command(null).startsWith("pid=''"));
    }
}
