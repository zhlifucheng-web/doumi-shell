package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.update.UpdateSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import com.doumiot.config.DoumiShellStorageProperties;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SshReleaseServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    SshReleaseService service;
    MockRestServiceServer server;
    @BeforeEach void setup() {
        service = new SshReleaseService();
        server = MockRestServiceServer.createServer((RestTemplate) ReflectionTestUtils.getField(service, "http"));
    }
    String release(String version) {
        return "{\"tag_name\":\"v" + version + "\",\"html_url\":null,\"assets\":[{\"name\":\"Doumi.Shell-" + version
                + ".msi\"},{\"name\":\"Doumi.Shell-" + version + ".msi.sha256\"},{\"name\":\"doumi-shell-" + version + "-linux-x64.tar.gz\"},{\"name\":\"doumi-shell-" + version + "-linux-x64.tar.gz.sha256\"}]}";
    }
    UpdateSettings authenticatedSettings() throws Exception {
        DoumiShellStorageProperties storage=new DoumiShellStorageProperties();storage.setDataDir(directory.toString());
        UpdateSettings settings=new UpdateSettings(storage,new MockEnvironment().withProperty("doumi.update.github-token","fixtureGithubToken123"));
        ReflectionTestUtils.setField(service,"settings",settings);return settings;
    }
    @Test void authenticatesDirectApiOnlyAndNeverPassesTokenToProxy() throws Exception {
        UpdateSettings settings=authenticatedSettings();settings.saveProxy("https://gh-proxy.com/");
        server.expect(requestTo(SshReleaseService.API)).andExpect(header("Authorization","Bearer fixtureGithubToken123"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://gh-proxy.com/"+SshReleaseService.API)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(release("1.0.63"),MediaType.APPLICATION_JSON));
        assertEquals("1.0.63",service.latest(true).getVersion());server.verify();
    }
    @Test void invalidTokenIsActionableWithoutAnonymousProxyFallbackOrTokenDisclosure() throws Exception {
        authenticatedSettings().saveProxy("https://gh-proxy.com/");
        server.expect(requestTo(SshReleaseService.API)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        String error=service.latest(true).getError();assertTrue(error.contains("Token"));assertFalse(error.contains("fixtureGithubToken123"));server.verify();
    }
    @Test void changingTokenInvalidatesSuccessfulReleaseCache() throws Exception {
        UpdateSettings settings=authenticatedSettings();
        server.expect(requestTo(SshReleaseService.API)).andExpect(header("Authorization","Bearer fixtureGithubToken123"))
                .andRespond(withSuccess(release("1.0.63"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(SshReleaseService.API)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(release("1.0.64"),MediaType.APPLICATION_JSON));
        assertEquals("1.0.63",service.latest(false).getVersion());settings.saveSettings(null,"");
        assertEquals("1.0.64",service.latest(false).getVersion());server.verify();
    }
    @Test void metadataTransportDoesNotForwardAuthorizationOnRedirects() throws Exception {
        com.sun.net.httpserver.HttpServer first=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        com.sun.net.httpserver.HttpServer second=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
        second.createContext("/capture",x->{received.incrementAndGet();x.sendResponseHeaders(200,0);x.close();});
        first.createContext("/redirect",x->{x.getResponseHeaders().set("Location","http://127.0.0.1:"+second.getAddress().getPort()+"/capture");x.sendResponseHeaders(302,-1);x.close();});
        first.start();second.start();
        try {
            RestTemplate transport=(RestTemplate)ReflectionTestUtils.getField(new SshReleaseService(),"http");
            org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.setBearerAuth("fixtureGithubToken123");
            transport.exchange("http://127.0.0.1:"+first.getAddress().getPort()+"/redirect",org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),String.class);
            assertEquals(0,received.get());
        } finally {first.stop(0);second.stop(0);}
    }
    @Test void resolvesExactReleaseAndPlatformLinksWithoutHtmlUrl() {
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(release("1.0.18"), MediaType.APPLICATION_JSON));
        SshReleaseService.Latest latest = service.latest(false);
        assertNull(latest.getError());
        assertEquals("1.0.18", latest.getVersion());
        assertEquals("https://github.com/zhlifucheng-web/doumi-shell/releases/tag/v1.0.18", latest.getUrl());
        assertTrue(latest.getWindowsUrl().endsWith("/Doumi.Shell-1.0.18.msi"));
        assertTrue(latest.getWindowsChecksumUrl().endsWith(".msi.sha256"));
        assertTrue(latest.getLinuxUrl().endsWith("/doumi-shell-1.0.18-linux-x64.tar.gz"));
        assertSame(latest, service.latest(false));
        server.verify();
    }
    @Test void manualRefreshBypassesSuccessfulCache() {
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(release("1.0.17"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(release("1.0.18"), MediaType.APPLICATION_JSON));
        assertEquals("1.0.17", service.latest(false).getVersion());
        assertEquals("1.0.18", service.latest(true).getVersion());
        server.verify();
    }
    @Test void rateLimitIsNotReportedAsLatestAndIsNotCached() {
        server.expect(requestTo(SshReleaseService.API)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(release("1.0.18"), MediaType.APPLICATION_JSON));
        SshReleaseService.Latest failed = service.latest(false);
        assertNull(failed.getVersion());
        assertTrue(failed.getError().contains("429"));
        assertEquals("1.0.18", service.latest(false).getVersion());
        server.verify();
    }
    @Test void networkFailureIncludesServerNetworkInstructions() {
        server.expect(requestTo(SshReleaseService.API)).andRespond(request -> { throw new IOException("timed out"); });
        SshReleaseService.Latest result = service.latest(true);
        assertNull(result.getVersion());
        assertTrue(result.getError().contains("部署 Doumi Shell 的机器无法连接 GitHub"));
    }
    @Test void missingAssetsAreReportedAsIncompleteRelease() {
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess("{\"tag_name\":\"v1.0.18\",\"assets\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.latest(true).getError().contains("尚未上传完整"));
    }
    @Test void invalidNullAndPrereleaseResponsesNeverReturnAnAvailableVersion() {
        for (String response : new String[]{"null", "broken", "{\"tag_name\":\"../bad\"}", "{\"tag_name\":\"v1.0.18\",\"prerelease\":true}"}) {
            server.reset();
            server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            SshReleaseService.Latest result = service.latest(true);
            assertNull(result.getVersion());
            assertNotNull(result.getError());
        }
    }
    @Test void requiresChecksumsAndRejectsDrafts() {
        for(String body:new String[]{release("1.0.63").replace(".msi.sha256", ".wrong"),release("1.0.63").replaceFirst("\\{", "{\"draft\":true,")}) {
            server.reset();server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertNotNull(service.latest(true).getError());
        }
    }
    @Test void fallsBackToConfiguredProxyWhenGithubApiIsUnavailableAndInvalidatesCacheOnChange() throws Exception {
        java.nio.file.Path data=java.nio.file.Files.createTempDirectory("doumi-proxy-test");
        UpdateSettings settings=new UpdateSettings(data,"https://gh-proxy.com/","");
        ReflectionTestUtils.setField(service,"settings",settings);
        server.expect(requestTo(SshReleaseService.API)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://gh-proxy.com/"+SshReleaseService.API)).andRespond(withSuccess(release("1.0.63"),MediaType.APPLICATION_JSON));
        assertEquals("1.0.63",service.latest(false).getVersion());
        server.verify();server.reset();settings.saveProxy("");
        server.expect(requestTo(SshReleaseService.API)).andRespond(withSuccess(release("1.0.64"),MediaType.APPLICATION_JSON));
        assertEquals("1.0.64",service.latest(false).getVersion());
        server.verify();com.doumiot.updater.UpdateFiles.delete(data);
    }

}
