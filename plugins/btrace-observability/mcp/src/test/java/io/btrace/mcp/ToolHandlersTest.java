package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.mcp.tools.DeployOnelinerHandler;
import io.btrace.mcp.tools.DeployScriptHandler;
import io.btrace.mcp.tools.DetachProbeHandler;
import io.btrace.mcp.tools.ExitProbeHandler;
import io.btrace.mcp.tools.ListJvmsHandler;
import io.btrace.mcp.tools.ListProbesHandler;
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
