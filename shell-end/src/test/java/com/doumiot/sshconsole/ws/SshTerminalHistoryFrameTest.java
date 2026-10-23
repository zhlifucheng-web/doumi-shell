package com.doumiot.sshconsole.ws;

import com.doumiot.sshconsole.service.SshTerminalService;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class SshTerminalHistoryFrameTest {
    @Test void readOnlyHistoryFailureIsNonFatalAndCorrelatedWithTheRequest() throws Exception {
        SshTerminalWebSocketHandler handler = new SshTerminalWebSocketHandler();
        SshTerminalService service = mock(SshTerminalService.class);
        ReflectionTestUtils.setField(handler, "sshTerminalService", service);
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sessionToken", "only-this-token"); attributes.put("readOnly", true);
        when(socket.getAttributes()).thenReturn(attributes); when(socket.isOpen()).thenReturn(true);
        when(service.readHistory("only-this-token", "UTF-8")).thenThrow(new IOException("history unavailable"));
        try {
            handler.handleTextMessage(socket, new TextMessage("{\"type\":\"history\",\"requestId\":42,\"encoding\":\"UTF-8\"}"));
            ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
            verify(socket, timeout(3000)).sendMessage(frame.capture());
            assertTrue(frame.getValue().getPayload().contains("\"type\":\"history\""));
            assertTrue(frame.getValue().getPayload().contains("\"requestId\":42"));
            assertTrue(frame.getValue().getPayload().contains("history unavailable"));
            verify(socket, never()).close(any());
            verify(service, never()).writeInput(anyString(), any());
        } finally { handler.shutdown(); }
    }

    @Test void successReturnsASnapshotWithoutWritingItIntoTheLiveOutputStream() throws Exception {
        SshTerminalWebSocketHandler handler = new SshTerminalWebSocketHandler();
        SshTerminalService service = mock(SshTerminalService.class);
        ReflectionTestUtils.setField(handler, "sshTerminalService", service);
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>(); attributes.put("sessionToken", "owner-token");
        when(socket.getAttributes()).thenReturn(attributes); when(socket.isOpen()).thenReturn(true);
        SshRemoteExec.ExecResult history = new SshRemoteExec.ExecResult();
        history.setStdout("old 中文\n"); history.setTruncated(true);
        when(service.readHistory("owner-token", "GBK")).thenReturn(history);
        try {
            handler.handleTextMessage(socket, new TextMessage("{\"type\":\"history\",\"requestId\":7,\"encoding\":\"GBK\"}"));
            ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
            verify(socket, timeout(3000)).sendMessage(frame.capture());
            assertTrue(frame.getValue().getPayload().contains("\"type\":\"history\""));
            assertTrue(frame.getValue().getPayload().contains("old 中文"));
            assertTrue(frame.getValue().getPayload().contains("\"truncated\":true"));
        } finally { handler.shutdown(); }
    }
}
