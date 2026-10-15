package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.*;
import com.doumiot.sshconsole.mapper.*;
import com.doumiot.sshconsole.ssh.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@SshConsoleEnabled
public class SshScriptScheduleService {
    @Resource private JdbcTemplate jdbc;
    @Resource private SshScriptMapper scripts;
    @Resource private SshAccountMapper accounts;
    @Resource private SshHostMapper hosts;
    @Resource private SshAccountService accountService;
    @Resource private SshClientFactory clients;
    @Resource private TaskScheduler taskScheduler;
    @Resource private SshConsoleProperties properties;
    private final Map<Long, Registration> registrations = new HashMap<>();
    @Resource private SshScriptExecutionService executions;
    private volatile boolean ready;

    @Data
    public static class Schedule {
        private Long accountId;
        private String cron;
        private String zone = "Asia/Shanghai";
        private boolean enabled;
        private String nextRunAt;
        private String lastRunAt;
        private String lastStatus;
        private String lastOutput;
    }

    public static String normalizeCron(String value) {
        if (!StringUtils.hasText(value)) throw invalid("请填写 cron 表达式");
        String cron = value.trim().replaceAll("\\s+", " ");
        if (cron.split(" ").length == 5) cron = "0 " + cron;
        if (!CronSequenceGenerator.isValidExpression(cron))
            throw invalid("cron 须为 5 位（分 时 日 月 周）或 6 位（秒 分 时 日 月 周）表达式");
        return cron;
    }

    public static TimeZone timeZone(String zone) {
        try { return TimeZone.getTimeZone(ZoneId.of(zone)); }
        catch (Exception e) { throw invalid("时区无效，请使用 Asia/Shanghai 等 IANA 时区"); }
    }

    public static List<String> preview(String cron, String zone) {
        TimeZone tz = timeZone(zone);
        CronSequenceGenerator generator = new CronSequenceGenerator(normalizeCron(cron), tz);
        Date date = new Date();
        List<String> dates = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                date = generator.next(date);
                dates.add(date.toInstant().atZone(tz.toZoneId()).toOffsetDateTime().toString());
            }
        } catch (IllegalArgumentException e) { throw invalid("cron 表达式没有可执行的日期"); }
        return dates;
    }

    public Schedule get(long scriptId) {
        List<Schedule> rows = jdbc.query("SELECT * FROM ssh_script_schedule WHERE script_id = ?",
                (rs, index) -> {
                    Schedule row = new Schedule();
                    row.setAccountId(rs.getLong("account_id"));
                    row.setCron(rs.getString("cron"));
                    row.setZone(rs.getString("zone"));
                    row.setEnabled(rs.getInt("enabled") == 1);
                    row.setLastRunAt(rs.getString("last_run_at"));
                    row.setLastStatus(rs.getString("last_status"));
                    row.setLastOutput(rs.getString("last_output"));
                    return row;
                }, scriptId);
        if (rows.isEmpty()) return null;
        Schedule row = rows.get(0);
        if (row.isEnabled()) {
            try { row.setNextRunAt(preview(row.getCron(), row.getZone()).get(0)); }
            catch (RuntimeException ignored) { /* 保留无效配置供用户修正 */ }
        }
        return row;
    }

    public void save(SshScript script, Schedule schedule) {
        if (schedule == null || !StringUtils.hasText(schedule.getCron())) {
            if (schedule != null && schedule.isEnabled()) throw invalid("启用定时执行前请填写 cron");
            jdbc.update("DELETE FROM ssh_script_schedule WHERE script_id = ?", script.getId());
            return;
        }
        String cron = normalizeCron(schedule.getCron());
        preview(cron, schedule.getZone());
        if (schedule.getAccountId() == null) throw invalid("请选择定时执行账户");
        SshAccount account = accounts.selectById(schedule.getAccountId());
        if (account == null) throw invalid("请选择有效的定时执行账户");
        if (script.getHostId() != null && !script.getHostId().equals(account.getHostId()))
            throw invalid("执行账户必须属于脚本绑定的主机");
        SshHost host = hosts.selectById(account.getHostId());
        if (schedule.isEnabled() && (Integer.valueOf(0).equals(account.getEnabled()) || host == null
                || Integer.valueOf(0).equals(host.getEnabled()))) throw invalid("执行账户或主机已禁用");
        jdbc.update("INSERT INTO ssh_script_schedule(script_id, account_id, cron, zone, enabled, created_at) VALUES(?,?,?,?,?,datetime('now','localtime')) "
                        + "ON CONFLICT(script_id) DO UPDATE SET account_id=excluded.account_id, cron=excluded.cron, "
                        + "zone=excluded.zone, enabled=excluded.enabled", script.getId(), schedule.getAccountId(),
                cron, schedule.getZone(), schedule.isEnabled() ? 1 : 0);
    }

    public List<Map<String, Object>> accountOptions() {
        return jdbc.queryForList("SELECT a.id, a.host_id AS hostId, a.username, h.name AS hostName "
                + "FROM ssh_account a JOIN ssh_host h ON a.host_id=h.id WHERE a.enabled=1 AND h.enabled=1 ORDER BY h.name,a.username");
    }

    @Order(3)
    @EventListener(ApplicationReadyEvent.class)
    public void start() { ready = true; reconcile(); }

    /** 保存事务提交后最多 2 秒应用新计划；执行不依赖浏览器会话。 */
    @Scheduled(fixedDelay = 2000)
    public synchronized void reconcile() {
        if (!ready) return;
        Set<Long> enabled = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM ssh_script_schedule WHERE enabled=1")) {
            long id = ((Number) row.get("script_id")).longValue();
            enabled.add(id);
            String signature = row.get("cron") + "|" + row.get("zone") + "|" + row.get("account_id");
            Registration old = registrations.get(id);
            if (old != null && old.signature.equals(signature)) continue;
            if (old != null) old.future.cancel(false);
            try {
                ScheduledFuture<?> future = taskScheduler.schedule(() -> dispatch(id),
                        new CronTrigger(normalizeCron((String) row.get("cron")), timeZone((String) row.get("zone"))));
                registrations.put(id, new Registration(signature, future));
            } catch (RuntimeException e) {
                registrations.remove(id);
                jdbc.update("UPDATE ssh_script_schedule SET enabled=0,last_status=? WHERE script_id=?", "INVALID_CRON", id);
                log.warn("Cannot schedule script {}: {}", id, e.getMessage());
            }
        }
        Iterator<Map.Entry<Long, Registration>> iterator = registrations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Registration> item = iterator.next();
            if (!enabled.contains(item.getKey())) { item.getValue().future.cancel(false); iterator.remove(); }
        }
    }

    private void dispatch(long id) {
        try {
            Schedule schedule = get(id);
            if (schedule != null && schedule.isEnabled()) executions.start(id, schedule.getAccountId(), "CRON");
        } catch (RuntimeException e) {
            jdbc.update("UPDATE ssh_script_schedule SET last_run_at=?,last_status='ERROR',last_output=? WHERE script_id=?", Instant.now().toString(), e.getMessage(), id);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        ready = false;
        registrations.values().forEach(registration -> registration.future.cancel(false));

    }

    private static BusinessRuntimeException invalid(String message) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, message);
    }

    private static class Registration {
        final String signature;
        final ScheduledFuture<?> future;
        Registration(String signature, ScheduledFuture<?> future) { this.signature = signature; this.future = future; }
    }
}
