package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.entity.SshScript;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doumiot.sshconsole.mapper.SshScriptMapper;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SshConsoleEnabled
@Service
public class SshScriptService {

    @Resource
    private SshScriptMapper sshScriptMapper;
    @Resource
    private SshHostService sshHostService;
    @Resource
    private SshSessionRegistry sessionRegistry;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private SshConsoleProperties sshConsoleProperties;
    @Resource
    private SshScriptScheduleService scheduleService;

    @Resource private SshScriptExecutionService executions;

    public List<SshScriptVO> listForHost(Long hostId) {
        LambdaQueryWrapper<SshScript> q = new LambdaQueryWrapper<>();
        if (hostId != null) {
            q.and(w -> w.isNull(SshScript::getHostId).or().eq(SshScript::getHostId, hostId));
        }
        q.orderByAsc(SshScript::getSortOrder).orderByDesc(SshScript::getId);
        List<SshScript> rows = sshScriptMapper.selectList(q);
        rows.sort(Comparator.comparing(SshScript::getSortOrder, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(SshScript::getId));
        List<SshScriptVO> out = new ArrayList<>();
        for (SshScript row : rows) {
            out.add(toVo(row));
        }
        return out;
    }

    public SshScriptVO getById(long id) {
        return toVo(requireScript(id));
    }

    @Transactional
    public SshScriptVO create(SshScriptSaveRequest req) {
        validate(req, true);
        if (req.getHostId() != null && sshHostService.getById(req.getHostId()) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机不存在");
        }
        SshScript row = new SshScript();
        apply(row, req);
        sshScriptMapper.insert(row);
        scheduleService.save(row, req.getSchedule());
        return toVo(row);
    }

    @Transactional
    public SshScriptVO update(long id, SshScriptSaveRequest req) {
        SshScript existing = requireScript(id);
        validate(req, false);
        if (req.getHostId() != null && sshHostService.getById(req.getHostId()) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机不存在");
        }
        apply(existing, req);
        sshScriptMapper.updateById(existing);
        scheduleService.save(existing, req.getSchedule());
        return toVo(existing);
    }

    public int delete(long id) {
        requireScript(id);
        return sshScriptMapper.deleteById(id);
    }

    public ScriptRunResult run(long scriptId, String sessionToken) throws IOException {
        Map<String,Object> row = executions.start(scriptId, sessionRegistry.get(sessionToken).getAccountId(), "MANUAL");
        while (isActive(row)) {
            try { Thread.sleep(250); } catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
            row = executions.detail((String)row.get("id"));
        }
        ScriptRunResult result = new ScriptRunResult();
        result.setExecutionId((String)row.get("id"));
        result.setExitStatus(row.get("exit_status") == null ? -1 : ((Number)row.get("exit_status")).intValue());
        result.setStdout((String)row.get("stdout"));
        result.setStderr((String)row.get("stderr") + (row.get("error")==null?"":"\n"+row.get("error")));
        result.setWorkDirDisplay(workDirLabel(requireScript(scriptId).getWorkDir()));
        return result;
    }

    private boolean isActive(Map<String,Object> row) {
        return java.util.Arrays.asList("QUEUED","RUNNING","CANCELLING").contains(row.get("status"));
    }

    public void runStreaming(long scriptId, String sessionToken, OutputStream out) throws IOException {
        Map<String,Object> row = executions.start(scriptId, sessionRegistry.get(sessionToken).getAccountId(), "MANUAL");
        String id=(String)row.get("id");
        emitLine(out,event("meta","executionId",id));
        int stdout=0,stderr=0;
        while (true) {
            row=executions.detail(id);
            String a=(String)row.get("stdout"), b=(String)row.get("stderr");
            if(a.length()>stdout) emitLine(out,event("stdout","text",a.substring(stdout)));
            if(b.length()>stderr) emitLine(out,event("stderr","text",b.substring(stderr)));
            stdout=a.length();stderr=b.length();
            if(!isActive(row)) break;
            try {Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        Map<String,Object> done=event("done","exitStatus",row.get("exit_status"));
        done.put("status",row.get("status"));done.put("executionId",id);emitLine(out,done);
    }

    private void emitStreamChunk(OutputStream out, String channel, byte[] chunk) {
        try {
            String text = new String(chunk, StandardCharsets.UTF_8);
            Map<String, Object> ev = new HashMap<>();
            ev.put("type", channel);
            ev.put("text", text);
            emitLine(out, ev);
        } catch (IOException e) {
            /* client disconnected */
        }
    }

    private static Map<String, Object> event(String type, String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put(key, value);
        return m;
    }

    private void emitLine(OutputStream out, Map<String, Object> event) throws IOException {
        byte[] line = (objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(line);
        out.flush();
    }

    private int scriptRunTimeoutSec() {
        return sshConsoleProperties.getScriptRunTimeoutSec();
    }

    /** 先 cd 到工作目录，再 exec 解释器从 stdin 读脚本 */
    static String buildRunShellCommand(String interpreter, String workDir) {
        String execInterp = normalizeInterpreterExec(interpreter);
        String cdPart;
        if (StringUtils.hasText(workDir)) {
            validateWorkDir(workDir.trim());
            cdPart = "cd " + shellSingleQuoted(workDir.trim());
        } else {
            cdPart = "cd \"${HOME:-~}\"";
        }
        return "/bin/sh -c " + shellSingleQuoted(cdPart + " && exec " + execInterp);
    }

    private static String normalizeInterpreterExec(String interpreter) {
        if (!StringUtils.hasText(interpreter) || "bash".equalsIgnoreCase(interpreter.trim())) {
            return "bash -s";
        }
        if ("sh".equalsIgnoreCase(interpreter.trim())) {
            return "sh -s";
        }
        throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "仅支持 bash 或 sh 解释器");
    }

    private static void validateWorkDir(String dir) {
        if (dir.contains("\n") || dir.contains("\r") || dir.contains(";") || dir.contains("|")
                || dir.contains("&") || dir.contains("`") || dir.contains("$(")) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "工作目录含有非法字符");
        }
    }

    private static String shellSingleQuoted(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String workDirLabel(String workDir) {
        return StringUtils.hasText(workDir) ? workDir.trim() : "~ (用户家目录)";
    }

    private SshScript requireScript(long id) {
        SshScript s = sshScriptMapper.selectById(id);
        if (s == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "脚本不存在");
        }
        return s;
    }

    private static void validate(SshScriptSaveRequest req, boolean create) {
        if (!StringUtils.hasText(req.getName())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "脚本名称必填");
        }
        if (!StringUtils.hasText(req.getContent())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "脚本内容必填");
        }
        normalizeInterpreterExec(req.getInterpreter());
        if (StringUtils.hasText(req.getWorkDir())) validateWorkDir(req.getWorkDir().trim());
    }

    private static void apply(SshScript row, SshScriptSaveRequest req) {
        row.setHostId(req.getHostId());
        row.setName(req.getName().trim());
        row.setInterpreter(StringUtils.hasText(req.getInterpreter()) ? req.getInterpreter().trim() : "bash");
        if (StringUtils.hasText(req.getContent())) {
            row.setContent(req.getContent());
        }
        row.setWorkDir(trimToNull(req.getWorkDir()));
        row.setRemark(trimToNull(req.getRemark()));
        row.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
    }

    private SshScriptVO toVo(SshScript row) {
        SshScriptVO vo = new SshScriptVO();
        vo.setId(row.getId());
        vo.setHostId(row.getHostId());
        vo.setName(row.getName());
        vo.setInterpreter(row.getInterpreter());
        vo.setContent(row.getContent());
        vo.setWorkDir(row.getWorkDir());
        vo.setRemark(row.getRemark());
        vo.setSortOrder(row.getSortOrder());
        vo.setSchedule(scheduleService.get(row.getId()));
        return vo;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Data
    public static class SshScriptSaveRequest {
        private SshScriptScheduleService.Schedule schedule;
        private Long hostId;
        private String name;
        private String interpreter;
        private String content;
        /** 空=用户家目录 */
        private String workDir;
        private String remark;
        private Integer sortOrder;
    }

    @Data
    public static class SshScriptVO {
        private SshScriptScheduleService.Schedule schedule;
        private Long id;
        private Long hostId;
        private String name;
        private String interpreter;
        private String content;
        private String workDir;
        private String remark;
        private Integer sortOrder;
    }

    @Data
    public static class ScriptRunResult {
        private String executionId;
        private int exitStatus;
        private String stdout;
        private String stderr;
        private String workDirDisplay;
    }
}
