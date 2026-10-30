package com.doumiot.sshconsole.rdp;

import org.junit.jupiter.api.*;
import org.springframework.web.socket.WebSocketSession;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RdpSessionTest {
    RdpProperties properties = new RdpProperties();
    RdpConnectionService profiles = mock(RdpConnectionService.class);
    MutableClock clock = new MutableClock();
    RdpSessionService sessions;
    @BeforeEach void setup() {
        sessions = new RdpSessionService(properties, profiles, clock);
        when(profiles.resolve(7L)).thenReturn(new RdpTarget("192.0.2.5",13389,"administrator","password","EXAMPLE",false));
    }
    @AfterEach void stop() { sessions.shutdown(); }
    @Test void ticketsAreOneUseAndCancellationReleasesCapacity() {
        properties.setMaxSessions(1);
        String token=sessions.issue(7,1024,768,96);
        assertThrows(RuntimeException.class,()->sessions.issue(7,1024,768,96));
        RdpSessionService.Ticket ticket=sessions.claim(token,mock(WebSocketSession.class));
        assertThrows(RuntimeException.class,()->sessions.claim(token,mock(WebSocketSession.class)));
        sessions.cancel(token);
        assertTrue(ticket.closed.get());
        assertNotEquals(token,sessions.issue(7,1024,768,96));
    }
    @Test void expiredTicketsCannotConnectAndDoNotExhaustCapacity() {
        properties.setMaxSessions(1);
        String token=sessions.issue(7,1024,768,96);
        clock.now=30001;
        assertThrows(RuntimeException.class,()->sessions.claim(token,mock(WebSocketSession.class)));
        assertNotNull(sessions.issue(7,1024,768,96));
    }
    @Test void disabledFeatureAndInvalidSizesCannotIssueTickets() {
        assertThrows(RuntimeException.class,()->sessions.issue(7,0,768,96));
        assertThrows(RuntimeException.class,()->sessions.issue(7,1024,9000,96));
        properties.setEnabled(false);
        assertThrows(RuntimeException.class,()->sessions.issue(7,1024,768,96));
        verifyNoInteractions(profiles);
    }
    @Test void profileIsCheckedAtIssueAndAgainAtConnection() {
        String token=sessions.issue(7,1024,768,96);
        RdpSessionService.Ticket ticket=sessions.claim(token,mock(WebSocketSession.class));
        when(profiles.resolve(7L)).thenThrow(new IllegalArgumentException("disabled"));
        assertThrows(Exception.class,()->sessions.open(ticket));
        verify(profiles,times(2)).resolve(7L);
        sessions.cancel(token);
    }
    static class MutableClock extends Clock {
        long now;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
}
