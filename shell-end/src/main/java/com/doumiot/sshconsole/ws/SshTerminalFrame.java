package com.doumiot.sshconsole.ws;

import lombok.Data;

/**
 * WebSocket JSON 帧（§4.4）。
 */
@Data
public class SshTerminalFrame {

    private String type;
    private String data;
    private Integer cols;
    private Integer rows;
    private String sessionToken;
    private String label;
    private String remark;
    private String connectionMode;
    private String code;
    private String message;

    public static SshTerminalFrame ready(String sessionToken, String label, String remark, String connectionMode) {
        SshTerminalFrame f = new SshTerminalFrame();
        f.setType("ready");
        f.setSessionToken(sessionToken);
        f.setLabel(label);
        f.setRemark(remark);
        f.setConnectionMode(connectionMode);
        return f;
    }

    public static SshTerminalFrame output(String base64) {
        SshTerminalFrame f = new SshTerminalFrame();
        f.setType("output");
        f.setData(base64);
        return f;
    }

    public static SshTerminalFrame pong() {
        SshTerminalFrame f = new SshTerminalFrame();
        f.setType("pong");
        return f;
    }

    public static SshTerminalFrame error(String code, String message) {
        SshTerminalFrame f = new SshTerminalFrame();
        f.setType("error");
        f.setCode(code);
        f.setMessage(message);
        return f;
    }
}
