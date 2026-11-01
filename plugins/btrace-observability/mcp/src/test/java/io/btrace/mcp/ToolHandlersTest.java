package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.client.Client;
import io.btrace.mcp.tools.DeployOnelinerHandler;
import io.btrace.mcp.tools.DeployScriptHandler;
import io.btrace.mcp.tools.DetachProbeHandler;
import io.btrace.mcp.tools.ExitProbeHandler;
import io.btrace.mcp.tools.ListJvmsHandler;
import io.btrace.mcp.tools.ListProbesHandler;
import io.btrace.mcp.tools.ReadProbeOutputHandler;
import io.btrace.mcp.tools.SendEventHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolHandlersTest {
  @Test
  void schemasExposeOnlyTheRequiredInputs() {
    assertRequired(DeployOnelinerHandler.schema(), "pid", "oneliner");
    assertRequired(DeployScriptHandler.schema(), "pid", "script");
    assertRequired(ListProbesHandler.schema(), "pid");
    assertRequired(SendEventHandler.schema(), "pid");
    assertRequired(DetachProbeHandler.schema(), "pid");
    assertRequired(ExitProbeHandler.schema(), "pid");
    assertRequired(ReadProbeOutputHandler.schema(), "pid");
    assertEquals("list_jvms", ListJvmsHandler.schema().get("name"));
  }

  @Test
  void rejectsMissingRequiredArgumentsWithoutLoadingBtrace() {
    assertError(DeployOnelinerHandler.execute(Map.of()), "pid");
    assertError(DeployOnelinerHandler.execute(Map.of("pid", "1")), "oneliner");
    assertError(DeployScriptHandler.execute(Map.of()), "pid");
    assertError(DeployScriptHandler.execute(Map.of("pid", "1")), "script");
    assertError(ListProbesHandler.execute(Map.of()), "pid");
    assertError(SendEventHandler.execute(Map.of()), "pid");
    assertError(DetachProbeHandler.execute(Map.of()), "pid");
    assertError(ExitProbeHandler.execute(Map.of()), "pid");
    assertError(ReadProbeOutputHandler.execute(Map.of()), "pid");
  }

  @Test
  void listsJvmResultInMcpContentShape() {
    Map<String, Object> result = ListJvmsHandler.execute(Map.of());

    assertFalse((Boolean) result.get("isError"));
    assertTrue(text(result).contains("Java VMs"));
  }

  @Test
  void runsTheMaskedClientToolWorkflowWithFixtures() {
    Map<String, Object> oneliner =
        DeployOnelinerHandler.execute(
            Map.of("pid", "51", "oneliner", "orders.Api::create @return { print duration }", "port", 2051));
    assertFalse((Boolean) oneliner.get("isError"));
    assertTrue(text(oneliner).contains("Probe deployed successfully"));

    assertFalse((Boolean) SendEventHandler.execute(Map.of("pid", "51", "port", 2051)).get("isError"));
    assertFalse(
        (Boolean)
            SendEventHandler.execute(Map.of("pid", "51", "event_name", "snapshot", "port", 2051))
                .get("isError"));
    assertFalse((Boolean) DetachProbeHandler.execute(Map.of("pid", "51", "port", 2051)).get("isError"));

    Map<String, Object> script =
        DeployScriptHandler.execute(
            Map.of("pid", "52", "script", "class Trace {}", "args", List.of("one"), "port", 2052));
    assertFalse((Boolean) script.get("isError"));
    assertFalse((Boolean) ExitProbeHandler.execute(Map.of("pid", "52", "port", 2052)).get("isError"));

    Map<String, Object> probes = ListProbesHandler.execute(Map.of("pid", "53", "port", 2053));
    assertFalse((Boolean) probes.get("isError"));
    assertTrue(text(probes).contains("Active probes on PID 53"));
  }

  @Test
  void returnsWhatAProbePrintsAfterItStarts() {
    Client.laterOutput.add("processOrder 412ms\n");
    Client.laterOutput.add("chargeCard 398ms\n");
    Client.exitOutput = "chargeCard 305ms\n";
    try {
      assertFalse(
          (Boolean)
              DeployOnelinerHandler.execute(
                      Map.of("pid", "56", "oneliner", "orders.Api::create @return { print duration }", "port", 2056))
                  .get("isError"));

      // The probe prints on its own thread, so collect reads until both lines are in.
      StringBuilder printed = new StringBuilder();
      for (int i = 0; i < 10 && !printed.toString().contains("chargeCard 398ms"); i++) {
        Map<String, Object> read =
            ReadProbeOutputHandler.execute(Map.of("pid", "56", "port", 2056, "wait_seconds", 1));
        assertFalse((Boolean) read.get("isError"));
        printed.append(text(read));
      }
      assertTrue(printed.toString().contains("processOrder 412ms\n"), printed.toString());
      assertTrue(printed.toString().contains("chargeCard 398ms\n"), printed.toString());

      Map<String, Object> again = ReadProbeOutputHandler.execute(Map.of("pid", "56", "port", 2056));
      assertFalse((Boolean) again.get("isError"));
      assertTrue(text(again).contains("No new output"), text(again));
      assertFalse(text(again).contains("chargeCard 398ms"), text(again));

      Map<String, Object> exited = ExitProbeHandler.execute(Map.of("pid", "56", "port", 2056));
      assertFalse((Boolean) exited.get("isError"));
      assertTrue(text(exited).contains("Probe stopped and removed from PID 56"), text(exited));
      assertTrue(text(exited).contains("chargeCard 305ms"), text(exited));
      assertFalse(text(exited).contains("chargeCard 398ms"), "already-read output is not repeated");

      assertError(ReadProbeOutputHandler.execute(Map.of("pid", "56", "port", 2056)), "No active BTrace session");
    } finally {
      Client.laterOutput.clear();
      Client.exitOutput = "";
    }
  }

  @Test
  void readCanWaitForTheProbeToPrint() throws Exception {
    DeployOnelinerHandler.execute(
        Map.of("pid", "58", "oneliner", "orders.Api::create @return { print duration }", "port", 2058));
    Thread printer =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                return;
              }
              ClientManager.getOutput("58", 2058).append("authorize 488ms\n");
            });
    printer.start();

    Map<String, Object> read =
        ReadProbeOutputHandler.execute(Map.of("pid", "58", "port", 2058, "wait_seconds", 10));
    assertFalse((Boolean) read.get("isError"));
    assertTrue(text(read).contains("authorize 488ms"), text(read));
    printer.join();

    long start = System.nanoTime();
    Map<String, Object> capped =
        ReadProbeOutputHandler.execute(Map.of("pid", "58", "port", 2058, "wait_seconds", -5));
    assertTrue(text(capped).contains("No new output"), text(capped));
    assertTrue(System.nanoTime() - start < 1_000_000_000L, "a negative wait does not block");
    ExitProbeHandler.execute(Map.of("pid", "58", "port", 2058));
  }

  @Test
  void detachReturnsTheUnreadOutput() {
    Client.laterOutput.add("validateOrder threw IllegalStateException\n");
    try {
      DeployScriptHandler.execute(Map.of("pid", "57", "script", "class Trace {}", "port", 2057));
      Map<String, Object> detached = DetachProbeHandler.execute(Map.of("pid", "57", "port", 2057));
      assertFalse((Boolean) detached.get("isError"));
      assertTrue(text(detached).contains("validateOrder threw IllegalStateException"), text(detached));
    } finally {
      Client.laterOutput.clear();
    }
  }

  @Test
  void refusesASecondDeployWhileAProbeIsRunning() {
    Map<String, Object> first = deployOneliner("60", 2060);
    assertFalse((Boolean) first.get("isError"), text(first));
    BTraceClient running = ClientManager.getExistingClient("60", 2060);

    assertError(deployOneliner("60", 2060), "already running on PID 60");
    assertError(
        DeployScriptHandler.execute(Map.of("pid", "60", "script", "class Trace {}", "port", 2060)),
        "already running on PID 60");

    assertTrue(ClientManager.getExistingClient("60", 2060) == running, "the first probe stays tracked");
    assertFalse((Boolean) ExitProbeHandler.execute(Map.of("pid", "60", "port", 2060)).get("isError"));
    assertFalse((Boolean) deployOneliner("60", 2060).get("isError"), "free again after exit_probe");
    ExitProbeHandler.execute(Map.of("pid", "60", "port", 2060));
  }

  @Test
  void aDifferentPidIsNotAffectedByARunningProbe() {
    assertFalse((Boolean) deployOneliner("61", 2061).get("isError"));
    assertFalse((Boolean) deployOneliner("62", 2061).get("isError"));
    ExitProbeHandler.execute(Map.of("pid", "61", "port", 2061));
    ExitProbeHandler.execute(Map.of("pid", "62", "port", 2061));
  }

  @Test
  void aProbeThatExitedByItselfDoesNotBlockTheNextDeploy() throws Exception {
    Client.exitOutput = "last words\n";
    try {
      assertFalse((Boolean) deployOneliner("63", 2063).get("isError"));
      ClientManager.getExistingClient("63", 2063).sendExit(0);
      assertTrue(ClientManager.getOutput("63", 2063).awaitFinished(5000));
    } finally {
      Client.exitOutput = "";
    }

    Map<String, Object> next = deployOneliner("63", 2063);
    assertFalse((Boolean) next.get("isError"), text(next));
    assertTrue(text(next).contains("last words"), "the unread output is not lost: " + text(next));
    ExitProbeHandler.execute(Map.of("pid", "63", "port", 2063));
  }

  @Test
  void aLostConnectionIsNotMistakenForAStoppedProbe() throws Exception {
    Client.exitOutput = "";
    assertFalse((Boolean) deployOneliner("64", 2064).get("isError"));
    ProbeOutput output = ClientManager.getOutput("64", 2064);
    ClientManager.getExistingClient("64", 2064).close();
    assertTrue(output.awaitFinished(5000), "the end of the command loop finishes the session");

    // The agent keeps a probe running when its client goes away, so the deploy must say so.
    Map<String, Object> refused = deployOneliner("64", 2064);
    assertError(refused, "may still be running");
    assertTrue(text(refused).contains("list_probes"), text(refused));
    assertTrue(text(refused).contains("stop_detached_probe"), text(refused));

    // Now reachable through those tools, so the JVM is free for the next deploy.
    assertFalse((Boolean) deployOneliner("64", 2064).get("isError"));
    ExitProbeHandler.execute(Map.of("pid", "64", "port", 2064));
  }

  @Test
  void exitAfterALostConnectionDoesNotClaimTheProbeStopped() throws Exception {
    assertFalse((Boolean) deployOneliner("65", 2065).get("isError"));
    ProbeOutput output = ClientManager.getOutput("65", 2065);
    ClientManager.getExistingClient("65", 2065).close();
    assertTrue(output.awaitFinished(5000));

    assertError(ExitProbeHandler.execute(Map.of("pid", "65", "port", 2065)), "may still be running");
  }

  @Test
  void aFailedDeployKeepsTheUnreadOutputOfAProbeThatExited() throws Exception {
    Client.exitOutput = "last words\n";
    try {
      assertFalse((Boolean) deployOneliner("66", 2066).get("isError"));
      ClientManager.getExistingClient("66", 2066).sendExit(0);
      assertTrue(ClientManager.getOutput("66", 2066).awaitFinished(5000));
    } finally {
      Client.exitOutput = "";
    }

    Client.failAttach = true;
    try {
      assertTrue((Boolean) deployOneliner("66", 2066).get("isError"));
    } finally {
      Client.failAttach = false;
    }

    Map<String, Object> read = ReadProbeOutputHandler.execute(Map.of("pid", "66", "port", 2066));
    assertTrue(text(read).contains("last words"), text(read));
  }

  private static Map<String, Object> deployOneliner(String pid, int port) {
    return DeployOnelinerHandler.execute(
        Map.of("pid", pid, "oneliner", "orders.Api::create @return { print duration }", "port", port));
  }

  @Test
  void namesScriptSourceAfterItsDeclaredClass() {
    String script = "// this class traces orders\n/* class Ignored */\n@BTrace public class OrderTrace {}";
    assertFalse(
        (Boolean)
            DeployScriptHandler.execute(Map.of("pid", "54", "script", script, "port", 2054))
                .get("isError"));
    assertEquals("OrderTrace.java", Client.last.submittedFileName);
    ExitProbeHandler.execute(Map.of("pid", "54", "port", 2054));

    assertFalse(
        (Boolean)
            DeployScriptHandler.execute(Map.of("pid", "55", "script", "@BTrace", "port", 2055))
                .get("isError"));
    assertTrue(Client.last.submittedFileName.matches("BTraceScript_\\d+\\.java"));
    ExitProbeHandler.execute(Map.of("pid", "55", "port", 2055));
  }

  @SuppressWarnings("unchecked")
  private static void assertRequired(Map<String, Object> schema, String... expected) {
    assertEquals(List.of(expected), schema.get("inputSchema") instanceof Map
        ? ((Map<String, Object>) schema.get("inputSchema")).get("required")
        : null);
  }

  private static void assertError(Map<String, Object> result, String expected) {
    assertTrue((Boolean) result.get("isError"));
    assertTrue(text(result).contains(expected));
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }
}
