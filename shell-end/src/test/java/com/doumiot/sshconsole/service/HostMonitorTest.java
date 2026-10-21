package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.doumiot.sshconsole.service.SshHostMonitorService.*;

class HostMonitorTest {
    @Test void parsesNvidiaMetricsWhenAvailable() {
        List<SshHostMonitorService.GpuBlock> gpus = SshHostMonitorService.parseGpus(
                "0, NVIDIA RTX A6000, 42, 8192, 49152, 61\n1, NVIDIA RTX A6000, 17, 4096, 49152, 54");
        assertEquals(2, gpus.size());
        assertEquals("NVIDIA RTX A6000", gpus.get(0).getName());
        assertEquals(Double.valueOf(42), gpus.get(0).getUtilizationPercent());
        assertEquals(Double.valueOf(61), gpus.get(0).getTemperatureC());
        assertTrue(SshHostMonitorService.parseGpus("").isEmpty());
    }
    static String net(long rx,long tx) {
        return "eth0: "+rx+" 99 2 3 0 0 0 0 "+tx+" 888 4 5 0 0 0 0\n"
                +"docker0: 99999 1 0 0 0 0 0 0 99999 1 0 0 0 0 0 0\n";
    }
    static String light(String uptime,long rx,long tx) {
        return "__M1__\ncpu 100 0 50 800 50 0 0 0 20 0\ncpu0 100 0 50 800\n"
                +"__M2__\nMemTotal: 2048 kB\nMemAvailable: 0 kB\nMemFree: 1024 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n"
                +"__M3__\n"+net(rx,tx)+"__M4__\n1.2 2.3 3.4 1/99 12\n__M5__\n"+uptime;
    }
    static final String DISK="__D1__\nFilesystem 1024-blocks Used Available Capacity Mounted on\n"
            +"overlay 1048576 524288 419430 56% /\n/dev/sdb 2097152 100000 1900000 6% /data space\n"
            +"tmpfs 100 0 100 0% /run\n__D2__\nFilesystem Inodes IUsed IFree IUse% Mounted on\n"
            +"overlay 1000 900 100 90% /\n/dev/sdb 2000 100 1900 5% /data space\n";

