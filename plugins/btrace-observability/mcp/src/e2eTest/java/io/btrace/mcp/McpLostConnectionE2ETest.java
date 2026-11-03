package io.btrace.mcp;

import static io.btrace.mcp.McpStdioClient.isError;
import static io.btrace.mcp.McpStdioClient.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.btrace.mcp.tools.DeployScriptHandler;
import io.btrace.mcp.tools.ExitProbeHandler;
import io.btrace.mcp.tools.ListProbesHandler;
import io.btrace.mcp.tools.StopDetachedProbeHandler;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import sample.orders.SampleOrderApp;

/**
 * A probe outlives the connection to its client: the agent keeps it running and lists it like a
 * detached one. These tests rely on that against a live JVM, so the tools can point to it.
 */
@EnabledIfSystemProperty(named = "e2e.btraceJar", matches = ".+")
class McpLostConnectionE2ETest {
  private static final Duration STARTUP = Duration.ofMinutes(5);
  private static final Duration CALL = Duration.ofSeconds(90);
  private static final String TARGET = SampleOrderApp.class.getName();

  private final File logDir = new File(System.getProperty("e2e.logDir", "build/e2e"));
  private Process sampleApp;
  private String pid;
  private int port;

  @BeforeEach
  void startSampleApp() throws Exception {
    logDir.mkdirs();
    sampleApp =
        new ProcessBuilder(
                javaTool("java"), "-cp", System.getProperty("e2e.sampleClasspath"), TARGET)
            .redirectError(new File(logDir, "lost-connection-app.err"))
            .start();
    pid = String.valueOf(sampleApp.pid());
    BufferedReader out =
        new BufferedReader(new InputStreamReader(sampleApp.getInputStream(), StandardCharsets.UTF_8));
    assertEquals(SampleOrderApp.READY, out.readLine(), "sample app did not start");
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
  }

  @AfterEach
  void stopSampleApp() throws Exception {
    sampleApp.destroy();
    if (!sampleApp.waitFor(10, TimeUnit.SECONDS)) {
      sampleApp.destroyForcibly();
    }
  }

  /** The real client, in this JVM: closing its socket ends the command loop as a dropped one does. */
  @Test
  void aLostConnectionIsRecognizedAndTheProbeStaysReachable() throws Exception {
    String probe = "E2ELostClientProbe";
    Map<String, Object> deployed =
        DeployScriptHandler.execute(Map.of("pid", pid, "port", port, "script", script(probe)));
    assertFalse(isError(deployed), text(deployed));
    awaitRunning(probe);

    ProbeOutput output = ClientManager.getOutput(pid, port);
    ClientManager.getExistingClient(pid, port).close();
    assertTrue(output.awaitFinished(10_000), "the command loop did not end");
    assertFalse(output.hasExited(), "a lost connection was taken for a confirmed exit");

    Map<String, Object> retried =
        DeployScriptHandler.execute(Map.of("pid", pid, "port", port, "script", script("E2ESecond")));
    assertTrue(isError(retried), text(retried));
    assertTrue(text(retried).contains("may still be running"), text(retried));
    Map<String, Object> exit = ExitProbeHandler.execute(Map.of("pid", pid, "port", port));
    assertTrue(isError(exit), "exit_probe cannot reach it: " + text(exit));
    awaitRunning(probe);

    Map<String, Object> listed = ListProbesHandler.execute(Map.of("pid", pid, "port", port));
    assertFalse(isError(listed), text(listed));
    assertTrue(text(listed).contains(probe), "not listed:\n" + text(listed));
    Map<String, Object> stopped =
        StopDetachedProbeHandler.execute(
            Map.of("pid", pid, "port", port, "probe_id", line(text(listed), probe)));
    assertFalse(isError(stopped), text(stopped));
    assertStopped(probe);
    assertEquals(null, ClientManager.getExistingClient(pid, port));
  }

  /** The server dies with no chance to clean up; the next one can still find and stop the probe. */
  @Test
  void aProbeLeftByACrashedServerIsFoundByTheNextOne() throws Exception {
    String probe = "E2ECrashedServerProbe";
    McpStdioClient first = server("lost-connection-first.err");
    try {
      Map<String, Object> deployed = tool(first, "deploy_script", "script", script(probe));
      assertFalse(isError(deployed), text(deployed));
      awaitRunning(probe);
    } finally {
      first.kill();
    }
    awaitRunning(probe);

    McpStdioClient second = server("lost-connection-second.err");
    try {
      Map<String, Object> listed = tool(second, "list_probes");
      assertFalse(isError(listed), text(listed));
      assertTrue(text(listed).contains(probe), "not listed:\n" + text(listed));

      Map<String, Object> stopped =
          tool(second, "stop_detached_probe", "probe_id", line(text(listed), probe));
      assertFalse(isError(stopped), text(stopped));
      assertStopped(probe);
    } finally {
      second.close();
    }
  }

  private McpStdioClient server(String logName) throws Exception {
    McpStdioClient client =
        new McpStdioClient(
            List.of(
                javaTool("java"),
                "--add-modules=jdk.attach",
                "-cp",
                System.getProperty("e2e.serverClasspath")
                    + File.pathSeparator
                    + System.getProperty("e2e.btraceJar"),
                BTraceMcpServer.class.getName()),
            new File(logDir, logName));
    client.initialize(STARTUP);
    return client;
  }

  private Map<String, Object> tool(McpStdioClient client, String name, Object... keyValues)
      throws Exception {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("pid", pid);
    arguments.put("port", port);
    for (int i = 0; i < keyValues.length; i += 2) {
      arguments.put((String) keyValues[i], keyValues[i + 1]);
    }
    return client.callTool(name, arguments, CALL);
  }

  private static String line(String listing, String probe) {
    return listing
        .lines()
        .filter(l -> l.contains(probe))
        .findFirst()
        .orElseThrow(() -> new AssertionError("not listed:\n" + listing));
  }

  private static String script(String name) {
    return "import io.btrace.core.annotations.*;\n"
        + "@BTrace public class "
        + name
        + " {\n"
        + "  @io.btrace.core.annotations.Export static long calls;\n"
        + "  @OnMethod(clazz = \""
        + TARGET
        + "\", method = \"process\")\n"
        + "  public static void onProcess() { calls++; }\n"
        + "}\n";
  }

  /** Requires the counter to keep moving, so a probe that merely ran earlier does not pass. */
  private void awaitRunning(String probe) throws Exception {
    long start = counter(probe);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    long value = start;
    while (System.nanoTime() < deadline) {
      value = counter(probe);
      if (value > start) {
        return;
      }
      Thread.sleep(200);
    }
    fail(probe + " is not firing; calls stayed at " + value);
  }

  private void assertStopped(String probe) throws Exception {
    Thread.sleep(1_000);
    long frozen = counter(probe);
    Thread.sleep(2_000);
    assertEquals(frozen, counter(probe), "probe still firing after stop_detached_probe");
  }

  private long counter(String probe) throws Exception {
    Process jcmd =
        new ProcessBuilder(javaTool("jcmd"), pid, "PerfCounter.print")
            .redirectErrorStream(true)
            .start();
    String suffix = "/" + probe + ".calls=";
    long value = -1;
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(jcmd.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        int at = line.indexOf(suffix);
        if (at >= 0) {
          value = Long.parseLong(line.substring(at + suffix.length()).trim());
        }
      }
    }
    assertTrue(jcmd.waitFor(30, TimeUnit.SECONDS), "jcmd did not finish");
    return value;
  }

  private static String javaTool(String name) {
    return new File(System.getProperty("java.home"), "bin/" + name).getAbsolutePath();
  }
}
