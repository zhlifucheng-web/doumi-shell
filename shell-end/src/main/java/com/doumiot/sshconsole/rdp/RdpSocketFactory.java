package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.protocol.*;
import java.net.*;
import java.util.Arrays;

/** Shared session/test negotiation; callers own cancellation and closing the transport. */
final class RdpSocketFactory {
    private RdpSocketFactory() {}
    static GuacamoleSocket open(Socket socket, RdpProperties properties, RdpTarget target,
                            int width, int height, int dpi, int timeoutMs) throws Exception {
        socket.connect(new InetSocketAddress(properties.getGuacdHost(), properties.getGuacdPort()), Math.min(5000, timeoutMs));
        socket.setSoTimeout(timeoutMs); socket.setTcpNoDelay(true);
        GuacamoleConfiguration config = new GuacamoleConfiguration();
        config.setProtocol("rdp");
        config.setParameter("hostname", target.getHost());
        config.setParameter("port", Integer.toString(target.getPort()));
        config.setParameter("username", target.getUsername());
        config.setParameter("password", target.getPassword());
        config.setParameter("domain", target.getDomain() == null ? "" : target.getDomain());
        config.setParameter("security", "nla");
        config.setParameter("ignore-cert", Boolean.toString(target.isIgnoreCertificate()));
        config.setParameter("resize-method", "display-update");
        config.setParameter("enable-drive", "false");
        config.setParameter("disable-audio", "true");
        config.setParameter("enable-audio-input", "false");
        config.setParameter("disable-copy", "true");
        config.setParameter("disable-paste", "true");
        GuacamoleClientInformation info = new GuacamoleClientInformation();
        info.setOptimalScreenWidth(width); info.setOptimalScreenHeight(height); info.setOptimalResolution(dpi);
        info.getImageMimetypes().addAll(Arrays.asList("image/png", "image/jpeg", "image/webp"));
        return new ConfiguredGuacamoleSocket(new RdpGuacamoleSocket(socket), config, info);
    }
}
