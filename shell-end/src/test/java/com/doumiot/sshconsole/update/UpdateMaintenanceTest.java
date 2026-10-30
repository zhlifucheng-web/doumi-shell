package com.doumiot.sshconsole.update;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UpdateMaintenanceTest {
 @Test void blocksNewWorkUntilPreparationIsCancelled() {
  UpdateMaintenance gate=new UpdateMaintenance();assertTrue(gate.enter());gate.begin();
  assertFalse(gate.enter());assertEquals(1,gate.active());gate.leave();assertEquals(0,gate.active());
  gate.end();assertTrue(gate.enter());gate.leave();
 }
}
