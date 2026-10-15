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

    public static int parseOctalMode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        String s = raw.trim();
        if (s.startsWith("0") && s.length() > 1) {
            s = s.substring(1);
        }
        try {
            return Integer.parseInt(s, 8) & 0777;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * find -printf '%f\t%U\t%G\t%#m\n' 输出解析为 name → [owner, group, octalMode].
     */
    public static Map<String, String[]> parseFindOwnerLines(String stdout) {
        Map<String, String[]> map = new HashMap<>();
        if (stdout == null || stdout.isEmpty()) {
            return map;
        }
        for (String line : stdout.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int t1 = line.indexOf('\t');
            if (t1 < 0) {
                continue;
            }
            int t2 = line.indexOf('\t', t1 + 1);
            if (t2 < 0) {
                continue;
            }
            int t3 = line.indexOf('\t', t2 + 1);
            if (t3 < 0) {
                continue;
            }
            String name = line.substring(0, t1);
            String owner = line.substring(t1 + 1, t2);
            String group = line.substring(t2 + 1, t3);
            String mode = line.substring(t3 + 1).trim();
            map.put(name, new String[]{owner, group, mode});
        }
        return map;
    }

    public static String shellSingleQuote(String path) {
        if (path == null) {
            return "''";
        }
        return "'" + path.replace("'", "'\"'\"'") + "'";
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
                int id = Integer.parseInt(m.group(1));
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

    public static String resolveIdName(int id, Map<Integer, String> idToName) {
        if (id < 0) {
            return "-";
        }
        if (idToName != null) {
            String name = idToName.get(id);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return String.valueOf(id);
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