    @Test void cpuExcludesGuestAndSeparatesIowaitAndWarmsUpOnCounterReset() {
        SampleState s=new SampleState();
        assertNull(parseCpu("cpu 100 0 50 800 50 0 0 0 20 0\ncpu0 1 0 0 0",s).getUsagePercent());
        CpuBlock cpu=parseCpu("cpu 120 0 60 850 70 0 0 0 40 0\ncpu0 1 0 0 0",s);
        assertEquals(30.0,cpu.getUsagePercent());assertEquals(20.0,cpu.getIowaitPercent());assertEquals(1,cpu.getCores());
        assertNull(parseCpu("cpu 1 0 0 1 0 0 0 0\ncpu0 1 0 0 0",s).getUsagePercent());
    }
    @Test void networkUsesTransmitBytesAndElapsedTimeAndExcludesBridgeFromDefault() {
        SampleState s=new SampleState();
        assertNull(parseNetwork(net(1000,2000),s,10).getTxBytesPerSec());
        NetworkBlock n=parseNetwork(net(3000,8000),s,12);
        assertEquals(1000L,n.getRxBytesPerSec());assertEquals(3000L,n.getTxBytesPerSec());
        assertFalse(n.getInterfaces().get(0).isIncludedByDefault());
        NetIface eth=n.getInterfaces().get(1);assertEquals(4L,eth.getTxErrors());assertEquals(5L,eth.getTxDropped());
        assertNull(parseNetwork(net(1,1),s,13).getRxBytesPerSec());
    }
    @Test void memoryZeroAvailableIsRealAndSwapCanBeDisabled() {
        HostMetricsSnapshot snap=parseLight(light("100 0",100,100),new SampleState(),123);
        assertEquals(100.0,snap.getMemory().getUsagePercent());assertEquals(0.0,snap.getMemory().getAvailableMb());
        assertFalse(snap.getSwap().isEnabled());assertNull(snap.getSwap().getUsagePercent());
        assertEquals(1.2,snap.getCpu().getLoad1());assertEquals(123L,snap.getTimestamp());
    }
    @Test void rateResetsWhenRemoteClockDisappearsOrReboots() {
        SampleState s=new SampleState();parseLight(light("100 0",100,100),s,1);
        assertNotNull(parseLight(light("102 0",200,200),s,2).getNetwork().getRxBytesPerSec());
        assertNull(parseLight(light("",300,300),s,3).getNetwork().getRxBytesPerSec());
        assertNull(parseLight(light("104 0",400,400),s,4).getNetwork().getRxBytesPerSec());
        assertNull(parseLight(light("1 0",1,1),s,5).getNetwork().getRxBytesPerSec());
    }
    @Test void diskKeepsOverlayRootAndMountSpacesAndDfCapacityAndInodes() {
        List<DiskBlock> disks=parseDisks(DISK);assertEquals(2,disks.size());
        assertEquals("/",disks.get(0).getMount());assertEquals(56.0,disks.get(0).getUsagePercent());
        assertEquals(90.0,disks.get(0).getInodeUsagePercent());assertEquals(100L,disks.get(0).getInodesAvailable());
        assertEquals("/data space",disks.get(1).getMount());assertEquals(5.0,disks.get(1).getInodeUsagePercent());
    }
    @Test void concurrentRequestsShareCollectionAndDiskFailureKeepsPreviousTimestamp() throws Exception {
        SshHostMonitorService service=new SshHostMonitorService();SshConsoleProperties props=new SshConsoleProperties();
        SshSessionRegistry registry=mock(SshSessionRegistry.class);SSHClient client=mock(SSHClient.class);
        SshSessionRegistry.SessionEntry entry=new SshSessionRegistry.SessionEntry();entry.setClient(client);
        when(registry.get("test")).thenReturn(entry);when(client.isConnected()).thenReturn(true);
        when(client.startSession()).thenAnswer(invocation->{
            Session channel=mock(Session.class);
            when(channel.exec(anyString())).thenAnswer(call->{
                Session.Command cmd=mock(Session.Command.class);String command=call.getArgument(0);
                String out=command.contains("__M1__")?light("100 0",100,100):DISK;
                when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)));
                when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));when(cmd.getExitStatus()).thenReturn(0);
                return cmd;
            });return channel;
        });
        ReflectionTestUtils.setField(service,"sessionRegistry",registry);ReflectionTestUtils.setField(service,"properties",props);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<HostMetricsSnapshot> a=pool.submit(()->service.sample("test")),b=pool.submit(()->service.sample("test"));
            HostMetricsSnapshot one=a.get(3,TimeUnit.SECONDS),two=b.get(3,TimeUnit.SECONDS);
            assertEquals(one.getTimestamp(),two.getTimestamp());assertEquals(one.getDiskTimestamp(),two.getDiskTimestamp());
            assertNotEquals(one.isThrottled(),two.isThrottled());verify(client,times(2)).startSession();
            java.util.Map<?,?> states=(java.util.Map<?,?>)ReflectionTestUtils.getField(service,"states");SampleState state=(SampleState)states.get("test");
            state.lastRemoteTick=0;state.lastDiskAttemptTick=0;
            Session channel=mock(Session.class);Session.Command cmd=mock(Session.Command.class);
            when(client.startSession()).thenReturn(channel);when(channel.exec(contains("__M1__"))).thenReturn(cmd);
            when(cmd.getInputStream()).thenReturn(new ByteArrayInputStream(light("105 0",200,200).getBytes(StandardCharsets.UTF_8)));
            when(cmd.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));when(cmd.getExitStatus()).thenReturn(0);
            when(channel.exec(contains("__D1__"))).thenThrow(new net.schmizz.sshj.connection.ConnectionException("disk unavailable"));
            HostMetricsSnapshot partial=service.sample("test");
            assertEquals("PARTIAL",partial.getStatus());assertEquals(one.getDiskTimestamp(),partial.getDiskTimestamp());
            assertEquals(2,partial.getDisks().size());assertNotNull(partial.getNetwork().getRxBytesPerSec());
        } finally {pool.shutdownNow();}
    }
}
