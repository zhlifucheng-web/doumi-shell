package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.*;
import com.doumiot.sshconsole.ssh.*;
import lombok.Data;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 无常驻远端代理；快速指标与低频磁盘采集分开记录时间和错误。 */
@Service @SshConsoleEnabled
public class SshHostMonitorService {
    private static final String LIGHT_SAMPLE_CMD = "echo __M1__;awk '/^cpu/{print}' /proc/stat 2>/dev/null;"
            + "echo __M2__;cat /proc/meminfo 2>/dev/null;echo __M3__;cat /proc/net/dev 2>/dev/null;"
            + "echo __M4__;cat /proc/loadavg 2>/dev/null;echo __M5__;cat /proc/uptime 2>/dev/null";
    private static final String DF_CMD = "echo __D1__;LC_ALL=C df -kP 2>/dev/null;echo __D2__;LC_ALL=C df -Pi 2>/dev/null";
    @Resource private SshSessionRegistry sessionRegistry;
    @Resource private SshConsoleProperties properties;
    private final Map<String,SampleState> states = new ConcurrentHashMap<>();

    public HostMetricsSnapshot sample(String sessionToken) throws IOException {
        int interval=Math.max(3000,properties.getMonitorMinIntervalMs());
        if(!properties.isMonitorEnabled()) {
            HostMetricsSnapshot off=new HostMetricsSnapshot();off.setStatus("DISABLED");off.setPollIntervalMs(interval);return off;
        }
        SshSessionRegistry.SessionEntry entry=sessionRegistry.get(sessionToken);
        SampleState state=states.computeIfAbsent(sessionToken,key->new SampleState());
        // 限频检查和更新属于同一次临界区，多标签并发请求不会排队重复采集。
        synchronized(state) {
            long tick=System.nanoTime();
            if(state.cachedSnapshot!=null && tick-state.lastRemoteTick<interval*1000000L) {
                HostMetricsSnapshot copy=copy(state.cachedSnapshot);copy.setThrottled(true);return copy;
            }
            SshRemoteExec.ExecResult light=SshRemoteExec.exec(entry.getClient(),entry.getExecLock(),LIGHT_SAMPLE_CMD,8);
            if(light.getExitStatus()!=0)throw new IOException("主机指标采集失败："+light.getStderr());
            HostMetricsSnapshot snapshot=parseLight(light.getStdout(),state,System.currentTimeMillis());
            snapshot.setPollIntervalMs(interval);
            long diskInterval=Math.max(interval,properties.getMonitorDiskIntervalMs())*1000000L;
            if(state.lastDiskAttemptTick==0 || tick-state.lastDiskAttemptTick>=diskInterval) {
                try {
                    SshRemoteExec.ExecResult disk=SshRemoteExec.exec(entry.getClient(),entry.getExecLock(),DF_CMD,12);
                    List<DiskBlock> parsed=parseDisks(disk.getStdout());
                    if(disk.getExitStatus()!=0 || parsed.isEmpty())throw new IOException("磁盘容量暂不可用");
                    state.disks=parsed;state.diskTimestamp=System.currentTimeMillis();state.diskError=null;
                    if(parsed.stream().anyMatch(d->d.getInodeUsagePercent()==null))state.diskError="部分文件系统不提供 inode 指标";
                }catch(IOException e) {state.diskError=e.getMessage();}
                state.lastDiskAttemptTick=System.nanoTime();
            }
            snapshot.setDisks(state.disks);snapshot.setDiskTimestamp(state.diskTimestamp);
            if(state.diskError!=null)snapshot.getErrors().put("disk",state.diskError);
            snapshot.setStatus(!snapshot.getErrors().isEmpty()?"PARTIAL":snapshot.getCpu().getUsagePercent()==null?"WARMING_UP":"READY");
            state.cachedSnapshot=snapshot;state.lastRemoteTick=System.nanoTime();return snapshot;
        }
    }

    public void clearState(String token) {states.remove(token);}

