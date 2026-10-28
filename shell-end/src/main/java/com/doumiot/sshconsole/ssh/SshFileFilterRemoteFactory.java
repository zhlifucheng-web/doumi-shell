package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Ships a bundled read-only helper, without installing files or touching the user's terminal. */
@Component
@SshConsoleEnabled
public class SshFileFilterRemoteFactory {
    private final SshSessionRegistry registry;
    private final Set<SshFileFilterRemoteCursor> cursors=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Clock clock=Clock.systemUTC();
    private final String command;
    public SshFileFilterRemoteFactory(SshSessionRegistry registry) throws IOException {
        this.registry=registry;
        try(InputStream input=getClass().getResourceAsStream("/ssh/file-filter.py")) {
            if(input==null) throw new IOException("缺少远端筛选程序");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
            String source=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            String script=" -u -c '"+source.replace("'","'\"'\"'")+"'";
            command="if command -v python3 >/dev/null 2>&1; then exec python3"+script
                    +"; elif command -v python >/dev/null 2>&1; then exec python"+script
                    +"; else printf '%s\\n' '{\"type\":\"unsupported\"}'; fi";
        }
    }
    /** null means no Python/exec support: the caller explicitly reports SFTP compatibility mode. */
    public SshFileFilterRemoteCursor open(String token,String path,String keyword,String encoding,boolean caseSensitive,int context) throws IOException {
        cursors.removeIf(SshFileFilterRemoteCursor::isClosed);
        SshSessionRegistry.SessionEntry entry=registry.get(token);
        final Session session;
        final Session.Command process;
        session=entry.getClient().startSession();
        try { process=session.exec(command); }
        catch(net.schmizz.sshj.connection.ConnectionException denied) {session.close();return null;}
        catch(IOException | RuntimeException error) {session.close();throw error;}
        SshFileFilterRemoteCursor cursor=new SshFileFilterRemoteCursor(process.getInputStream(),process.getOutputStream(),()->{
            // Closing stdin wakes an idle helper. TERM also stops one blocked in a filesystem read.
            try {process.getOutputStream().close();} catch(IOException ignored) {}
            try {process.signal(Signal.TERM);} catch(IOException ignored) {}
            try {process.close();} finally {session.close();}
        },clock);
        cursors.add(cursor);
        Map<String,Object> config=new LinkedHashMap<>();config.put("path",path);config.put("keyword",keyword);
        config.put("encoding",encoding==null || encoding.isEmpty()?"UTF-8":encoding);config.put("caseSensitive",caseSensitive);config.put("contextLines",context);
        try {
            if(cursor.initialize(config)) return cursor;
            cursors.remove(cursor);return null;
        } catch(IOException | RuntimeException error) {cursors.remove(cursor);cursor.close();throw error;}
    }
    /** At most 32 service-owned cursors; no read futures, thread-per-scan, or unbounded work queue. */
    @Scheduled(fixedDelay=1000)
    public void expireBlockedReads() {for(SshFileFilterRemoteCursor cursor:cursors) {cursor.expire(clock.millis());if(cursor.isClosed())cursors.remove(cursor);}}
    @PreDestroy public void closeAll() {for(SshFileFilterRemoteCursor cursor:cursors)try{cursor.close();}catch(IOException ignored){}cursors.clear();}
}
