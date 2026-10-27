package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.ReturnObjectHandler;
import com.doumiot.sshconsole.service.SshRemoteBackupService;
import com.doumiot.sshconsole.service.SshRemoteBackupService.RemoteBackupConfigDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SshRemoteBackupControllerTest {

    @Test
    void testGetAndSaveConfigEndpoints() throws Exception {
        SshRemoteBackupService service = mock(SshRemoteBackupService.class);
        RemoteBackupConfigDTO dto = new RemoteBackupConfigDTO();
        dto.setEnabled(true);
        dto.setTargetType("MINIO");
        dto.setCron("0 0 2 * * *");
        when(service.getConfig()).thenReturn(dto);

        SshRemoteBackupController controller = new SshRemoteBackupController();
        ReflectionTestUtils.setField(controller, "service", service);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ReturnObjectHandler()).build();

        mvc.perform(get("/api/ssh-console/backup/remote/config"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetType").value("MINIO"))
                .andExpect(jsonPath("$.data.cron").value("0 0 2 * * *"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mvc.perform(post("/api/ssh-console/backup/remote/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"targetType\":\"MINIO\",\"cron\":\"0 0 2 * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).saveConfig(any());

        when(service.testConnection(any())).thenReturn(Collections.singletonMap("success", true));
        mvc.perform(post("/api/ssh-console/backup/remote/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"MINIO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        when(service.executeBackupNow()).thenReturn(Collections.singletonMap("success", true));
        mvc.perform(post("/api/ssh-console/backup/remote/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