    static HostMetricsSnapshot parseLight(String text,SampleState state,long timestamp) {
        HostMetricsSnapshot snap=new HostMetricsSnapshot();snap.setTimestamp(timestamp);
        Double uptime=number(section(text,"__M5__",null).split("\\s+")[0]);
        if(uptime!=null && state.lastUptime!=null && uptime<state.lastUptime) {
            state.lastCpuTotal=-1;state.interfaces.clear();state.lastNetSeconds=null;
        }
        snap.setUptimeSeconds(uptime);state.lastUptime=uptime;
        snap.setCpu(parseCpu(section(text,"__M1__","__M2__"),state));
        String[] load=section(text,"__M4__","__M5__").split("\\s+");
        if(load.length>=3) {snap.getCpu().setLoad1(number(load[0]));snap.getCpu().setLoad5(number(load[1]));snap.getCpu().setLoad15(number(load[2]));}
        if(snap.getCpu().getCores()==null)snap.getErrors().put("cpu","CPU 信息不可用");
        if(snap.getCpu().getLoad1()==null)snap.getErrors().put("load","系统负载不可用");
        parseMemory(section(text,"__M2__","__M3__"),snap);
        // /proc/uptime 是远端单调时间，避免网络延迟或本机时钟跳变扭曲速率。
        boolean remoteClock=uptime!=null;
        if(state.remoteClock!=null && state.remoteClock!=remoteClock){state.interfaces.clear();state.lastNetSeconds=null;}
        state.remoteClock=remoteClock;
        double seconds=uptime==null?System.nanoTime()/1000000000.0:uptime;
        snap.setNetwork(parseNetwork(section(text,"__M3__","__M4__"),state,seconds));
        if(snap.getNetwork().getInterfaces().isEmpty())snap.getErrors().put("network","未读取到可用网卡");
        return snap;
    }

    static CpuBlock parseCpu(String text,SampleState state) {
        CpuBlock result=new CpuBlock();String summary=null;int cores=0;
        for(String raw:text.split("\n")) {String line=raw.trim();if(line.startsWith("cpu "))summary=line;else if(line.matches("cpu\\d+\\s+.*"))cores++;}
        if(summary==null)return result;
        String[] values=summary.split("\\s+");if(values.length<5)return result;
        long total=0;
        // guest/guest_nice 已计入 user/nice，不可再次相加。
        for(int i=1;i<Math.min(values.length,9);i++){Long n=integer(values[i]);if(n==null||n<0)return result;total+=n;}
        long idle=Long.parseLong(values[4]),io=values.length>5?Long.parseLong(values[5]):0;
        result.setCores(cores>0?cores:null);
        long delta=total-state.lastCpuTotal,dIdle=idle-state.lastCpuIdle,dIo=io-state.lastCpuIo;
        if(state.lastCpuTotal>=0 && delta>0 && dIdle>=0 && dIo>=0 && dIdle+dIo<=delta) {
            result.setUsagePercent(percent((delta-dIdle-dIo)*100.0/delta));
            result.setIowaitPercent(percent(dIo*100.0/delta));
        }
        state.lastCpuTotal=total;state.lastCpuIdle=idle;state.lastCpuIo=io;return result;
    }

