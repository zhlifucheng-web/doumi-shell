package com.doumiot.sshconsole.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "doumi.ssh-console")
public class SshConsoleProperties {

    /** 为 false 时不注册 SSH 控制台 Controller */
    private boolean enabled = true;

    /** AES-256-GCM 主密钥，32 字节，Base64 或 Hex */
    private String credentialMasterKey = "";

    private int maxSessions = 50;

    private int idleTimeoutMinutes = 30;

    private int uploadMaxSizeMb = 512;

    /** 是否启用主机监控采样（关闭后 /host/metrics 仅返回空快照） */
    private boolean monitorEnabled = true;

    /** 同一 session 两次真实远程采样的最小间隔（毫秒），默认 5s */
    private int monitorMinIntervalMs = 5000;

    /** 磁盘 df 采样间隔（毫秒），默认 60s */
    private int monitorDiskIntervalMs = 60000;

    /**
     * 脚本执行 SSH 等待超时（秒）。{@code <= 0} 表示不限制，直到远端进程结束。
     */
    private int scriptRunTimeoutSec = 0;

    /**
     * Linux 终端优先使用 tmux 承载远端 shell。tmux 未安装时自动回退到普通 SSH shell。
     */
    private boolean tmuxEnabled = true;

    /** AI 单次请求最多执行的模型/工具循环次数。 */
    private int aiMaxRounds = 24;

    /** AI 会话最多保留的消息条数（含系统、助手、工具消息）。 */
    private int aiMaxContextMessages = 400;

    /** AI 会话上下文最大字符数，超过后拒绝继续请求。 */
    private int aiMaxContextChars = 1024 * 1024;

    /** AI 单轮最多允许的工具调用数量。 */
    private int aiMaxToolCalls = 64;
}
