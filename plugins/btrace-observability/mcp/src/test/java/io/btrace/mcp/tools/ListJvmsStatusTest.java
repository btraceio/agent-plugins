package io.btrace.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.mcp.ClientManager;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListJvmsStatusTest {
  private static final String NO_PROBE =
      "agent loaded, no probe from this server (list_probes shows detached ones)";
  private static final String PROBE = "agent loaded, probe deployed through this server";

  @Test
  void aJvmWithoutTheAgentSaysSo() {
    assertEquals("no agent", ListJvmsHandler.status("80", null));
  }

  @Test
  void theAgentStayingLoadedIsNotMistakenForAProbe() {
    assertEquals(NO_PROBE, ListJvmsHandler.status("81", "2081"));
  }

  @Test
  void followsAProbeThroughItsLife() {
    assertEquals(NO_PROBE, ListJvmsHandler.status("82", "2082"));

    assertFalse((Boolean) deploy("82", 2082).get("isError"));
    assertEquals(PROBE, ListJvmsHandler.status("82", "2082"));

    assertFalse((Boolean) ExitProbeHandler.execute(Map.of("pid", "82", "port", 2082)).get("isError"));
    assertEquals(NO_PROBE, ListJvmsHandler.status("82", "2082"));
  }

  @Test
  void aProbeThatExitedByItselfNoLongerCounts() throws Exception {
    assertFalse((Boolean) deploy("83", 2083).get("isError"));
    ClientManager.getExistingClient("83", 2083).sendExit(0);
    assertTrue(ClientManager.getOutput("83", 2083).awaitFinished(5000));

    assertEquals(NO_PROBE, ListJvmsHandler.status("83", "2083"));
    ClientManager.removeClient("83", 2083);
  }

  @Test
  void aLostConnectionStillCountsBecauseTheProbeMayBeRunning() throws Exception {
    assertFalse((Boolean) deploy("84", 2084).get("isError"));
    var output = ClientManager.getOutput("84", 2084);
    ClientManager.getExistingClient("84", 2084).close();
    assertTrue(output.awaitFinished(5000));

    assertEquals(PROBE, ListJvmsHandler.status("84", "2084"));
    ClientManager.removeClient("84", 2084);
  }

  @Test
  void aFailedAttachIsNotReportedAsNoAgent() {
    assertEquals("unknown (could not attach)", ListJvmsHandler.status("85", ListJvmsHandler.ATTACH_FAILED));
  }

  @Test
  void aProbeIsFoundEvenIfTheAgentRunsOnAnotherPort() {
    assertFalse((Boolean) deploy("86", 2086).get("isError"));
    assertEquals(PROBE, ListJvmsHandler.status("86", "3000"));
    assertEquals(PROBE, ListJvmsHandler.status("86", ListJvmsHandler.ATTACH_FAILED));
    ExitProbeHandler.execute(Map.of("pid", "86", "port", 2086));
  }

  private static Map<String, Object> deploy(String pid, int port) {
    return DeployOnelinerHandler.execute(
        Map.of("pid", pid, "oneliner", "orders.Api::create @return { print duration }", "port", port));
  }
}
