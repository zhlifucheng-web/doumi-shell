package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.sftp.FileTailRecoveryException;
import com.doumiot.sshconsole.ssh.SshFileFilterRemoteFactory;
import com.doumiot.sshconsole.ssh.SshFileFilterRemoteCursor;
import javax.annotation.PreDestroy;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.sftp.SshFileFilterScanner;
import lombok.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Session-owned bounded scanners; remote mode retains one demand-driven SSH helper/FD. */
@Service
@SshConsoleEnabled
public class SshFileFilterService {
    private static final int READ_BUDGET = 256 * 1024;
    private static final int ANCHOR_BYTES = 4096;
    private static final int MAX_SCANS = 32;
    private static final long IDLE_MS = 10 * 60 * 1000L;
    private final Map<String, Scan> scans = new ConcurrentHashMap<>();
    @Resource private SshSftpService files;
    @Resource private SshFileFilterRemoteFactory remoteScans;

    public synchronized Started start(String token, String path, String keyword, String encoding,
                                      boolean caseSensitive, int contextLines) throws IOException {
        cleanupExpired();
        if (scans.size() >= MAX_SCANS) throw new IOException("筛选任务过多，请取消旧任务后重试");
        final SshFileFilterScanner scanner;
        try { scanner = new SshFileFilterScanner(keyword, encoding, caseSensitive, contextLines); }
        catch (IllegalArgumentException error) { throw new IOException("筛选参数无效: " + error.getMessage(), error); }
        final Scan scan;
        try (SshSftpService.ReadFileHandle handle = files.openReadHandle(token, path)) {
            SshSftpService.ReadSnapshot snapshot = handle.snapshot();
            scan = new Scan(token, handle.path(), scanner, snapshot.getSize(), snapshot.getMtime());
        }
        scan.remote = remoteScans.open(token, scan.path, keyword, encoding, caseSensitive, contextLines);
        if (scan.remote != null) scan.lastSize = scan.remote.size();
        String id = UUID.randomUUID().toString(); scans.put(id, scan); return new Started(id, scan.remote != null ? "remote" : "sftp");
    }

    public Batch next(String token, String id) throws IOException {
        Scan scan = require(token, id);
        synchronized (scan) {
            checkActive(id, scan);
            if (scan.tailOwner != null) throw new IOException("筛选游标已由实时 Tail 独占，请先关闭 Tail");
            try {
                if (scan.remote != null) return read(scan, id, null);
                try (SshSftpService.ReadFileHandle handle = files.openReadHandle(token, scan.path)) { return read(scan, id, handle); }
            } catch (IOException | RuntimeException error) { discard(id, scan); throw error; }
            finally { scan.touched = System.currentTimeMillis(); }
        }
    }

