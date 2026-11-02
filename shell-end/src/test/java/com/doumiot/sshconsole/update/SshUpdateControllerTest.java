package com.doumiot.sshconsole.update;
import com.doumiot.sshconsole.controller.SshUpdateController;
import com.doumiot.spring.result.ReturnObjectHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SshUpdateControllerTest {
 @TempDir Path dir;MockMvc mvc;UpdateService service;UpdateSettings settings;
 @BeforeEach void setup() throws Exception {
  configure(true);
 }
 void configure(Boolean requireToken) throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  MockEnvironment env=new MockEnvironment().withProperty("doumi.update.token","test-management-token");
  if(requireToken!=null)env.withProperty("doumi.update.require-token",requireToken.toString());
  settings=new UpdateSettings(storage,env);service=mock(UpdateService.class);
  com.doumiot.sshconsole.crypto.CredentialCipher cipher=new com.doumiot.sshconsole.crypto.CredentialCipher(new com.doumiot.sshconsole.config.SshConsoleProperties(),storage);
  org.springframework.test.util.ReflectionTestUtils.invokeMethod(cipher,"init");org.springframework.test.util.ReflectionTestUtils.setField(settings,"cipher",cipher);
  when(service.status()).thenAnswer(call->Map.of("phase","IDLE","busy",false,"githubTokenConfigured",settings.hasGithubToken()));
  mvc=MockMvcBuilders.standaloneSetup(new SshUpdateController(settings,service,new MockEnvironment()))
      .setControllerAdvice(new ReturnObjectHandler()).build();
 }
 @Test void defaultAllowsSettingsAndInstallationWithoutManagementToken() throws Exception {
  configure(null);
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"https://gh-proxy.com\",\"githubToken\":\"ghp_fixtureToken\"}"))
      .andExpect(status().isOk());
  org.junit.jupiter.api.Assertions.assertEquals("ghp_fixtureToken",settings.githubToken());
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"\"}")) .andExpect(status().isOk());
  when(service.start("1.0.64")).thenReturn(Map.of("phase","CHECKING"));
  mvc.perform(post("/api/ssh-console/update/install").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.64\"}")) .andExpect(status().isOk());
  verify(service).start("1.0.64");
 }
 @Test void savesOnlyHttpProxyAndRejectsInvalidOrNullWithoutChangingSettings() throws Exception {
  configure(false);
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"httpProxy\":\"http://127.0.0.1:7890\"}")) .andExpect(status().isOk());
  org.junit.jupiter.api.Assertions.assertEquals("http://127.0.0.1:7890",settings.httpProxy());
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"https://gh-proxy.com/\",\"httpProxy\":\"http://user:secret@host\"}")) .andExpect(status().isBadRequest());
  org.junit.jupiter.api.Assertions.assertEquals("",settings.proxy());
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"httpProxy\":null}")) .andExpect(status().isBadRequest());
 }
 @Test void explicitlyDisabledTokenStillRejectsCrossOriginAndCrossSiteRequests() throws Exception {
  configure(false);
  mvc.perform(put("/api/ssh-console/update/settings").header("Origin","https://evil.invalid").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"\"}")) .andExpect(status().isForbidden());
  mvc.perform(post("/api/ssh-console/update/install").header("Sec-Fetch-Site","cross-site").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.64\"}")) .andExpect(status().isForbidden());
  mvc.perform(put("/api/ssh-console/update/settings").header("Origin","http://localhost").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"\"}")) .andExpect(status().isOk());
  verify(service,never()).start(anyString());
 }
 @Test void cannotSaveProxyOrInstallWithoutToken() throws Exception {
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"https://gh-proxy.com/\"}")) .andExpect(status().isForbidden());
  mvc.perform(post("/api/ssh-console/update/install").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.63\"}")) .andExpect(status().isForbidden());
  verify(service,never()).start(anyString());
 }
 @Test void savesProxyWithTokenAndRejectsCrossOriginEvenWithToken() throws Exception {
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"https://gh-proxy.com\"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
  assert settings.proxy().equals("https://gh-proxy.com/");
  mvc.perform(post("/api/ssh-console/update/install").header("Origin","https://evil.invalid").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.63\"}")) .andExpect(status().isForbidden());
  verify(service,never()).start(anyString());
 }
 @Test void rejectsInvalidProxyAndConcurrentSettingsChanges() throws Exception {
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"file:///tmp/\"}")) .andExpect(status().isBadRequest());
  when(service.status()).thenReturn(Map.of("busy",true));
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"proxy\":\"\"}")) .andExpect(status().isConflict());
 }
 @Test void managesGithubTokenWithoutReturningItAndRequiresAuthorization() throws Exception {
  mvc.perform(put("/api/ssh-console/update/settings").contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"ghp_fixtureSecret123\"}")) .andExpect(status().isForbidden());
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"ghp_fixtureSecret123\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data.githubTokenConfigured").value(true)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ghp_fixtureSecret123"))));
  org.junit.jupiter.api.Assertions.assertEquals("ghp_fixtureSecret123",settings.githubToken());
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":null}")) .andExpect(status().isBadRequest());
  mvc.perform(put("/api/ssh-console/update/settings").header("X-Doumi-Update-Token","test-management-token").contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data.githubTokenConfigured").value(false));
 }
}
