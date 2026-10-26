package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.NoWrap;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshSftpService;
import com.doumiot.sshconsole.service.SshFolderTransferService;
import com.doumiot.sshconsole.service.SshUploadService;
import org.springframework.core.io.InputStreamResource;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.FilterInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH SFTP", path = "/api/ssh-console/sftp")
public class SshSftpController {

    @Resource
    private SshSftpService sshSftpService;
    @Resource private SshFolderTransferService folderTransfer;
    @Resource private SshUploadService uploads;

    @GetMapping("/folder/limits")
    public long folderLimits() { return folderTransfer.maxBytes(); }

    @PostMapping("/folder/mkdir")
    public void folderMkdir(@RequestParam @NotBlank String sessionToken,@RequestParam(required=false) String path,
            @RequestParam @NotBlank String relativePath) throws IOException {
        folderTransfer.mkdir(sessionToken,path,relativePath);
    }

    @GetMapping("/folder/tree")
    public SshFolderTransferService.Tree folderTree(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String path) throws IOException {
        return folderTransfer.tree(sessionToken,path);
    }

    @NoWrap @GetMapping("/folder/download")
    public ResponseEntity<InputStreamResource> folderDownload(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String path,@RequestParam(defaultValue="true") boolean compress) throws IOException {
        Path zip=folderTransfer.archive(sessionToken,path,compress);
        long size=Files.size(zip);
        InputStreamResource body=new InputStreamResource(new FilterInputStream(Files.newInputStream(zip)) {
            @Override public void close() throws IOException {try{super.close();}finally{Files.deleteIfExists(zip);}}
        });
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=folder.zip")
                .contentLength(size).body(body);
    }

    @PostMapping("/folder/upload")
    public void folderUpload(@RequestParam @NotBlank String sessionToken,@RequestParam(required=false) String path,
            @RequestParam("file") MultipartFile file,@RequestParam(defaultValue="false") boolean archive,
            @RequestParam(required=false) String relativePath) throws IOException {
        if(archive)folderTransfer.uploadArchive(sessionToken,path,file);
        else folderTransfer.uploadFile(sessionToken,path,relativePath,file);
    }

    @GetMapping("/directories")
    public SshSftpService.ListResult directories(@RequestParam @NotBlank String sessionToken,
            @RequestParam(required = false) String path) throws IOException {
        return sshSftpService.directories(sessionToken, path);
    }

    @GetMapping("/list")
    public SshSftpService.ListResult list(
            @RequestParam @NotBlank String sessionToken,
            @RequestParam(required = false) String path) throws IOException {
        return sshSftpService.list(sessionToken, path);
    }

    @NoWrap
    @GetMapping("/download")
    public ResponseEntity<InputStreamResource> download(
            @RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String path) throws IOException {
        return sshSftpService.streamDownload(sessionToken, path);
    }

    @GetMapping("/read-chunk")
    public SshSftpService.ChunkResult readChunk(
            @RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String path,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "262144") int length,
            @RequestParam(defaultValue = "false") boolean fromEnd) throws IOException {
        return sshSftpService.readChunk(sessionToken, path, offset, length, fromEnd);
    }

    @PostMapping("/upload/start")
    public String startUpload(@RequestParam @NotBlank String sessionToken,
            @RequestParam(required=false) String path, @RequestParam @NotBlank String name,
            @RequestParam long total) throws IOException {
        return uploads.start(sessionToken, path, name, total);
    }

    @PostMapping("/upload/chunk")
    public void uploadChunk(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId,
            @RequestParam long offset, @RequestParam("file") MultipartFile file) throws IOException {
        uploads.chunk(sessionToken, uploadId, offset, file);
    }

    @PostMapping("/upload/complete")
    public void completeUpload(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId) throws IOException {
        uploads.complete(sessionToken, uploadId);
    }

    @PostMapping("/upload/cancel")
    public void cancelUpload(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId) throws IOException {
        uploads.cancel(sessionToken, uploadId);
    }

    @PostMapping("/upload")
    public void upload(
            @RequestParam @NotBlank String sessionToken,
            @RequestParam(required = false) String path,
            @RequestParam("file") MultipartFile file) throws IOException {
        sshSftpService.upload(sessionToken, path, file);
    }

    @PostMapping("/mkdir")
    public void mkdir(@RequestBody @Validated MkdirRequest body) throws IOException {
        sshSftpService.mkdir(body.getSessionToken(), body.getPath());
    }

    @PostMapping("/delete")
    public void delete(@RequestBody @Validated PathRequest body) throws IOException {
        sshSftpService.delete(body.getSessionToken(), body.getPath());
    }

    @PostMapping("/rename")
    public void rename(@RequestBody @Validated RenameRequest body) throws IOException {
        sshSftpService.rename(body.getSessionToken(), body.getPath(), body.getNewName());
    }

    @Data
    public static class MkdirRequest {
        @NotBlank
        private String sessionToken;
        @NotBlank
        private String path;
    }

    @Data
    public static class PathRequest {
        @NotBlank
        private String sessionToken;
        @NotBlank
        private String path;
    }

    @Data
    public static class RenameRequest {
        @NotBlank
        private String sessionToken;
        @NotBlank
        private String path;
        @NotBlank
        private String newName;
    }
}