    /** Transfer a historical-EOF scanner to a single retained-handle Tail. */
    public TailLease claimTail(String token, String id) throws IOException { return claimStream(token, id, false); }
    public TailLease claimStream(String token, String id, boolean historical) throws IOException {
        Scan scan = require(token, id);
        synchronized (scan) {
            checkActive(id, scan);
            if (scan.tailOwner != null || (!historical && (scan.offset != scan.lastSize || scan.limited)))
                throw new IOException("请等待历史筛选完成或关闭已有 Tail 后重试");
            TailLease lease = new TailLease(id, scan); scan.tailOwner = lease; return lease;
        }
    }
    public Batch nextTail(TailLease lease, SshSftpService.ReadFileHandle handle) throws IOException {
        Scan scan = lease.scan;
        synchronized (scan) {
            checkActive(lease.scanId, scan);
            if (scan.tailOwner != lease) throw new IOException("实时 Tail 已释放筛选游标");
            try { return read(scan, lease.scanId, handle); }
            catch (IOException | RuntimeException error) { discard(lease.scanId, scan); throw error; }
            finally { scan.touched = System.currentTimeMillis(); }
        }
    }
    public void releaseTail(TailLease lease) {
        if (lease == null) return;
        synchronized (lease.scan) {
            if (lease.scan.tailOwner == lease) { lease.scan.tailOwner = null; lease.scan.touched = System.currentTimeMillis(); }
        }
    }
    public static final class TailLease {
        private final String scanId;
        private final Scan scan;
        private TailLease(String id, Scan scan) { this.scanId=id; this.scan=scan; }
        public String path() { return scan.path; }
        public long offset() { synchronized (scan) { return scan.offset; } }
        public String scanId() { return scanId; }
        public boolean remote() { return scan.remote != null; }
    }
    private void checkActive(String id, Scan scan) throws IOException {
        if (scans.get(id) != scan) throw new IOException("筛选任务已取消或过期，请重新筛选");
    }
    private Batch read(Scan scan, String id, SshSftpService.ReadFileHandle handle) throws IOException {
        if (scan.remote != null) {
            Batch batch = scan.remote.next(id);
            checkActive(id, scan);
            scan.offset = batch.getOffset(); scan.lastSize = batch.getTotalSize(); scan.limited = batch.isLimited();
            return batch;
        }
        SshSftpService.ReadSnapshot snapshot = handle.snapshot();
        long size = snapshot.getSize(), mtime = snapshot.getMtime();
        if (size < scan.lastSize || (snapshot.isStable() && size == scan.lastSize && mtime != scan.mtime)) throw changed();
        int verified = verify(handle, 0, scan.prefix);
        verified += verify(handle, scan.offset - scan.boundary.length, scan.boundary);
        if (scan.scanner.isLimited()) return new Batch(id, Collections.emptyList(), scan.offset, size, scan.offset == size, true);
        int length = (int)Math.min(READ_BUDGET - verified, size - scan.offset);
        byte[] bytes = readExact(handle, scan.offset, length);
        boolean eof = scan.offset + length == size;
        if (handle.length() < size) throw changed();
        final List<SshFileFilterScanner.Row> rows;
        try { rows = scan.scanner.accept(bytes, eof); }
        catch (IOException | RuntimeException error) { scans.remove(id, scan); throw error; }
        if (scan.prefix.length < ANCHOR_BYTES) {
            int take = Math.min(ANCHOR_BYTES - scan.prefix.length, bytes.length);
            byte[] prefix = Arrays.copyOf(scan.prefix, scan.prefix.length + take);
            System.arraycopy(bytes, 0, prefix, scan.prefix.length, take); scan.prefix = prefix;
        }
        byte[] tail = new byte[scan.boundary.length + bytes.length];
        System.arraycopy(scan.boundary,0,tail,0,scan.boundary.length);
        System.arraycopy(bytes,0,tail,scan.boundary.length,bytes.length);
        scan.boundary = Arrays.copyOfRange(tail,Math.max(0,tail.length - ANCHOR_BYTES),tail.length);
        scan.offset += length; scan.lastSize = size; scan.mtime = mtime; scan.limited = scan.scanner.isLimited();
        return new Batch(id, rows, scan.offset, size, eof, scan.scanner.isLimited());
    }

    public void cancel(String token, String id) throws IOException {
        Scan scan = scans.get(id); if (scan == null) return;
        if (!scan.token.equals(token)) throw new IOException("筛选任务不属于当前会话");
        discard(id, scan); // Close outside the scan monitor to interrupt an in-flight remote read.
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        scans.forEach((id, scan) -> {
            if (scan.tailOwner == null && now - scan.touched >= IDLE_MS) discard(id, scan);
        });
    }

    private Scan require(String token, String id) throws IOException {
        Scan scan = id == null ? null : scans.get(id);
        if (scan == null || !scan.token.equals(token)) throw new IOException("筛选任务不存在或不属于当前会话");
        if (scan.tailOwner == null && System.currentTimeMillis() - scan.touched >= IDLE_MS) {
            discard(id, scan); throw new IOException("筛选任务已过期，请重新筛选");
        }
        return scan;
    }
    private void discard(String id, Scan scan) {
        scans.remove(id, scan);
        if (scan.remote != null) try { scan.remote.close(); } catch (IOException ignored) {}
    }
    @PreDestroy public void closeAll() { scans.forEach(this::discard); }
    private static int verify(SshSftpService.ReadFileHandle remote, long offset, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, readExact(remote, offset, expected.length))) throw changed();
        return expected.length;
    }
    private static byte[] readExact(SshSftpService.ReadFileHandle remote, long offset, int length) throws IOException {
        byte[] bytes = new byte[length]; int read = 0;
        while (read < length) {
            int n = remote.read(offset + read, bytes, read, length - read);
            if (n <= 0) throw changed(); read += n;
        }
        return bytes;
    }
    private static IOException changed() { return new FileTailRecoveryException("FILE_CHANGED", "文件已改变或截断，正在重新筛选"); }
    private static final class Scan {
        final String token, path;
        final SshFileFilterScanner scanner;
        long offset, lastSize, mtime;
        boolean limited;
        SshFileFilterRemoteCursor remote;
        volatile TailLease tailOwner;
        volatile long touched = System.currentTimeMillis();
        byte[] prefix = new byte[0], boundary = new byte[0];
        Scan(String token, String path, SshFileFilterScanner scanner, long size, long mtime) {
            this.token = token; this.path = path; this.scanner = scanner; this.lastSize = size; this.mtime = mtime;
        }
    }
    @Value public static class Started { String scanId; String mode; }
    @Value public static class Batch { String scanId; List<SshFileFilterScanner.Row> rows; long offset; long totalSize; boolean eof; boolean limited; }
}
