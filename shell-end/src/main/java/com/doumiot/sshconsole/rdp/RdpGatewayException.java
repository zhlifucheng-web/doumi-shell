package com.doumiot.sshconsole.rdp;

/** Identifies gateway transport failures separately from Windows RDP failures. */
final class RdpGatewayException extends org.apache.guacamole.GuacamoleServerException {
    RdpGatewayException(Throwable cause) { super("[GUACD_UNAVAILABLE]", cause); }
}
