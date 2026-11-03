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
  void keepsASessionLostWhileTheListIsInFlightForTheNextCall() throws Exception {
    deploy("75", 2075);
    Client.duringListProbes =
        () -> {
          try {
            ClientManager.getExistingClient("75", 2075).close();
            assertTrue(ClientManager.getOutput("75", 2075).awaitFinished(5000));
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    try {
      Map<String, Object> first = ListProbesHandler.execute(Map.of("pid", "75", "port", 2075));
      assertTrue(text(first).contains("a probe on port 2075"), text(first));
      assertTrue(
          ClientManager.getExistingClient("75", 2075) != null,
          "a session this listing reported as live must not be forgotten");
    } finally {
      Client.duringListProbes = null;
    }

    Map<String, Object> second = ListProbesHandler.execute(Map.of("pid", "75", "port", 2075));
    assertTrue(text(second).contains("was lost"), text(second));
    assertEquals(null, ClientManager.getExistingClient("75", 2075), "reported, then forgotten");
  }

  @Test
  void givesUpAndClosesTheClientWhenTheAgentNeverAnswers() {
    deploy("73", 2073);
    Client session = Client.last;
    Client.hangListProbes = true;
    try {
      Map<String, Object> result =
          ListProbesHandler.execute(Map.of("pid", "73", "port", 2073), 200);

      assertEquals(true, result.get("isError"));
      assertTrue(text(result).contains("Timed out"), text(result));
      assertTrue(
          text(result).contains("Connected through this MCP server: a probe on port 2073"),
          text(result));
      assertTrue(Client.last != session, "the list must use its own client");
      assertTrue(Client.last.closed, "client must be closed to release the connection");
    } finally {
      Client.hangListProbes = false;
      ExitProbeHandler.execute(Map.of("pid", "73", "port", 2073));
    }
  }

  @Test
  void closesTheClientWhenAttachFails() {
    Client.failAttach = true;
    try {
      Map<String, Object> result = ListProbesHandler.execute(Map.of("pid", "74", "port", 2074));

      assertEquals(true, result.get("isError"));
      assertTrue(text(result).contains("attach failed"), text(result));
      assertTrue(text(result).contains("Connected through this MCP server: none"), text(result));
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
