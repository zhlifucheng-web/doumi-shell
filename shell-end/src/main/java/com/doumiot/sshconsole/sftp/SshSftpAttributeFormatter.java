package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.xfer.FilePermission;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SFTP 列表项权限与 ls -l 风格展示。
 */
public final class SshSftpAttributeFormatter {

    private SshSftpAttributeFormatter() {
    }

    public static String formatPermissions(FileAttributes attrs, boolean dir, boolean symlink) {
        if (attrs == null) {
            return typeChar(dir, symlink) + "---------";
        }
        Set<FilePermission> perms = attrs.getPermissions();
        int mask = perms == null ? 0 : (FilePermission.toMask(perms) & 0777);
        return formatPermissionMask(mask, dir, symlink);
    }

    public static String formatPermissionMask(int permMask, boolean dir, boolean symlink) {
        int p = permMask & 0777;
        return typeChar(dir, symlink) + triplet((p >> 6) & 7) + triplet((p >> 3) & 7) + triplet(p & 7);
    }

    private static final Pattern ID_NAME_LINE = Pattern.compile("^(\\d+):(.+)$");

    /**
     * 解析 getent passwd/group 经 awk 输出的 {@code id:name} 行。
     */
    public static Map<Integer, String> parseIdNameLines(String stdout) {
        Map<Integer, String> map = new HashMap<>();
        if (stdout == null || stdout.isEmpty()) {
            return map;
        }
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = ID_NAME_LINE.matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            try {
                int id = Integer.parseUnsignedInt(m.group(1));
                String name = m.group(2).trim();
                if (!name.isEmpty()) {
                    map.put(id, name);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return map;
    }

    public static String resolveIdName(Integer id, Map<Integer, String> idToName) {
        if (id == null) {
            return "—";
        }
        if (idToName != null) {
            String name = idToName.get(id);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return Integer.toUnsignedString(id);
    }

    private static char typeChar(boolean dir, boolean symlink) {
        if (symlink) {
            return 'l';
        }
        return dir ? 'd' : '-';
    }

    private static String triplet(int v) {
        return ((v & 4) != 0 ? "r" : "-")
                + ((v & 2) != 0 ? "w" : "-")
                + ((v & 1) != 0 ? "x" : "-");
    }
}
