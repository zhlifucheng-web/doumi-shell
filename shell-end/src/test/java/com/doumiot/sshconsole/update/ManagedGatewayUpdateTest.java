package com.doumiot.sshconsole.update;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedGatewayUpdateTest {
    @Test void managedRestartWaitsForOwningLauncherAndRestartsIt() {
        assertEquals(123,UpdateService.managedLauncherPid("docker","123",123));
        assertEquals(123,UpdateService.managedLauncherPid("native","123",123));
        for(String mode:new String[]{"external","", "invalid"})assertEquals(-1,UpdateService.managedLauncherPid(mode,"123",123));
        assertEquals(-1,UpdateService.managedLauncherPid("docker","456",123));
        assertEquals(-1,UpdateService.managedLauncherPid("docker","bad",123));
        List<String> java=Arrays.asList("/jdk/bin/java","-jar","/install dir/doumi-shell.jar");
        assertEquals(Arrays.asList("bash",Path.of("/install dir/run-linux.sh").toString()),UpdateService.restartCommand(Path.of("/install dir"),java,true));
        assertEquals(java,UpdateService.restartCommand(Path.of("/install dir"),java,false));
    }
}
