package io.btrace.mcp;

import static io.btrace.mcp.McpStdioClient.isError;
import static io.btrace.mcp.McpStdioClient.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.btrace.mcp.tools.DeployScriptHandler;
import io.btrace.mcp.tools.ListProbesHandler;
import io.btrace.mcp.tools.StopDetachedProbeHandler;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import sample.orders.SampleOrderApp;

/**
 * A connection that goes silent without a reset. Stopping the target JVM with SIGSTOP keeps its TCP
 * connection open, because the kernel still acknowledges, while the agent cannot answer: the case
 * a socket read alone never notices. The client's heartbeats must end the session as lost, and once
 * the target resumes the probe must be detached, still running, and stoppable.
 */
@EnabledIfSystemProperty(named = "e2e.btraceJar", matches = ".+")
@DisabledOnOs(OS.WINDOWS)
class McpSilentDropE2ETest {
  private static final long INTERVAL_MILLIS = 500;
  private static final long TIMEOUT_MILLIS = 3_000;
  private static final String TARGET = SampleOrderApp.class.getName();

  private final File logDir = new File(System.getProperty("e2e.logDir", "build/e2e"));
  private Process sampleApp;
  private String pid;
  private int port;

  @BeforeEach
  void startSampleApp() throws Exception {
    // This JVM runs the btrace client; the target runs the agent.
    System.setProperty("btrace.heartbeat.interval", String.valueOf(INTERVAL_MILLIS));
    System.setProperty("btrace.heartbeat.timeout", String.valueOf(TIMEOUT_MILLIS));
    logDir.mkdirs();
    sampleApp =
        new ProcessBuilder(
                javaTool("java"),
                "-Dbtrace.heartbeat.timeout=" + TIMEOUT_MILLIS,
                "-cp",
                System.getProperty("e2e.sampleClasspath"),
                TARGET)
            .redirectError(new File(logDir, "silent-drop-app.err"))
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
    signal("CONT");
    sampleApp.destroy();
    if (!sampleApp.waitFor(10, TimeUnit.SECONDS)) {
      sampleApp.destroyForcibly();
    }
    System.clearProperty("btrace.heartbeat.interval");
    System.clearProperty("btrace.heartbeat.timeout");
  }

  @Test
  void aSilentTargetEndsTheSessionAndLeavesADetachedProbe() throws Exception {
    String probe = "E2ESilentDropProbe";
    Map<String, Object> deployed =
        DeployScriptHandler.execute(Map.of("pid", pid, "port", port, "script", script(probe)));
    assertFalse(isError(deployed), text(deployed));
    awaitRunning(probe);
    ProbeOutput output = ClientManager.getOutput(pid, port);

    signal("STOP");
    long stopped = System.nanoTime();
    try {
      assertTrue(
          output.awaitFinished(5 * TIMEOUT_MILLIS),
          "the session did not notice the silent target");
      long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped);
      assertTrue(tookMillis >= TIMEOUT_MILLIS / 2, "ended too early: " + tookMillis + " ms");
      assertFalse(output.hasExited(), "a lost connection was taken for a confirmed exit");
    } finally {
      signal("CONT");
    }

    awaitRunning(probe);
    Map<String, Object> listed = ListProbesHandler.execute(Map.of("pid", pid, "port", port));
    assertFalse(isError(listed), text(listed));
    assertTrue(text(listed).contains(probe), "not listed as detached:\n" + text(listed));
    Map<String, Object> stop =
        StopDetachedProbeHandler.execute(
            Map.of("pid", pid, "port", port, "probe_id", line(text(listed), probe)));
    assertFalse(isError(stop), text(stop));
    assertStopped(probe);
  }

  private void signal(String name) throws Exception {
    Process kill = new ProcessBuilder("kill", "-" + name, pid).redirectErrorStream(true).start();
    assertTrue(kill.waitFor(10, TimeUnit.SECONDS), "kill -" + name + " did not finish");
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