    private static void parseMemory(String text,HostMetricsSnapshot snapshot) {
        Map<String,Long> fields=new HashMap<>();
        for(String line:text.split("\n")){String[] p=line.trim().split("\\s+");if(p.length>=2){Long value=integer(p[1]);if(value!=null)fields.put(p[0].replace(":",""),value);}}
        Long total=fields.get("MemTotal");
        if(total==null||total<=0){snapshot.getErrors().put("memory","内存信息不可用");return;}
        long available=fields.containsKey("MemAvailable")?fields.get("MemAvailable"):
                fields.getOrDefault("MemFree",0L)+fields.getOrDefault("Buffers",0L)+fields.getOrDefault("Cached",0L)
                        +fields.getOrDefault("SReclaimable",0L)-fields.getOrDefault("Shmem",0L);
        available=Math.max(0,Math.min(total,available));
        MemoryBlock memory=new MemoryBlock();memory.setTotalMb(mb(total));memory.setAvailableMb(mb(available));memory.setUsedMb(mb(total-available));memory.setUsagePercent(percent((total-available)*100.0/total));snapshot.setMemory(memory);
        Long swapTotal=fields.get("SwapTotal"),swapFree=fields.get("SwapFree");
        if(swapTotal!=null&&swapFree!=null&&swapTotal>=0){
            SwapBlock swap=new SwapBlock();swap.setEnabled(swapTotal>0);swap.setTotalMb(mb(swapTotal));swap.setUsedMb(mb(Math.max(0,swapTotal-swapFree)));
            if(swapTotal>0)swap.setUsagePercent(percent(Math.max(0,swapTotal-swapFree)*100.0/swapTotal));snapshot.setSwap(swap);
        }else snapshot.getErrors().put("swap","交换分区信息不可用");
    }

    static NetworkBlock parseNetwork(String text,SampleState state,double seconds) {
        NetworkBlock result=new NetworkBlock();List<NetIface> interfaces=new ArrayList<>();Map<String,long[]> current=new HashMap<>();
        double elapsed=state.lastNetSeconds==null?0:seconds-state.lastNetSeconds;
        for(String raw:text.split("\n")) {
            int colon=raw.lastIndexOf(':');if(colon<0)continue;
            String name=raw.substring(0,colon).trim();if("lo".equals(name))continue;
            String[] columns=raw.substring(colon+1).trim().split("\\s+");if(columns.length<16)continue;
            Long rx=integer(columns[0]),tx=integer(columns[8]);if(rx==null||tx==null||rx<0||tx<0)continue;
            NetIface iface=new NetIface();iface.setName(name);iface.setRxBytes(rx);iface.setTxBytes(tx);
            iface.setRxErrors(integer(columns[2]));iface.setRxDropped(integer(columns[3]));iface.setTxErrors(integer(columns[10]));iface.setTxDropped(integer(columns[11]));
            iface.setIncludedByDefault(!name.matches("^(docker.*|veth.*|br-.*|virbr.*|cni.*|flannel.*)$"));
            long[] previous=state.interfaces.get(name);
            if(previous!=null&&elapsed>0&&rx>=previous[0]&&tx>=previous[1]){
                iface.setRxBytesPerSec((long)((rx-previous[0])/elapsed));iface.setTxBytesPerSec((long)((tx-previous[1])/elapsed));
            }
            interfaces.add(iface);current.put(name,new long[]{rx,tx});
        }
        interfaces.sort(Comparator.comparing(NetIface::getName));result.setInterfaces(interfaces);
        List<NetIface> selected=new ArrayList<>();for(NetIface iface:interfaces)if(iface.isIncludedByDefault())selected.add(iface);
        if(!selected.isEmpty()&&selected.stream().allMatch(i->i.getRxBytesPerSec()!=null)) {
            result.setRxBytesPerSec(selected.stream().mapToLong(NetIface::getRxBytesPerSec).sum());result.setTxBytesPerSec(selected.stream().mapToLong(NetIface::getTxBytesPerSec).sum());
        }
        state.interfaces=current;state.lastNetSeconds=seconds;return result;
    }

    static List<DiskBlock> parseDisks(String text) {
        Map<String,DiskBlock> disks=new LinkedHashMap<>();
        for(String line:section(text,"__D1__","__D2__").split("\n")) {
            String[] p=line.trim().split("\\s+",6);if(p.length<6||"tmpfs".equals(p[0])||"devtmpfs".equals(p[0]))continue;
            Long total=integer(p[1]),used=integer(p[2]),available=integer(p[3]);if(total==null||total<=0||used==null||available==null)continue;
            DiskBlock disk=new DiskBlock();disk.setFilesystem(p[0]);disk.setMount(unescapeMount(p[5]));
            disk.setTotalGb(gb(total));disk.setUsedGb(gb(used));disk.setAvailableGb(gb(available));disk.setUsagePercent(parsePercent(p[4]));disks.put(p[0]+"|"+disk.getMount(),disk);
        }
        for(String line:section(text,"__D2__",null).split("\n")) {
            String[] p=line.trim().split("\\s+",6);if(p.length<6)continue;
            DiskBlock disk=disks.get(p[0]+"|"+unescapeMount(p[5]));if(disk==null)continue;
            disk.setInodesTotal(integer(p[1]));disk.setInodesAvailable(integer(p[3]));disk.setInodeUsagePercent(parsePercent(p[4]));
        }
        return new ArrayList<>(disks.values());
    }

