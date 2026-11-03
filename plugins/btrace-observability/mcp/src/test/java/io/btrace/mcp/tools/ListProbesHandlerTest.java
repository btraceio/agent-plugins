package io.btrace.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.client.Client;
import io.btrace.mcp.ClientManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListProbesHandlerTest {
  @Test
  void reportsTheProbeThisServerIsConnectedTo() {
    deploy("70", 2070);
    Client session = Client.last;
    try {
      Map<String, Object> result = ListProbesHandler.execute(Map.of("pid", "70", "port", 2070));

      assertEquals(false, result.get("isError"), text(result));
      assertTrue(
          text(result).contains("Connected through this MCP server: a probe on port 2070"),
          text(result));
      assertTrue(text(result).contains("exit_probe"), text(result));
      assertTrue(text(result).contains("another client"), text(result));
      assertTrue(Client.last != session && Client.last.closed, "the list client must be closed");
      assertTrue(!session.closed, "listing must not touch the probe's own session");
    } finally {
      ExitProbeHandler.execute(Map.of("pid", "70", "port", 2070));
    }
  }

  @Test
  void reportsNoConnectedProbeWithoutASession() {
    Map<String, Object> result = ListProbesHandler.execute(Map.of("pid", "71", "port", 2071));

    assertEquals(false, result.get("isError"), text(result));
    assertTrue(text(result).contains("Connected through this MCP server: none"), text(result));
    assertTrue(text(result).contains("Detached (stop with stop_detached_probe):"), text(result));
    assertTrue(Client.last.closed, "the list client must be closed");
  }

  @Test
  void saysALostSessionsProbeIsListedAsDetached() throws Exception {
    deploy("72", 2072);
    ClientManager.getExistingClient("72", 2072).close();
    assertTrue(ClientManager.getOutput("72", 2072).awaitFinished(5000));

    Map<String, Object> result = ListProbesHandler.execute(Map.of("pid", "72", "port", 2072));

    assertEquals(false, result.get("isError"), text(result));
    assertTrue(text(result).contains("was lost"), text(result));
    assertTrue(text(result).contains("listed as detached"), text(result));
    assertEquals(null, ClientManager.getExistingClient("72", 2072), "the lost session is forgotten");
  }

  @Test
  void givesUpAndClosesTheClientWhenTheAgentNeverAnswers() {
    Client.hangListProbes = true;
    try {
      Map<String, Object> result =
          ListProbesHandler.execute(Map.of("pid", "73", "port", 2073), 200);

      assertEquals(true, result.get("isError"));
      assertTrue(text(result).contains("Timed out"), text(result));
      assertTrue(Client.last.closed, "client must be closed to release the connection");
    } finally {
      Client.hangListProbes = false;
    }
  }

  @Test
  void closesTheClientWhenAttachFails() {
    Client.failAttach = true;
    try {
      Map<String, Object> result = ListProbesHandler.execute(Map.of("pid", "74", "port", 2074));

      assertEquals(true, result.get("isError"));
      assertTrue(Client.last.closed, "client must be closed after a failed attach");
    } finally {
      Client.failAttach = false;
    }
  }

  private static void deploy(String pid, int port) {
    Map<String, Object> deployed =
        DeployOnelinerHandler.execute(
            Map.of("pid", pid, "oneliner", "orders.Api::create @return { print duration }", "port", port));
    assertEquals(false, deployed.get("isError"), text(deployed));
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    return (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
  }
}
