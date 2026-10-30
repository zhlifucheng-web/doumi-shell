package com.doumiot.sshconsole.rdp;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Resolved server-side credentials. Never returned by an HTTP endpoint. */
public final class RdpTarget {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String domain;
    private final boolean ignoreCertificate;

    public RdpTarget(String host, int port, String username, String password, String domain, boolean ignoreCertificate) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.ignoreCertificate = ignoreCertificate;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    @JsonIgnore public String getPassword() { return password; }
    public String getDomain() { return domain; }
    public boolean isIgnoreCertificate() { return ignoreCertificate; }
}
