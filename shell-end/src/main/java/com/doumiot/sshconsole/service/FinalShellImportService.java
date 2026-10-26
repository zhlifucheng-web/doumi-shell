package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.crypto.FinalShellCipher;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshGroup;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.mapper.SshGroupMapper;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import com.doumiot.sshconsole.model.SshAuthType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@SshConsoleEnabled
public class FinalShellImportService {

    @Resource private SshGroupMapper groupMapper;
    @Resource private SshHostMapper hostMapper;
    @Resource private SshAccountMapper accountMapper;
    @Resource private SshGroupService groupService;
    @Resource private SshHostService hostService;
    @Resource private SshAccountService accountService;
    @Resource private ObjectMapper json;

    @Data
    public static class FinalShellImportResult {
        private int totalFiles = 0;
        private int importedHosts = 0;
        private int importedAccounts = 0;
        private int createdGroups = 0;
        private int updatedHosts = 0;
        private int updatedAccounts = 0;
        private int failedCount = 0;
        private List<String> warnings = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
    }

    @Data
    public static class FinalShellConfig {
        private String name;
        private String host;
        private Integer port;
        private String user_name;
        private Integer authentication_type;
        private String password;
        private String description;
        private String parent_id;
        private String terminal_encoding;
        private String id;

        // Metadata
        private String relativePath;
        private List<String> folderSegments = new ArrayList<>();
    }

    @Data
    public static class FinalShellFolder {
        private String id;
        private String name;
        private String parent_id;
    }

