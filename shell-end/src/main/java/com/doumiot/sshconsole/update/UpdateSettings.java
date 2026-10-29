package com.doumiot.sshconsole.update;

import com.doumiot.config.DoumiShellStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Server-owned configuration; secrets never belong in an HTTP response. */
@Component
public class UpdateSettings {
    public static final String REPOSITORY="https://github.com/zhlifucheng-web/doumi-shell";
    public static final String API="https://api.github.com/repos/zhlifucheng-web/doumi-shell/releases/latest";
    private final Path directory;
    private final String token;
    private String proxy;
    @Autowired public UpdateSettings(DoumiShellStorageProperties storage, Environment env) throws IOException {
        this(Paths.get(storage.getDataDir()),env.getProperty("doumi.update.proxy", ""),env.getProperty("doumi.update.token", ""));
    }
    public UpdateSettings(Path data,String defaultProxy,String overrideToken) throws IOException {
        directory=data.toAbsolutePath().normalize().resolve("updates");
        Files.createDirectories(directory);privateMode(directory,true);
        Properties saved=new Properties();
        Path config=directory.resolve("settings.properties");
        if(Files.exists(config))try(InputStream in=Files.newInputStream(config)){saved.load(in);}
        proxy=normalizeProxy(saved.getProperty("proxy",defaultProxy));
        Path tokenFile=directory.resolve("admin-token");
        if(overrideToken!=null&&!overrideToken.trim().isEmpty())token=overrideToken.trim();
        else {
            if(!Files.exists(tokenFile)) {
                byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
                atomic(tokenFile,Base64.getUrlEncoder().withoutPadding().encode(bytes));
            }
            token=new String(Files.readAllBytes(tokenFile),StandardCharsets.UTF_8).trim();
            if(token.length()<32)throw new IOException("更新管理密钥无效，请检查 data/updates/admin-token");
            privateMode(tokenFile,false);
        }
    }
    public Path directory(){return directory;}
    public synchronized String proxy(){return proxy;}
    public synchronized void saveProxy(String value) throws IOException {
        String normalized=normalizeProxy(value);
        Properties saved=new Properties();saved.setProperty("proxy",normalized);
        ByteArrayOutputStream out=new ByteArrayOutputStream();saved.store(out,"Doumi update settings");
        atomic(directory.resolve("settings.properties"),out.toByteArray());proxy=normalized;
    }
    public void authorize(String value) {
        if(value==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("更新管理密钥不正确，请读取部署机器数据目录下的 updates/admin-token，或使用配置的 DOUMI_UPDATE_TOKEN");
    }
    public synchronized String route(String url) {
        if(!url.equals(API)&&!url.startsWith(REPOSITORY+"/releases/download/"))throw new IllegalArgumentException("更新地址不属于指定 GitHub 仓库");
        return proxy+url;
    }
    public static String normalizeProxy(String input) {
        String value=input==null?"":input.trim();if(value.isEmpty())return "";
        try {
            URI uri=new URI(value);
            if(value.length()>512||(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))
                ||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                ||uri.getPort()==0||uri.getPort()>65535||value.chars().anyMatch(c->c<33||c==127))throw new IllegalArgumentException();
            return value.endsWith("/")?value:value+"/";
        }catch(Exception e){throw new IllegalArgumentException("加速代理须为 HTTP(S) 地址前缀，不能包含账号密码、查询参数或片段");}
    }
    public static void atomic(Path path,byte[] bytes) throws IOException {
        Path temp=Files.createTempFile(path.getParent(),"update-",".tmp");
        try {privateMode(temp,false);Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    public static void privateMode(Path path,boolean directory) throws IOException {
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
}
