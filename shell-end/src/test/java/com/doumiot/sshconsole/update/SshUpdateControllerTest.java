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
  settings=new UpdateSettings(dir,"","test-management-token");service=mock(UpdateService.class);
  when(service.status()).thenReturn(Map.of("phase","IDLE","busy",false));
  mvc=MockMvcBuilders.standaloneSetup(new SshUpdateController(settings,service,new MockEnvironment()))
      .setControllerAdvice(new ReturnObjectHandler()).build();
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
}
