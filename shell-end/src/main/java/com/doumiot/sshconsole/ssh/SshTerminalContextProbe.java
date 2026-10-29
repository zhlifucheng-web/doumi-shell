package com.doumiot.sshconsole.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A read-only Linux probe; its output never changes the authenticated SSH/SFTP identity. */
public final class SshTerminalContextProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SshTerminalContextProbe() {}

    public static String command(String panePidCommand) throws IOException {
        String script;
        try (InputStream input = SshTerminalContextProbe.class.getResourceAsStream("/ssh/terminal-context.py")) {
            if (input == null) throw new IOException("终端上下文探测资源缺失");
            script = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        String pid = panePidCommand == null ? "pid=''\n" : "pid=$(" + panePidCommand + ") || exit 1\n"
                + "case \"$pid\" in ''|*[!0-9]*) exit 1;; esac\n";
        return pid + "if command -v python3 >/dev/null 2>&1; then python=python3; "
                + "elif command -v python >/dev/null 2>&1; then python=python; "
                + "else printf '%s\\n' '{\"available\":false,\"status\":\"unsupported\",\"message\":\"目录跟随需要远端 Python 2.7 或 Python 3；原有 SFTP 功能仍可使用\"}'; exit 0; fi\n"
                + "exec \"$python\" - \"$pid\" <<'DOUMI_TERMINAL_CONTEXT_SCRIPT'\n" + script + "\nDOUMI_TERMINAL_CONTEXT_SCRIPT\n";
    }

    public static Context parse(String output) throws IOException {
        if (output == null || output.length() > 32768) throw new IOException("终端上下文响应无效");
        String[] lines = output.trim().split("\\r?\\n");
        JsonNode data = JSON.readTree(lines[lines.length - 1]);
        if (data == null || !data.isObject() || !data.path("available").isBoolean()) throw new IOException("终端上下文响应无效");
        Context context = new Context();
        context.setAvailable(data.path("available").asBoolean());
        context.setStatus(text(data, "status", 64)); context.setMessage(text(data,"message",1024));
        context.setFileUser(text(data,"fileUser",256)); context.setFileUid(id(data,"fileUid")); context.setFileGid(id(data,"fileGid"));
        context.setTerminalUser(text(data,"terminalUser",256)); context.setTerminalUid(id(data,"terminalUid")); context.setTerminalGid(id(data,"terminalGid"));
        if (context.isAvailable()) {
            String cwd = text(data,"cwd",16384);
            if (!cwd.startsWith("/") || context.getTerminalUid() == null || context.getTerminalGid() == null
                    || context.getFileUid() == null || context.getFileGid() == null || context.getTerminalUser().isEmpty())
                throw new IOException("终端上下文缺少绝对目录或有效用户身份");
            context.setCwd(cwd); context.setContextId(text(data,"contextId",256));
        }
        return context;
    }
    private static String text(JsonNode data, String field, int limit) throws IOException {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > limit || value.textValue().indexOf('\0') >= 0)
            throw new IOException("终端上下文字段无效: " + field);
        return value.textValue();
    }
    private static Long id(JsonNode data,String field) throws IOException {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > Integer.MAX_VALUE)
            throw new IOException("终端 UID/GID 超出支持范围");
        return value.longValue();
    }
    public static Context unavailable(String message) {
        Context context = new Context(); context.setStatus("unavailable"); context.setMessage(message); return context;
    }
    @Data public static class Context {
        private boolean available;
        private String status, message, cwd, terminalUser, fileUser, contextId;
        private Long terminalUid, terminalGid, fileUid, fileGid;
    }
}
