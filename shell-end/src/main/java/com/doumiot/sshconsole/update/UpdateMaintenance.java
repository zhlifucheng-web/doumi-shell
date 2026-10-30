package com.doumiot.sshconsole.update;
import org.springframework.stereotype.Component;
/** Serializes admission with maintenance entry, including requests that already started. */
@Component
public final class UpdateMaintenance {
    private boolean blocked;
    private int active;
    public synchronized boolean enter(){if(blocked)return false;active++;return true;}
    public synchronized void leave(){active--;notifyAll();}
    public synchronized int active(){return active;}
    public synchronized void begin(){blocked=true;}
    public synchronized void end(){blocked=false;}
    public synchronized boolean blocked(){return blocked;}
}
