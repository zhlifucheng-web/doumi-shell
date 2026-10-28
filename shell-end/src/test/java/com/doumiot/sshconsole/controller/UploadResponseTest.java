package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.ReturnObjectHandler;
import com.doumiot.sshconsole.service.SshUploadService;
import com.doumiot.sshconsole.sftp.SftpStreams;
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
        when(uploads.start("token", "/tmp", "large.zip", 3221225472L, null, null)).thenReturn("upload-id");
        when(uploads.completeResult("token", "upload-id")).thenReturn(SftpStreams.UploadResult.success(false, null));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploaded").value(true))
                .andExpect(jsonPath("$.data.ownershipApplied").value(false));
        verify(uploads).chunk(eq("token"), eq("upload-id"), eq(0L), any());
        verify(uploads).start("token", "/tmp", "large.zip", 3221225472L, null, null);
        verify(uploads).completeResult("token", "upload-id");
    }

    @Test void ownershipSnapshotAndWarningRetainSuccessfulUploadContract() throws Exception {
        SshUploadService uploads = mock(SshUploadService.class);
        when(uploads.start("token", "/tmp", "file", 1L, 99L, 99L)).thenReturn("owned-id");
        when(uploads.completeResult("token", "owned-id"))
                .thenReturn(SftpStreams.UploadResult.success(false, "ownership denied"));
        SshSftpController controller = new SshSftpController();
        ReflectionTestUtils.setField(controller, "uploads", uploads);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ReturnObjectHandler()).build();
        mvc.perform(post("/api/ssh-console/sftp/upload/start")
                .param("sessionToken", "token").param("path", "/tmp").param("name", "file")
                .param("total", "1").param("ownerUid", "99").param("ownerGid", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("owned-id"));
        mvc.perform(post("/api/ssh-console/sftp/upload/complete")
                .param("sessionToken", "token").param("uploadId", "owned-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploaded").value(true))
                .andExpect(jsonPath("$.data.ownershipApplied").value(false))
                .andExpect(jsonPath("$.data.warning").value("ownership denied"));
        verify(uploads).start("token", "/tmp", "file", 1L, 99L, 99L);
    }
}
