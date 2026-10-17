package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.ReturnObjectHandler;
import com.doumiot.sshconsole.service.SshUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadResponseTest {
    @Test void uploadStartReturnsTheSameSuccessContractAsChunksAndCompletion() throws Exception {
        SshUploadService uploads = mock(SshUploadService.class);
        when(uploads.start("token", "/tmp", "large.zip", 3221225472L)).thenReturn("upload-id");
        SshSftpController controller = new SshSftpController();
        ReflectionTestUtils.setField(controller, "uploads", uploads);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ReturnObjectHandler()).build();

        mvc.perform(post("/api/ssh-console/sftp/upload/start")
                .param("sessionToken", "token").param("path", "/tmp")
                .param("name", "large.zip").param("total", "3221225472"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("upload-id"));
        mvc.perform(multipart("/api/ssh-console/sftp/upload/chunk")
                .file(new MockMultipartFile("file", "large.zip", "application/octet-stream", new byte[]{1, 2}))
                .param("sessionToken", "token").param("uploadId", "upload-id").param("offset", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/ssh-console/sftp/upload/complete")
                .param("sessionToken", "token").param("uploadId", "upload-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(uploads).chunk(eq("token"), eq("upload-id"), eq(0L), any());
        verify(uploads).complete("token", "upload-id");
    }
}