    private static String section(String text,String from,String to) {
        if(text==null)return "";int start=text.indexOf(from);if(start<0)return "";start+=from.length();
        int end=to==null?-1:text.indexOf(to,start);return text.substring(start,end<0?text.length():end).trim();
    }
    private static String unescapeMount(String mount){return mount.replace("\\040"," ").replace("\\011","\t").replace("\\134","\\");}
    private static Long integer(String value){try{return Long.valueOf(value);}catch(Exception e){return null;}}
    private static Double number(String value){try{double n=Double.parseDouble(value);return Double.isFinite(n)&&n>=0?n:null;}catch(Exception e){return null;}}
    private static Double parsePercent(String value){Double number=number(value.replace("%",""));return number==null?null:percent(number);}
    private static double percent(double n){return Math.round(Math.min(100,Math.max(0,n))*10.0)/10.0;}
    private static double mb(long kb){return Math.round(kb/1024.0*10)/10.0;}
    private static double gb(long kb){return Math.round(kb/(1024.0*1024)*100)/100.0;}
    private static HostMetricsSnapshot copy(HostMetricsSnapshot source){
        HostMetricsSnapshot copy=new HostMetricsSnapshot();copy.setTimestamp(source.getTimestamp());copy.setDiskTimestamp(source.getDiskTimestamp());copy.setPollIntervalMs(source.getPollIntervalMs());copy.setStatus(source.getStatus());copy.setErrors(source.getErrors());
        copy.setCpu(source.getCpu());copy.setMemory(source.getMemory());copy.setSwap(source.getSwap());copy.setNetwork(source.getNetwork());copy.setDisks(source.getDisks());copy.setUptimeSeconds(source.getUptimeSeconds());return copy;
    }

    static class SampleState {
        long lastRemoteTick,lastDiskAttemptTick,lastCpuTotal=-1,lastCpuIdle,lastCpuIo;
        Boolean remoteClock;Double lastUptime,lastNetSeconds;Map<String,long[]> interfaces=new HashMap<>();
        List<DiskBlock> disks;Long diskTimestamp;String diskError;HostMetricsSnapshot cachedSnapshot;
    }
    @Data public static class HostMetricsSnapshot {
        private Long timestamp,diskTimestamp;private int pollIntervalMs;private boolean throttled;
        private String status;private Map<String,String> errors=new LinkedHashMap<>();private Double uptimeSeconds;
        private CpuBlock cpu;private MemoryBlock memory;private SwapBlock swap;private NetworkBlock network;private List<DiskBlock> disks;
    }
    @Data public static class CpuBlock {private Double usagePercent,iowaitPercent,load1,load5,load15;private Integer cores;}
    @Data public static class MemoryBlock {private Double totalMb,usedMb,availableMb,usagePercent;}
    @Data public static class SwapBlock {private boolean enabled;private Double totalMb,usedMb,usagePercent;}
    @Data public static class NetworkBlock {private Long rxBytesPerSec,txBytesPerSec;private List<NetIface> interfaces=new ArrayList<>();}
    @Data public static class NetIface {
        private String name;private Long rxBytes,txBytes,rxBytesPerSec,txBytesPerSec,rxErrors,txErrors,rxDropped,txDropped;private boolean includedByDefault;
    }
    @Data public static class DiskBlock {private String filesystem,mount;private Double totalGb,usedGb,availableGb,usagePercent,inodeUsagePercent;private Long inodesTotal,inodesAvailable;}
}
