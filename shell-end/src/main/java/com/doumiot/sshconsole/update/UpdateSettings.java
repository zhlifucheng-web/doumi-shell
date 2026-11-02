package com.doumiot.sshconsole.update;

import com.doumiot.config.DoumiShellStorageProperties;
import com.doumiot.sshconsole.crypto.CredentialCipher;
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
@com.doumiot.sshconsole.config.SshConsoleEnabled
public class UpdateSettings {
    public static final String REPOSITORY="https://github.com/zhlifucheng-web/doumi-shell";
    public static final String API="https://api.github.com/repos/zhlifucheng-web/doumi-shell/releases/latest";
    private final Path directory;
    private final String token;
    private final boolean requireToken;
    private String proxy;
    private String httpProxy;
    private final Properties saved=new Properties();
    private String defaultGithubToken="";
    private long revision;
    @javax.annotation.Resource private CredentialCipher cipher;
    @Autowired public UpdateSettings(DoumiShellStorageProperties storage, Environment env) throws IOException {
        this(Paths.get(storage.getDataDir()),env.getProperty("doumi.update.proxy", ""),env.getProperty("doumi.update.token", ""),
                env.getProperty("doumi.update.require-token",Boolean.class,false));
        defaultGithubToken=normalizeGithubToken(env.getProperty("doumi.update.github-token", ""));
        httpProxy=normalizeHttpProxy(saved.getProperty("http-proxy",env.getProperty("doumi.update.http-proxy","")));
    }
    public UpdateSettings(Path data,String defaultProxy,String overrideToken) throws IOException {
        this(data,defaultProxy,overrideToken,false);
    }
    public UpdateSettings(Path data,String defaultProxy,String overrideToken,boolean requireToken) throws IOException {
        this.requireToken=requireToken;
        directory=data.toAbsolutePath().normalize().resolve("updates");
        Files.createDirectories(directory);privateMode(directory,true);
        Path config=directory.resolve("settings.properties");
        if(Files.exists(config))try(InputStream in=Files.newInputStream(config)){saved.load(in);}
        proxy=normalizeProxy(saved.getProperty("proxy",defaultProxy));
        httpProxy=normalizeHttpProxy(saved.getProperty("http-proxy",""));
        Path tokenFile=directory.resolve("admin-token");
        if(!requireToken)token="";
        else if(overrideToken!=null&&!overrideToken.trim().isEmpty())token=overrideToken.trim();
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
    public boolean requiresToken(){return requireToken;}
    public synchronized String proxy(){return proxy;}
    public synchronized String httpProxy(){return httpProxy;}
    public synchronized void saveProxy(String value) throws IOException {
        saveSettings(normalizeProxy(value),null);
    }
    public synchronized long revision(){return revision;}
    public synchronized boolean hasGithubToken(){return !saved.getProperty("github-token.enc",defaultGithubToken).isEmpty();}
    /** Internal use only: never include this value in status, logs or proxy requests. */
    public synchronized String githubToken() {
        String encrypted=saved.getProperty("github-token.enc");
        if(encrypted==null)return defaultGithubToken;
        if(encrypted.isEmpty())return "";
        try{return normalizeGithubToken(cipher.decryptToJson(Base64.getDecoder().decode(encrypted)));}
        catch(Exception error){throw new IllegalStateException("GitHub API Token 无法解密，请重新保存 Token 或检查凭据主密钥");}
    }
    /** Null retains a setting; an explicit empty token disables authentication, including the environment default. */
    public synchronized void saveSettings(String value,String githubToken) throws IOException {
        saveSettings(value,githubToken,null);
    }
    public synchronized void saveSettings(String value,String githubToken,String downloadProxy) throws IOException {
        String normalized=value==null?proxy:normalizeProxy(value);
        String normalizedHttp=downloadProxy==null?httpProxy:normalizeHttpProxy(downloadProxy);
        Properties next=new Properties();next.putAll(saved);next.setProperty("proxy",normalized);
        if(downloadProxy!=null)next.setProperty("http-proxy",normalizedHttp);
        if(githubToken!=null) {
            String secret=normalizeGithubToken(githubToken);
            next.setProperty("github-token.enc",secret.isEmpty()?"":Base64.getEncoder().encodeToString(cipher.encryptSecretJson(secret)));
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();next.store(out,"Doumi update settings");
        atomic(directory.resolve("settings.properties"),out.toByteArray());saved.clear();saved.putAll(next);proxy=normalized;httpProxy=normalizedHttp;revision++;
    }
    private static String normalizeGithubToken(String input) {
        if(input==null)return "";
        String value=input.trim();
        if(input.chars().anyMatch(c->c<32||c>126)||value.length()>1024||(!value.isEmpty()&&!value.matches("[A-Za-z0-9_.-]+")))
            throw new IllegalArgumentException("GitHub API Token 格式无效，请只填写 Token 本身");
        return value;
    }
    public void authorize(String value) {
        if(!requireToken)return;
        if(value==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("更新管理密钥不正确，请读取部署机器数据目录下的 updates/admin-token，或使用配置的 DOUMI_UPDATE_TOKEN");
    }
    public synchronized String route(String url) {
        if(!url.equals(API)&&!url.startsWith(REPOSITORY+"/releases/download/"))throw new IllegalArgumentException("更新地址不属于指定 GitHub 仓库");
        return proxy+url;
    }
    public static String normalizeHttpProxy(String input) {
        String value=input==null?"":input.trim();if(value.isEmpty())return "";
        try {
            URI uri=new URI(value);String path=uri.getRawPath();
            if(value.length()>512||!"http".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
                ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||(path!=null&&!path.isEmpty()&&!path.equals("/"))
                ||uri.getPort()<1||uri.getPort()>65535||value.chars().anyMatch(c->c<33||c==127))throw new IllegalArgumentException();
            return "http://"+uri.getRawAuthority();
        }catch(Exception error){throw new IllegalArgumentException("HTTP 下载代理须为 http://主机:端口，不支持账号密码、路径或查询参数");}
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