    public FinalShellImportResult importFromMultipart(MultipartFile file, Long targetGroupId, boolean overwrite) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "上传的文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            filename = "upload.json";
        }
        byte[] bytes = file.getBytes();
        return importFromBytes(bytes, filename, targetGroupId, overwrite);
    }

    public FinalShellImportResult importFromLocalPath(String localPath, Long targetGroupId, boolean overwrite) throws Exception {
        if (!StringUtils.hasText(localPath)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "本地路径不能为空");
        }
        File target = new File(localPath.trim());
        if (!target.exists()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "指定的路径不存在: " + localPath);
        }

        if (target.isFile()) {
            byte[] bytes = Files.readAllBytes(target.toPath());
            return importFromBytes(bytes, target.getName(), targetGroupId, overwrite);
        }

        // It is a directory
        List<FinalShellConfig> configs = new ArrayList<>();
        Map<String, FinalShellFolder> folderMap = new HashMap<>();
        scanDirectory(target, target.getAbsolutePath(), configs, folderMap);
        return processImport(configs, folderMap, targetGroupId, overwrite);
    }

    public FinalShellImportResult importFromBytes(byte[] bytes, String filename, Long targetGroupId, boolean overwrite) throws Exception {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".zip")) {
            return importZipBytes(bytes, targetGroupId, overwrite);
        }
        // Otherwise treat as JSON
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<FinalShellConfig> configs = parseJsonConfigs(content, filename);
        return processImport(configs, Collections.emptyMap(), targetGroupId, overwrite);
    }

    private FinalShellImportResult importZipBytes(byte[] bytes, Long targetGroupId, boolean overwrite) throws Exception {
        // First try reading with UTF-8, if fails or malformed try GBK
        List<ZipParsedEntry> entries = readZipEntries(bytes, StandardCharsets.UTF_8);
        if (entries == null || entries.isEmpty()) {
            entries = readZipEntries(bytes, Charset.forName("GBK"));
        }
        if (entries == null) {
            entries = Collections.emptyList();
        }

        List<FinalShellConfig> configs = new ArrayList<>();
        Map<String, FinalShellFolder> folderMap = new HashMap<>();

        for (ZipParsedEntry entry : entries) {
            String path = entry.getPath().replace('\\', '/');
            String lower = path.toLowerCase();
            if (lower.endsWith("folder.json")) {
                try {
                    FinalShellFolder folder = json.readValue(entry.getContent(), FinalShellFolder.class);
                    if (folder != null && StringUtils.hasText(folder.getId())) {
                        folderMap.put(folder.getId(), folder);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse folder.json in zip: {}", path, e);
                }
            } else if (lower.endsWith(".json")) {
                List<FinalShellConfig> parsed = parseJsonConfigs(entry.getContent(), path);
                configs.addAll(parsed);
            }
        }

        return processImport(configs, folderMap, targetGroupId, overwrite);
    }

    private static class ZipParsedEntry {
        private final String path;
        private final String content;

        public ZipParsedEntry(String path, String content) {
            this.path = path;
            this.content = content;
        }

        public String getPath() { return path; }
        public String getContent() { return content; }
    }

    private List<ZipParsedEntry> readZipEntries(byte[] bytes, Charset charset) {
        List<ZipParsedEntry> list = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes), charset)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    list.add(new ZipParsedEntry(entry.getName(), out.toString("UTF-8")));
                }
                zis.closeEntry();
            }
            return list;
        } catch (Exception e) {
            log.debug("readZipEntries with charset {} failed: {}", charset, e.getMessage());
            return null;
        }
    }

    private void scanDirectory(File dir, String baseAbsolutePath, List<FinalShellConfig> configs, Map<String, FinalShellFolder> folderMap) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, baseAbsolutePath, configs, folderMap);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                try {
                    String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    String relPath = f.getAbsolutePath().substring(baseAbsolutePath.length());
                    if (relPath.startsWith(File.separator) || relPath.startsWith("/")) {
                        relPath = relPath.substring(1);
                    }
                    relPath = relPath.replace('\\', '/');

                    if (f.getName().toLowerCase().endsWith("folder.json")) {
                        FinalShellFolder folder = json.readValue(content, FinalShellFolder.class);
                        if (folder != null && StringUtils.hasText(folder.getId())) {
                            folderMap.put(folder.getId(), folder);
                        }
                    } else {
                        List<FinalShellConfig> list = parseJsonConfigs(content, relPath);
                        configs.addAll(list);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read local file: {}", f.getAbsolutePath(), e);
                }
            }
        }
    }

    private List<FinalShellConfig> parseJsonConfigs(String content, String relativePath) {
        List<FinalShellConfig> list = new ArrayList<>();
        if (!StringUtils.hasText(content)) return list;
        try {
            JsonNode root = json.readTree(content);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    FinalShellConfig cfg = convertJsonNode(item, relativePath);
                    if (cfg != null) list.add(cfg);
                }
            } else if (root.isObject()) {
                if (root.has("conn") && root.get("conn").isArray()) {
                    for (JsonNode item : root.get("conn")) {
                        FinalShellConfig cfg = convertJsonNode(item, relativePath);
                        if (cfg != null) list.add(cfg);
                    }
                } else if (root.has("list") && root.get("list").isArray()) {
                    for (JsonNode item : root.get("list")) {
                        FinalShellConfig cfg = convertJsonNode(item, relativePath);
                        if (cfg != null) list.add(cfg);
                    }
                } else {
                    FinalShellConfig cfg = convertJsonNode(root, relativePath);
                    if (cfg != null) list.add(cfg);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON content for path: {}", relativePath, e);
        }
        return list;
    }

    private FinalShellConfig convertJsonNode(JsonNode node, String relativePath) {
        if (node == null || !node.has("host") && !node.has("user_name") && !node.has("name")) {
            return null;
        }
        FinalShellConfig cfg = json.convertValue(node, FinalShellConfig.class);
        if (cfg == null) return null;
        cfg.setRelativePath(relativePath);

        // Derive folder segments from relativePath if present
        if (StringUtils.hasText(relativePath)) {
            String dirPath = relativePath.contains("/") ? relativePath.substring(0, relativePath.lastIndexOf('/')) : "";
            if (StringUtils.hasText(dirPath)) {
                String[] parts = dirPath.split("/");
                List<String> segments = new ArrayList<>();
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(".") && !trimmed.equalsIgnoreCase("finalshell")
                            && !trimmed.equalsIgnoreCase(".finalshell") && !trimmed.equalsIgnoreCase("conn")) {
                        segments.add(trimmed);
                    }
                }
                cfg.setFolderSegments(segments);
            }
        }
        return cfg;
    }

    private FinalShellImportResult processImport(List<FinalShellConfig> configs, Map<String, FinalShellFolder> folderMap, Long targetGroupId, boolean overwrite) {
        FinalShellImportResult result = new FinalShellImportResult();
        result.setTotalFiles(configs.size());

        if (targetGroupId != null) {
            SshGroup targetGroup = groupMapper.selectById(targetGroupId);
            if (targetGroup == null) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "指定的导入目标分组不存在: " + targetGroupId);
            }
        }

        // Cache for resolved group paths: pathKey -> groupId
        Map<String, Long> groupPathCache = new HashMap<>();

        for (FinalShellConfig config : configs) {
            try {
                if (!StringUtils.hasText(config.getHost())) {
                    result.getWarnings().add("跳过缺少 host 的配置: " + (config.getName() != null ? config.getName() : config.getRelativePath()));
                    continue;
                }

                // 1. Resolve Group Hierarchy
                List<String> segments = resolveSegments(config, folderMap);
                Long resolvedGroupId = resolveGroup(targetGroupId, segments, groupPathCache, result);

                // 2. Resolve Host
                String hostAddr = config.getHost().trim();
                int port = config.getPort() != null && config.getPort() > 0 ? config.getPort() : 22;
                String displayName = StringUtils.hasText(config.getName()) ? config.getName().trim() : hostAddr;
                String hostRemark = StringUtils.hasText(config.getDescription()) ? config.getDescription().trim() : null;

                SshHost existingHost = findExistingHost(resolvedGroupId, displayName, hostAddr, port);
                Long hostId;
                if (existingHost != null) {
                    hostId = existingHost.getId();
                    if (overwrite) {
                        SshHostService.SshHostSaveRequest updateReq = new SshHostService.SshHostSaveRequest();
                        updateReq.setGroupId(resolvedGroupId);
                        updateReq.setName(displayName);
                        updateReq.setHost(hostAddr);
                        updateReq.setPort(port);
                        updateReq.setRemark(hostRemark != null ? hostRemark : existingHost.getRemark());
                        updateReq.setEnabled(existingHost.getEnabled());
                        updateReq.setSortOrder(existingHost.getSortOrder());
                        hostService.update(hostId, updateReq);
                        result.setUpdatedHosts(result.getUpdatedHosts() + 1);
                    }
                } else {
                    SshHostService.SshHostSaveRequest createReq = new SshHostService.SshHostSaveRequest();
                    createReq.setGroupId(resolvedGroupId);
                    createReq.setName(displayName);
                    createReq.setHost(hostAddr);
                    createReq.setPort(port);
                    createReq.setRemark(hostRemark);
                    createReq.setEnabled(1);
                    createReq.setSortOrder(0);
                    SshHost created = hostService.create(createReq);
                    hostId = created.getId();
                    result.setImportedHosts(result.getImportedHosts() + 1);
                }

                // 3. Resolve Account
                String username = StringUtils.hasText(config.getUser_name()) ? config.getUser_name().trim() : "root";
                String decryptedPassword = FinalShellCipher.decodePassword(config.getPassword());
                if (decryptedPassword == null) {
                    decryptedPassword = "";
                }

                CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload();
                secret.setPassword(decryptedPassword);

                String accountRemark = "FinalShell 导入";
                if (StringUtils.hasText(config.getTerminal_encoding())) {
                    accountRemark += " (" + config.getTerminal_encoding().trim() + ")";
                }

                SshAccount existingAccount = findExistingAccount(hostId, username);
                if (existingAccount != null) {
                    if (overwrite) {
                        SshAccountService.SshAccountSaveRequest accUpdate = new SshAccountService.SshAccountSaveRequest();
                        accUpdate.setHostId(hostId);
                        accUpdate.setUsername(username);
                        accUpdate.setAuthType(SshAuthType.PASSWORD);
                        accUpdate.setSecret(secret);
                        accUpdate.setRemark(accountRemark);
                        accUpdate.setEnabled(existingAccount.getEnabled());
                        accUpdate.setSortOrder(existingAccount.getSortOrder());
                        accountService.update(existingAccount.getId(), accUpdate);
                        result.setUpdatedAccounts(result.getUpdatedAccounts() + 1);
                    }
                } else {
                    SshAccountService.SshAccountSaveRequest accCreate = new SshAccountService.SshAccountSaveRequest();
                    accCreate.setHostId(hostId);
                    accCreate.setUsername(username);
                    accCreate.setAuthType(SshAuthType.PASSWORD);
                    accCreate.setSecret(secret);
                    accCreate.setRemark(accountRemark);
                    accCreate.setEnabled(1);
                    accCreate.setSortOrder(0);
                    accountService.create(accCreate);
                    result.setImportedAccounts(result.getImportedAccounts() + 1);
                }

            } catch (Exception e) {
                log.error("Failed to import config: {} ({})", config.getName(), config.getHost(), e);
                result.setFailedCount(result.getFailedCount() + 1);
                result.getErrors().add("导入主机 " + (config.getName() != null ? config.getName() : config.getHost()) + " 失败: " + e.getMessage());
            }
        }

        return result;
    }

    private List<String> resolveSegments(FinalShellConfig config, Map<String, FinalShellFolder> folderMap) {
        // If folderMap has folder for config.parent_id, build hierarchy from folderMap
        if (folderMap != null && !folderMap.isEmpty() && StringUtils.hasText(config.getParent_id())) {
            List<String> list = new ArrayList<>();
            String currentId = config.getParent_id();
            Set<String> visited = new HashSet<>();
            while (StringUtils.hasText(currentId) && folderMap.containsKey(currentId) && !visited.contains(currentId)) {
                visited.add(currentId);
                FinalShellFolder folder = folderMap.get(currentId);
                if (folder != null && StringUtils.hasText(folder.getName())) {
                    list.add(0, folder.getName().trim());
                    currentId = folder.getParent_id();
                } else {
                    break;
                }
            }
            if (!list.isEmpty()) {
                return list;
            }
        }

        // Fallback to folderSegments derived from directory structure
        if (config.getFolderSegments() != null && !config.getFolderSegments().isEmpty()) {
            return config.getFolderSegments();
        }

        return Collections.emptyList();
    }

    private Long resolveGroup(Long targetGroupId, List<String> segments, Map<String, Long> groupPathCache, FinalShellImportResult result) {
        if (segments == null || segments.isEmpty()) {
            if (targetGroupId != null) {
                return targetGroupId;
            }
            // If targetGroupId is null and no segments, place into default group "FinalShell 导入"
            String defaultKey = "ROOT/FinalShell 导入";
            if (groupPathCache.containsKey(defaultKey)) {
                return groupPathCache.get(defaultKey);
            }
            SshGroup defaultGroup = findSiblingGroup(null, "FinalShell 导入");
            if (defaultGroup == null) {
                SshGroupService.SshGroupSaveRequest req = new SshGroupService.SshGroupSaveRequest();
                req.setParentId(null);
                req.setName("FinalShell 导入");
                defaultGroup = groupService.create(req);
                result.setCreatedGroups(result.getCreatedGroups() + 1);
            }
            groupPathCache.put(defaultKey, defaultGroup.getId());
            return defaultGroup.getId();
        }

        Long currentParentId = targetGroupId;
        StringBuilder pathKeyBuilder = new StringBuilder(targetGroupId != null ? String.valueOf(targetGroupId) : "ROOT");

        for (String segment : segments) {
            pathKeyBuilder.append("/").append(segment);
            String pathKey = pathKeyBuilder.toString();

            if (groupPathCache.containsKey(pathKey)) {
                currentParentId = groupPathCache.get(pathKey);
            } else {
                SshGroup existing = findSiblingGroup(currentParentId, segment);
                if (existing != null) {
                    currentParentId = existing.getId();
                } else {
                    SshGroupService.SshGroupSaveRequest req = new SshGroupService.SshGroupSaveRequest();
                    req.setParentId(currentParentId);
                    req.setName(segment);
                    SshGroup created = groupService.create(req);
                    currentParentId = created.getId();
                    result.setCreatedGroups(result.getCreatedGroups() + 1);
                }
                groupPathCache.put(pathKey, currentParentId);
            }
        }

        return currentParentId;
    }

    private SshGroup findSiblingGroup(Long parentId, String name) {
        LambdaQueryWrapper<SshGroup> q = new LambdaQueryWrapper<>();
        if (parentId == null) {
            q.isNull(SshGroup::getParentId);
        } else {
            q.eq(SshGroup::getParentId, parentId);
        }
        q.eq(SshGroup::getName, name.trim());
        List<SshGroup> list = groupMapper.selectList(q);
        return list.isEmpty() ? null : list.get(0);
    }

    private SshHost findExistingHost(Long groupId, String name, String host, int port) {
        LambdaQueryWrapper<SshHost> q = new LambdaQueryWrapper<>();
        q.eq(SshHost::getGroupId, groupId);
        q.eq(SshHost::getName, name);
        q.eq(SshHost::getHost, host);
        q.eq(SshHost::getPort, port);
        List<SshHost> list = hostMapper.selectList(q);
        return list.isEmpty() ? null : list.get(0);
    }

    private SshAccount findExistingAccount(Long hostId, String username) {
        LambdaQueryWrapper<SshAccount> q = new LambdaQueryWrapper<>();
        q.eq(SshAccount::getHostId, hostId).eq(SshAccount::getUsername, username.trim());
        List<SshAccount> list = accountMapper.selectList(q);
        return list.isEmpty() ? null : list.get(0);
    }
}
