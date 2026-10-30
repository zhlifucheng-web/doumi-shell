package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.update.UpdateSettings;
import javax.annotation.Resource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Service
@SshConsoleEnabled
public class SshReleaseService {
    private static final String REPOSITORY = UpdateSettings.REPOSITORY;
    static final String API = UpdateSettings.API;
    private final RestTemplate http;
    private final ObjectMapper json = new ObjectMapper();
    private Latest cached;
    private long cachedAt;
    private String cachedProxy;
    @Resource private UpdateSettings settings;

    public SshReleaseService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        http = new RestTemplate(factory);
    }

    public synchronized Latest latest(boolean refresh) {
        String proxy=settings==null?"":settings.proxy();
        if (!refresh && java.util.Objects.equals(proxy,cachedProxy) && cached != null && System.currentTimeMillis() - cachedAt < 60_000) return cached;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.set(HttpHeaders.USER_AGENT, "Doumi-Shell-Update-Check");
            headers.setCacheControl("no-cache");
            String response;
            try { response = metadata(API,headers); }
            catch (org.springframework.web.client.RestClientException directFailure) {
                if(proxy.isEmpty())throw directFailure;
                response=metadata(settings.route(API),headers);
            }
            JsonNode release = json.readTree(response);
            if (release == null || release.isNull()) return failure("GitHub 暂未返回可用发布版本，请稍后重试或直接打开下载页面。");
            String version = release.path("tag_name").asText("").replaceFirst("^v", "");
            if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || (release.path("prerelease").asBoolean(false) || release.path("draft").asBoolean(false)))
                return failure("GitHub 返回的版本信息无效，无法判断是否有更新，请打开下载页面查看。");
            Latest latest = new Latest();
            latest.setVersion(version);
            latest.setUrl(REPOSITORY + "/releases/tag/v" + version);
            for (JsonNode asset : release.path("assets")) {
                String name = asset.path("name").asText("");
                // Build trusted links from the validated version rather than accepting arbitrary upstream URLs.
                if (name.equals("Doumi.Shell-" + version + ".msi"))
                    latest.setWindowsUrl(REPOSITORY + "/releases/download/v" + version + "/Doumi.Shell-" + version + ".msi");
                if (name.equals("Doumi.Shell-" + version + ".msi.sha256"))
                    latest.setWindowsChecksumUrl(REPOSITORY + "/releases/download/v" + version + "/" + name);
                if (name.equals("doumi-shell-" + version + "-linux-x64.tar.gz.sha256"))
                    latest.setLinuxChecksumUrl(REPOSITORY + "/releases/download/v" + version + "/" + name);
                if (name.equals("doumi-shell-" + version + "-linux-x64.tar.gz"))
                    latest.setLinuxUrl(REPOSITORY + "/releases/download/v" + version + "/" + name);
            }
            if (latest.getWindowsUrl() == null || latest.getLinuxUrl() == null || latest.getWindowsChecksumUrl() == null || latest.getLinuxChecksumUrl() == null)
                return failure("新版本的 Windows / Linux 安装包尚未上传完整，请稍后重试或打开发布页面查看。");
            cached = latest;
            cachedAt = System.currentTimeMillis();
            cachedProxy=proxy;
            return latest;
        } catch (HttpStatusCodeException error) {
            int status = error.getRawStatusCode();
            if (status == 403 || status == 429) return failure("GitHub 拒绝或限制了更新请求（HTTP " + status + "），请稍后重试或打开下载页面。");
            return failure("GitHub 更新服务返回 HTTP " + status + "，无法确认最新版本，请稍后重试。");
        } catch (ResourceAccessException error) {
            return failure("部署 Doumi Shell 的机器无法连接 GitHub，可能是连接超时、DNS 或网络限制。请检查服务器网络，或直接打开下载页面。");
        } catch (Exception error) {
            return failure("无法解析 GitHub 版本信息，本次未能确认最新版本，请稍后重试或打开下载页面。");
        }
    }

    private String metadata(String address,HttpHeaders headers) {
        return http.execute(java.net.URI.create(address),HttpMethod.GET,request->request.getHeaders().putAll(headers),response->{
            byte[] bytes=response.getBody().readNBytes(1024*1024+1);
            if(bytes.length>1024*1024)throw new java.io.IOException("GitHub 版本信息超过大小限制");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        });
    }
    private Latest failure(String message) {
        Latest result = new Latest(); result.setError(message); result.setUrl(REPOSITORY + "/releases"); return result;
    }

    @Data
    public static class Latest {
        private String version;
        private String url;
        private String windowsUrl;
        private String linuxUrl;
        private String windowsChecksumUrl;
        private String linuxChecksumUrl;
        private String error;
    }
}
