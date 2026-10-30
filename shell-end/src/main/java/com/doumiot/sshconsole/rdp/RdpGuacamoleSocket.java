package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.io.*;
import org.apache.guacamole.net.GuacamoleSocket;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Uses a cancellable TCP socket with bounded connection/handshake timeouts. */
final class RdpGuacamoleSocket implements GuacamoleSocket {
    private final Socket socket;
    private final GuacamoleReader reader;
    private final GuacamoleWriter writer;
    RdpGuacamoleSocket(Socket socket) throws IOException {
        this.socket = socket;
        reader = new ReaderGuacamoleReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new WriterGuacamoleWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }
    public GuacamoleReader getReader() { return reader; }
    public GuacamoleWriter getWriter() { return writer; }
    public boolean isOpen() { return !socket.isClosed(); }
    public void close() throws GuacamoleException {
        try { socket.close(); } catch (IOException error) { throw new GuacamoleServerException("网关连接关闭失败", error); }
    }
}
