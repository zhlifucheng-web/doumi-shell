package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.ssh.*;
import lombok.Data;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, read-only GNU/Linux scan; no symlink traversal or filesystem crossing. */
@Service @SshConsoleEnabled
public class SshDiskUsageService {
    @Resource private SshSessionRegistry sessions;
    private final Set<String> running=ConcurrentHashMap.newKeySet();

    public ScanResult scan(String token,String path,int minimumMb) throws IOException {
        if(minimumMb<1||minimumMb>1048576)throw new IllegalArgumentException("文件下限应为 1–1048576 MiB");
        if(path!=null&&(path.length()>4096||path.indexOf('\0')>=0))throw new IllegalArgumentException("无效的扫描路径");
        SshSessionRegistry.SessionEntry entry=sessions.get(token);
        if(!running.add(token))throw new IOException("此会话已有目录扫描，请等待完成");
        try {
            // Separate channel/lock keeps the monitor and terminal responsive.
            SshRemoteExec.ExecResult result=SshRemoteExec.exec(entry.getClient(),new Object(),command(path,minimumMb),95);
            if(result.getExitStatus()==124||result.getExitStatus()==137)throw new IOException("扫描超过 85 秒，请缩小目录范围后重试");
            if(result.getExitStatus()!=0)throw new IOException("扫描失败："+result.getStderr());
            return parse(result.getStdout(),result.getStderr());
        } finally {running.remove(token);}
    }

    static String quote(String text){return "'"+text.replace("'","'\"'\"'")+"'";}
    static String command(String path,int minimumMb) {
        String script="p="+quote(path==null?"":path)+"; "
                +"case \"$p\" in ''|'~') p=$HOME;; '~/'*) p=$HOME/${p#\\~/};; esac; "
                +"cd -- \"$p\" || exit 2; p=$(pwd -P) || exit 2; "
                +"work=$(mktemp -d) || exit 2; trap 'rm -rf -- \"$work\"' EXIT; trap 'exit 124' HUP INT TERM; "
                +"printf '__PATH__\\000%s\\000__DIRS__\\000' \"$p\"; "
                +"LC_ALL=C du -x -B1 --max-depth=1 --null --exclude=\"$work\" -- \"$p\" >\"$work/dirs\"; d=$?; "
                +"LC_ALL=C sort -z -nr \"$work/dirs\" >\"$work/sorted-dirs\" || exit 3; "
                // Always retain the root record, even when hundreds of zero-byte children tie with it.
                +"ROOT_PATH=\"$p\" LC_ALL=C awk 'BEGIN { RS=\"\\0\"; ORS=\"\\0\" } "
                +"{ path=substr($0,index($0,\"\\t\")+1); if(path==ENVIRON[\"ROOT_PATH\"] || n++<200) print }' \"$work/sorted-dirs\" || exit 3; "
                +"printf '__FILES__\\000'; "
                +"LC_ALL=C find \"$p\" -xdev -mindepth 1 -maxdepth 1 -path \"$work\" -prune -o -type f -size +"+((long)minimumMb*1024*1024-1)+"c -printf '%s\\t%p\\0' >\"$work/files\"; f=$?; "
                +"LC_ALL=C sort -z -nr \"$work/files\" >\"$work/sorted-files\" || exit 3; "
                +"LC_ALL=C awk 'BEGIN { RS=\"\\0\"; ORS=\"\\0\" } NR<=200 { print }' \"$work/sorted-files\" || exit 3; "
                +"printf '__STATUS__\\000%s %s\\000__END__\\000' \"$d\" \"$f\";";
        return "command -v timeout >/dev/null 2>&1 || { echo '需要 GNU timeout/coreutils/findutils' >&2; exit 2; }; "
                +"for tool in du find sort awk mktemp; do command -v \"$tool\" >/dev/null 2>&1 || { echo \"缺少扫描工具：$tool\" >&2; exit 2; }; done; "
                +"printf 'a\\000b\\000' | awk 'BEGIN { RS=\"\\0\" } END { exit (NR!=2) }' || { echo 'awk 不支持 NUL 分隔记录，需要 gawk 或 mawk' >&2; exit 2; }; "
                +"timeout -k 3s 85s sh -c "+quote(script);
    }

    static ScanResult parse(String output,String stderr) throws IOException {
        String[] fields=output.split("\u0000",-1);ScanResult result=new ScanResult();String mode="";boolean end=false;
        for(int i=0;i<fields.length;i++) {
            String field=fields[i];
            if("__PATH__".equals(field)){if(++i<fields.length)result.setPath(fields[i]);continue;}
            if("__DIRS__".equals(field)||"__FILES__".equals(field)||"__STATUS__".equals(field)){mode=field;continue;}
            if("__END__".equals(field)){end=true;break;}
            if("__STATUS__".equals(mode)){if(!"0 0".equals(field))result.setPartial(true);continue;}
            int tab=field.indexOf('\t');if(tab<0)continue;
            try {
                long bytes=Long.parseLong(field.substring(0,tab));if(bytes<0)continue;
                String path=field.substring(tab+1);
                if("__DIRS__".equals(mode)&&Objects.equals(path,result.getPath())){result.setTotalBytes(bytes);continue;}
                String base="/".equals(result.getPath())?"/":result.getPath()+"/";
                if(!path.startsWith(base)||path.substring(base.length()).contains("/"))continue;
                UsageRow row=new UsageRow();row.setPath(path);row.setBytes(bytes);row.setDirectory("__DIRS__".equals(mode));
                if(row.isDirectory())result.getDirectories().add(row);else result.getFiles().add(row);
            }catch(NumberFormatException ignored){result.setPartial(true);}
        }
        if(!end||result.getPath()==null||result.getTotalBytes()==null) {
            String reason=!end?"扫描输出中断或超过输出上限":"未获得扫描目录的总大小";
            String detail=stderr==null?"":stderr.trim();
            throw new IOException(reason+(detail.isEmpty()?"，请确认目录可读取且服务器支持 GNU du/find/sort": "："+detail.substring(0,Math.min(detail.length(),2000))));
        }
        if(stderr!=null&&!stderr.trim().isEmpty()){result.setPartial(true);result.setWarning(stderr.substring(0,Math.min(stderr.length(),2000)));}
        result.setLimited(result.getFiles().size()>=200||result.getDirectories().size()>=200);
        result.getDirectories().sort(Comparator.comparingLong(UsageRow::getBytes).reversed());
        result.getFiles().sort(Comparator.comparingLong(UsageRow::getBytes).reversed());
        result.setTimestamp(System.currentTimeMillis());return result;
    }
    @Data public static class ScanResult {
        private String path,warning;private Long totalBytes;private long timestamp;private boolean partial,limited;
        private List<UsageRow> directories=new ArrayList<>(),files=new ArrayList<>();
    }
    @Data public static class UsageRow {private String path;private long bytes;private boolean directory;}
}
