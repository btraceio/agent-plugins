package io.btrace.mcp;

import static io.btrace.mcp.McpStdioClient.isError;
import static io.btrace.mcp.McpStdioClient.names;
import static io.btrace.mcp.McpStdioClient.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import sample.orders.SampleOrderApp;
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
import java.util.function.LongPredicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Drives the real MCP server against a live sample JVM. The server is launched through JBang, as the
 * plugin manifests launch it, unless the build supplies a BTrace JAR (see build.gradle). Probe
 * effects are observed through {@code @Export} jvmstat counters read with {@code jcmd}, because no
 * MCP tool returns probe output after deployment.
 *
 * <p>The steps share one server and one target JVM and must run in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerE2ETest {
  private static final Duration STARTUP = Duration.ofMinutes(5);
  private static final Duration CALL = Duration.ofSeconds(90);
  private static final Duration PROBE_EFFECT = Duration.ofSeconds(15);

  private static final String TARGET = SampleOrderApp.class.getName();
  private static final String RUNNING_PROBE = "E2ERunningProbe";
  private static final String EXIT_PROBE = "E2EExitProbe";

  private Process sampleApp;
  private String pid;
  private int port;
  private McpStdioClient server;
  private Map<String, Object> initializeResult;

  @BeforeAll
  void startSampleAppAndServer() throws Exception {
    File logDir = new File(System.getProperty("e2e.logDir", "build/e2e"));
    logDir.mkdirs();

    sampleApp =
        new ProcessBuilder(javaTool("java"), "-cp", requiredProperty("e2e.sampleClasspath"), TARGET)
            .redirectError(new File(logDir, "sample-app.err"))
            .start();
    pid = String.valueOf(sampleApp.pid());
    awaitReady(sampleApp);

    // A dedicated agent port keeps the run independent of other BTrace sessions on this host.
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    server = new McpStdioClient(serverCommand(), new File(logDir, "mcp-server.err"));
    initializeResult = server.initialize(STARTUP);
  }

  @AfterAll
  void stopProcesses() throws Exception {
    if (server != null) {
      server.close();
    }
    if (sampleApp != null) {
      sampleApp.destroy();
      if (!sampleApp.waitFor(10, TimeUnit.SECONDS)) {
        sampleApp.destroyForcibly();
      }
    }
  }

  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void handshakeAdvertisesEveryTool() throws Exception {
    Map<String, Object> serverInfo = (Map<String, Object>) initializeResult.get("serverInfo");
    assertEquals("btrace-mcp-server", serverInfo.get("name"));

    List<String> tools = names(server.request("tools/list", new LinkedHashMap<>(), CALL).get("tools"));
    assertEquals(
        List.of(
            "list_jvms",
            "deploy_oneliner",
            "deploy_script",
            "list_probes",
            "send_event",
            "detach_probe",
            "exit_probe"),
        tools);
  }

  @Test
  @Order(2)
  @SuppressWarnings("unchecked")
  void servesEveryPromptForTheTarget() throws Exception {
    List<String> prompts =
        names(server.request("prompts/list", new LinkedHashMap<>(), CALL).get("prompts"));
    assertEquals(
        List.of("diagnose_slow_endpoint", "find_exception_source", "profile_method"), prompts);

    Map<String, Map<String, Object>> arguments = new LinkedHashMap<>();
    arguments.put(
        "diagnose_slow_endpoint", args("endpoint_class", TARGET, "endpoint_method", "process"));
    arguments.put(
        "find_exception_source", args("exception_class", "java.lang.IllegalStateException"));
    arguments.put("profile_method", args("class_name", TARGET, "method_name", "process"));
    for (Map.Entry<String, Map<String, Object>> prompt : arguments.entrySet()) {
      prompt.getValue().put("pid", pid);
      Map<String, Object> params = args("name", prompt.getKey(), "arguments", prompt.getValue());
      Map<String, Object> result = server.request("prompts/get", params, CALL);
      List<Object> messages = (List<Object>) result.get("messages");
      assertFalse(messages.isEmpty(), prompt.getKey() + " returned no messages");
      String rendered = McpProtocol.toJson(messages);
      assertTrue(rendered.contains(pid), prompt.getKey() + " does not mention the target PID");
    }
  }

  @Test
  @Order(3)
  void listJvmsFindsTheSampleApp() throws Exception {
    Map<String, Object> result = server.callTool("list_jvms", new LinkedHashMap<>(), CALL);
    assertSuccess(result);
    assertTrue(
        text(result).contains("PID: " + pid + "  |  Main Class: " + TARGET),
        "sample app missing from: " + text(result));
  }

  @Test
  @Order(4)
  void deployScriptInstrumentsTheTarget() throws Exception {
    Map<String, Object> result = tool("deploy_script", "script", runningProbeScript());
    assertSuccess(result);
    assertTrue(text(result).contains("Script deployed successfully to PID " + pid));
    awaitCounter(RUNNING_PROBE, "processCalls", calls -> calls > 0);
  }

  @Test
  @Order(5)
  void sendEventRunsTheNamedHandler() throws Exception {
    assertSuccess(tool("send_event", "event_name", "bump"));
    awaitCounter(RUNNING_PROBE, "events", events -> events == 1);
  }

  @Test
  @Order(6)
  void detachProbeLeavesTheProbeRunning() throws Exception {
    assertSuccess(tool("detach_probe"));

    Map<String, Object> afterDetach = tool("send_event", "event_name", "bump");
    assertTrue(isError(afterDetach), "session should be gone after detach");
    assertTrue(text(afterDetach).contains("No active BTrace session"));

    long calls = counter(RUNNING_PROBE, "processCalls");
    awaitCounter(RUNNING_PROBE, "processCalls", now -> now > calls);
  }

  @Test
  @Order(7)
  void deployOnelinerAndExitProbe() throws Exception {
    Map<String, Object> deployed =
        tool(
            "deploy_oneliner",
            "oneliner",
            TARGET + "::process @return if duration>50ms { print method, duration }");
    assertSuccess(deployed);
    assertTrue(text(deployed).contains("Probe deployed successfully to PID " + pid));

    assertSuccess(tool("exit_probe"));
    assertTrue(isError(tool("send_event")), "session should be gone after exit");
  }

  @Test
  @Order(8)
  void exitProbeRemovesTheInstrumentation() throws Exception {
    assertSuccess(tool("deploy_script", "script", exitProbeScript()));
    awaitCounter(EXIT_PROBE, "calls", calls -> calls > 0);

    assertSuccess(tool("exit_probe"));
    // Give in-flight handler invocations time to drain, then require the counter to stay frozen.
    Thread.sleep(1_000);
    long frozen = counter(EXIT_PROBE, "calls");
    Thread.sleep(2_000);
    assertEquals(frozen, counter(EXIT_PROBE, "calls"), "probe still firing after exit_probe");
  }

  @Test
  @Order(9)
  @Disabled(
      "BTrace Client.connectAndListProbes calls System.exit(0) after the reply, which terminates"
          + " the MCP server process; needs a BTrace client change")
  void listProbesReportsTheRunningProbe() throws Exception {
    Map<String, Object> result = tool("list_probes");
    assertSuccess(result);
    assertTrue(text(result).contains(RUNNING_PROBE));
    assertTrue(server.isAlive(), "list_probes terminated the MCP server");
  }

  @Test
  @Order(10)
  void targetSurvivesTheWholeSession() {
    assertTrue(sampleApp.isAlive(), "sample app died during the session");
    assertTrue(server.isAlive(), "MCP server died during the session");
  }

  private Map<String, Object> tool(String name, Object... keyValues) throws Exception {
    Map<String, Object> arguments = args(keyValues);
    arguments.put("pid", pid);
    arguments.put("port", port);
    return server.callTool(name, arguments, CALL);
  }

  private static String runningProbeScript() {
    return "import io.btrace.core.annotations.*;\n"
        + "@BTrace public class "
        + RUNNING_PROBE
        + " {\n"
        + "  @io.btrace.core.annotations.Export static long processCalls;\n"
        + "  @io.btrace.core.annotations.Export static long events;\n"
        + "  @OnMethod(clazz = \""
        + TARGET
        + "\", method = \"process\", location = @Location(Kind.RETURN))\n"
        + "  public static void onProcess() { processCalls++; }\n"
        + "  @OnEvent(\"bump\") public static void onBump() { events++; }\n"
        + "}\n";
  }

  private static String exitProbeScript() {
    return "import io.btrace.core.annotations.*;\n"
        + "@BTrace public class "
        + EXIT_PROBE
        + " {\n"
        + "  @io.btrace.core.annotations.Export static long calls;\n"
        + "  @OnMethod(clazz = \""
        + TARGET
        + "\", method = \"process\")\n"
        + "  public static void onProcess() { calls++; }\n"
        + "}\n";
  }

  private void awaitCounter(String probe, String field, LongPredicate condition) throws Exception {
    long deadline = System.nanoTime() + PROBE_EFFECT.toNanos();
    long value = -1;
    while (System.nanoTime() < deadline) {
      value = counter(probe, field);
      if (condition.test(value)) {
        return;
      }
      Thread.sleep(200);
    }
    fail(probe + "." + field + " did not reach the expected value; last=" + value);
  }

  /** Reads an {@code @Export} field of a probe from the target's jvmstat counters; -1 if absent. */
  private long counter(String probe, String field) throws Exception {
    Process jcmd =
        new ProcessBuilder(javaTool("jcmd"), pid, "PerfCounter.print")
            .redirectErrorStream(true)
            .start();
    String suffix = "/" + probe + "." + field + "=";
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

  private static void assertSuccess(Map<String, Object> result) {
    assertFalse(isError(result), "tool failed: " + text(result));
  }

  private static void awaitReady(Process app) throws Exception {
    BufferedReader out =
        new BufferedReader(new InputStreamReader(app.getInputStream(), StandardCharsets.UTF_8));
    String line = out.readLine();
    if (!SampleOrderApp.READY.equals(line)) {
      fail("sample app did not start: " + line);
    }
  }

  private static Map<String, Object> args(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  /**
   * JBang launch as in the plugin manifests, or, when the build supplies a BTrace JAR, the compiled
   * server on a plain classpath so the run does not depend on the published BTrace artifact.
   */
  private static List<String> serverCommand() {
    String btraceJar = System.getProperty("e2e.btraceJar");
    if (btraceJar == null || btraceJar.isEmpty()) {
      return List.of("jbang", requiredProperty("e2e.serverScript"));
    }
    if (!new File(btraceJar).isFile()) {
      throw new IllegalStateException("BTrace JAR not found: " + btraceJar);
    }
    return List.of(
        javaTool("java"),
        "--add-modules=jdk.attach",
        "-cp",
        requiredProperty("e2e.serverClasspath") + File.pathSeparator + btraceJar,
        BTraceMcpServer.class.getName());
  }

  private static String javaTool(String name) {
    return new File(System.getProperty("java.home"), "bin/" + name).getAbsolutePath();
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Run through `./gradlew e2eTest`; missing -D" + name);
    }
    return value;
  }
}
