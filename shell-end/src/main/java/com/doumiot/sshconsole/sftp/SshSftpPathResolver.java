package com.doumiot.sshconsole.sftp;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * SFTP 路径规范化：拒绝 {@code ..} 逃逸；若配置了 default_cwd 则限制在其下。
 */
public final class SshSftpPathResolver {

    private SshSftpPathResolver() {
    }

    public static String resolveForList(String path, String defaultCwd) {
        return resolveForList(path, defaultCwd, null);
    }

    public static String resolveForList(String path, String defaultCwd, String homeDir) {
        if (!StringUtils.hasText(path)) {
            if (StringUtils.hasText(defaultCwd)) {
                return normalizePosix(defaultCwd.trim(), defaultCwd);
            }
            if (StringUtils.hasText(homeDir)) {
                return normalizePosix(homeDir.trim(), null);
            }
            return "/";
        }
        if (isHomePath(path)) {
            if (StringUtils.hasText(homeDir)) {
                return normalizePosix(homeDir.trim(), defaultCwd);
            }
            if (StringUtils.hasText(defaultCwd)) {
                return normalizePosix(defaultCwd.trim(), defaultCwd);
            }
            return "/";
        }
        return resolve(path, defaultCwd, homeDir);
    }

    public static String resolve(String path, String defaultCwd) {
        return resolve(path, defaultCwd, null);
    }

    public static String resolve(String path, String defaultCwd, String homeDir) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "路径不能为空");
        }
        String trimmed = path.trim();
        if (isHomePath(trimmed)) {
            if (StringUtils.hasText(homeDir)) {
                trimmed = homeDir.trim();
            } else if (StringUtils.hasText(defaultCwd)) {
                trimmed = defaultCwd.trim();
            } else {
                trimmed = "/";
            }
        } else if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            String base = StringUtils.hasText(homeDir) ? homeDir.trim() :
                    (StringUtils.hasText(defaultCwd) ? defaultCwd.trim() : "/");
            String sub = trimmed.substring(2).trim();
            trimmed = base.endsWith("/") ? base + sub : base + "/" + sub;
        }
        rejectRawDotDotSegments(trimmed);
        String root = StringUtils.hasText(defaultCwd) ? normalizePosix(defaultCwd.trim(), defaultCwd) : null;
        String resolved = normalizePosix(trimmed, root);
        if (root != null && !isUnderRoot(resolved, root)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "路径超出允许的目录范围");
        }
        return resolved;
    }

    public static String resolveChildDir(String parentDir, String name, String defaultCwd) {
        return resolveChildDir(parentDir, name, defaultCwd, null);
    }

    public static String resolveChildDir(String parentDir, String name, String defaultCwd, String homeDir) {
        if (!StringUtils.hasText(name) || name.contains("/") || name.contains("\\")) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的文件名");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的文件名");
        }
        String parent = resolveForList(parentDir, defaultCwd, homeDir);
        String combined = parent.endsWith("/") ? parent + name : parent + "/" + name;
        return resolve(combined, defaultCwd, homeDir);
    }

    private static boolean isHomePath(String path) {
        if (path == null) {
            return false;
        }
        String t = path.trim();
        return "~".equals(t) || "~/".equals(t) || "~\\".equals(t);
    }

    private static void rejectRawDotDotSegments(String path) {
        String[] parts = path.split("/");
        for (String p : parts) {
            if ("..".equals(p)) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "路径不允许包含 ..");
            }
        }
    }

    private static String normalizePosix(String path, String forcedRoot) {
        boolean absolute = path.startsWith("/");
        List<String> stack = new ArrayList<>();
        if (forcedRoot != null && !absolute) {
            for (String seg : forcedRoot.split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                stack.add(seg);
            }
        }
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (stack.isEmpty()) {
                    throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "路径不允许包含 ..");
                }
                stack.remove(stack.size() - 1);
                continue;
            }
            stack.add(seg);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", stack);
    }

    private static boolean isUnderRoot(String resolved, String root) {
        if (root.equals("/")) {
            return true;
        }
        return resolved.equals(root) || resolved.startsWith(root + "/");
    }
}
