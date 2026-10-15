package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SshConsoleEnabled
@Service
public class SshProcessService {

    private static final String PS_CMD =
            "ps -eo user,pid,pcpu,pmem,rss,args --no-headers 2>/dev/null "
                    + "|| ps aux 2>/dev/null";

    @Resource
    private SshSessionRegistry sessionRegistry;

    public List<ProcessRow> listProcesses(String sessionToken) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        SshRemoteExec.ExecResult result =
                SshRemoteExec.exec(entry.getClient(), entry.getExecLock(), PS_CMD, 20);
        return parsePs(result.getStdout());
    }

    public KillResult killProcess(String sessionToken, long pid, String signal) throws IOException {
        if (pid <= 1 || pid > 4_194_304L) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效 PID");
        }
        int sig = 15;
        if ("KILL".equalsIgnoreCase(signal) || "9".equals(signal)) {
            sig = 9;
        } else if ("TERM".equalsIgnoreCase(signal) || "15".equals(signal) || signal == null || signal.isEmpty()) {
            sig = 15;
        } else {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "仅支持 TERM(15) 或 KILL(9)");
        }
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String cmd = "kill -" + sig + " " + pid + " 2>&1";
        SshRemoteExec.ExecResult result =
                SshRemoteExec.exec(entry.getClient(), entry.getExecLock(), cmd, 10);
        KillResult kr = new KillResult();
        kr.setPid(pid);
        kr.setSignal(sig);
        kr.setExitStatus(result.getExitStatus());
        kr.setMessage(StringUtils.hasText(result.getStderr()) ? result.getStderr().trim() : result.getStdout().trim());
        kr.setSuccess(result.getExitStatus() == 0);
        return kr;
    }

    private static List<ProcessRow> parsePs(String text) {
        List<ProcessRow> rows = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return rows;
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("USER")) {
                continue;
            }
            ProcessRow row = parseLine(line);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static ProcessRow parseLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 6) {
            return null;
        }
        try {
            ProcessRow row = new ProcessRow();
            row.setUser(parts[0]);
            row.setPid(Long.parseLong(parts[1]));
            row.setCpuPercent(Double.parseDouble(parts[2]));
            row.setMemPercent(Double.parseDouble(parts[3]));
            row.setRssKb(Long.parseLong(parts[4]));
            StringBuilder comm = new StringBuilder();
            for (int i = 5; i < parts.length; i++) {
                if (comm.length() > 0) {
                    comm.append(' ');
                }
                comm.append(parts[i]);
            }
            row.setCommand(comm.toString());
            return row;
        } catch (NumberFormatException e) {
            return parsePsAuxLine(line);
        }
    }

    /** ps aux 行：USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND */
    private static ProcessRow parsePsAuxLine(String line) {
        String[] parts = line.trim().split("\\s+", 11);
        if (parts.length < 11) {
            return null;
        }
        try {
            ProcessRow row = new ProcessRow();
            row.setUser(parts[0]);
            row.setPid(Long.parseLong(parts[1]));
            row.setCpuPercent(Double.parseDouble(parts[2]));
            row.setMemPercent(Double.parseDouble(parts[3]));
            row.setRssKb(Long.parseLong(parts[5]));
            row.setCommand(parts[10]);
            return row;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class ProcessRow {
        private String user;
        private long pid;
        private double cpuPercent;
        private double memPercent;
        private long rssKb;
        private String command;
    }

    @Data
    public static class KillResult {
        private long pid;
        private int signal;
        private boolean success;
        private int exitStatus;
        private String message;
    }
}
