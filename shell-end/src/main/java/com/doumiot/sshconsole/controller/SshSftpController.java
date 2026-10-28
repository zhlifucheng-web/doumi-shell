package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.NoWrap;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshSftpService;
import com.doumiot.sshconsole.sftp.SshFileTextCodec;
import com.doumiot.sshconsole.sftp.SftpStreams;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
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
    @Resource private SshSessionRegistry sessions;

    @GetMapping("/folder/limits")
    public long folderLimits() { return folderTransfer.maxBytes(); }

    public void folderMkdir(String sessionToken,String path,String relativePath) throws IOException {
        folderMkdir(sessionToken,path,relativePath,null,null);
    }
    @PostMapping("/folder/mkdir")
    public SftpStreams.UploadResult folderMkdir(@RequestParam @NotBlank String sessionToken,@RequestParam(required=false) String path,
            @RequestParam @NotBlank String relativePath,@RequestParam(required=false) Long ownerUid,
            @RequestParam(required=false) Long ownerGid) throws IOException {
        return folderTransfer.mkdir(sessionToken,path,relativePath,ownerUid,ownerGid);
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

    public void folderUpload(String sessionToken,String path,MultipartFile file,boolean archive,String relativePath) throws IOException {
        folderUpload(sessionToken,path,file,archive,relativePath,null,null);
    }
    @PostMapping("/folder/upload")
    public SftpStreams.UploadResult folderUpload(@RequestParam @NotBlank String sessionToken,@RequestParam(required=false) String path,
            @RequestParam("file") MultipartFile file,@RequestParam(defaultValue="false") boolean archive,
            @RequestParam(required=false) String relativePath,@RequestParam(required=false) Long ownerUid,
            @RequestParam(required=false) Long ownerGid) throws IOException {
        if(archive)return folderTransfer.uploadArchive(sessionToken,path,file,ownerUid,ownerGid);
        return folderTransfer.uploadFile(sessionToken,path,relativePath,file,ownerUid,ownerGid);
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

    @NoWrap
    @PostMapping("/encode-text")
    public ResponseEntity<byte[]> encodeText(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String encoding, @RequestParam(defaultValue = "false") boolean bom,
            @RequestParam("file") MultipartFile file) throws IOException {
        sessions.get(sessionToken);
        if (file.getSize() > SshFileTextCodec.MAX_BYTES) throw new IOException("在线编辑文件超过 50 MB 限制");
        byte[] encoded = SshFileTextCodec.encode(file.getBytes(), encoding, bom);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(encoded.length).body(encoded);
    }

    public String startUpload(String sessionToken,String path,String name,long total) throws IOException {
        return startUpload(sessionToken,path,name,total,null,null);
    }
    @PostMapping("/upload/start")
    public String startUpload(@RequestParam @NotBlank String sessionToken,
            @RequestParam(required=false) String path, @RequestParam @NotBlank String name,
            @RequestParam long total,@RequestParam(required=false) Long ownerUid,
            @RequestParam(required=false) Long ownerGid) throws IOException {
        return uploads.start(sessionToken, path, name, total, ownerUid, ownerGid);
    }

    @PostMapping("/upload/chunk")
    public void uploadChunk(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId,
            @RequestParam long offset, @RequestParam("file") MultipartFile file) throws IOException {
        uploads.chunk(sessionToken, uploadId, offset, file);
    }

    public void completeUpload(String sessionToken,String uploadId) throws IOException {
        uploads.complete(sessionToken,uploadId);
    }
    @PostMapping("/upload/complete")
    public SftpStreams.UploadResult completeUploadResult(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId) throws IOException {
        return uploads.completeResult(sessionToken, uploadId);
    }

    @PostMapping("/upload/cancel")
    public void cancelUpload(@RequestParam @NotBlank String sessionToken,
            @RequestParam @NotBlank String uploadId) throws IOException {
        uploads.cancel(sessionToken, uploadId);
    }

    public void upload(String sessionToken,String path,MultipartFile file) throws IOException {
        upload(sessionToken,path,file,null,null);
    }
    @PostMapping("/upload")
    public SftpStreams.UploadResult upload(
            @RequestParam @NotBlank String sessionToken,
            @RequestParam(required = false) String path,
            @RequestParam("file") MultipartFile file,@RequestParam(required=false) Long ownerUid,
            @RequestParam(required=false) Long ownerGid) throws IOException {
        return sshSftpService.upload(sessionToken, path, file, ownerUid, ownerGid);
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

    @PostMapping("/chown")
    public void chown(@RequestBody @Validated ChownRequest body) throws IOException {
        sshSftpService.chown(body.getSessionToken(), body.getPath(), body.getOwner(), body.getGroup(), body.isRecursive());
    }

    @GetMapping("/users-and-groups")
    public SshSftpService.SystemUsersAndGroups usersAndGroups(@RequestParam @NotBlank String sessionToken) throws IOException {
        return sshSftpService.systemUsersAndGroups(sessionToken);
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

    @Data
    public static class ChownRequest {
        @NotBlank
        private String sessionToken;
        @NotBlank
        private String path;
        private String owner;
        private String group;
        private boolean recursive;
    }
}
