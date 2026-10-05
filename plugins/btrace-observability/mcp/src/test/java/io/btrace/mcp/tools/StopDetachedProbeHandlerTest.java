package io.btrace.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.client.Client;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StopDetachedProbeHandlerTest {
  @Test
  void stopsTheProbeNamedByAListProbesLine() {
    Map<String, Object> result =
        StopDetachedProbeHandler.execute(args("1: 2f0c-probe [io.example.Trace]"));

    assertEquals(false, result.get("isError"));
    assertEquals("42", Client.last.attachedPid);
    assertEquals("localhost:2f0c-probe", Client.last.exitedProbe);
  }

  @Test
  void givesUpAndClosesTheClientWhenTheAgentNeverConfirms() {
    Map<String, Object> result = StopDetachedProbeHandler.execute(args("hang"), 200);

    assertEquals(true, result.get("isError"));
    assertTrue(text(result).contains("Timed out"), text(result));
    assertTrue(Client.last.closed, "client must be closed to release the connection");
  }

  private static Map<String, Object> args(String probeId) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("pid", "42");
    args.put("probe_id", probeId);
    return args;
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    return (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
  }
}
