package io.btrace.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal MCP client that drives a server process over JSON-RPC on stdin/stdout. */
final class McpStdioClient implements AutoCloseable {
  private static final String EOF = "\u0000EOF";

  private final Process process;
  private final OutputStream stdin;
  private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
  private int nextId = 1;

  McpStdioClient(List<String> command, File stderrLog) throws IOException {
    process =
        new ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.to(stderrLog))
            .start();
    stdin = process.getOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  lines.add(line);
                }
              } catch (IOException ignored) {
                // process went away; the EOF marker below reports it
              }
              lines.add(EOF);
            },
            "mcp-stdout");
    reader.setDaemon(true);
    reader.start();
  }

  Map<String, Object> initialize(Duration timeout) throws Exception {
    Map<String, Object> clientInfo = new LinkedHashMap<>();
    clientInfo.put("name", "btrace-mcp-e2e");
    clientInfo.put("version", "0");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("protocolVersion", "2024-11-05");
    params.put("capabilities", new LinkedHashMap<>());
    params.put("clientInfo", clientInfo);
    Map<String, Object> result = request("initialize", params, timeout);
    Map<String, Object> initialized = new LinkedHashMap<>();
    initialized.put("jsonrpc", "2.0");
    initialized.put("method", "notifications/initialized");
    write(initialized);
    return result;
  }

  /** Sends a request and returns its {@code result}; fails on a JSON-RPC error or bad stdout. */
  @SuppressWarnings("unchecked")
  Map<String, Object> request(String method, Map<String, Object> params, Duration timeout)
      throws Exception {
    int id = nextId++;
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("jsonrpc", "2.0");
    message.put("id", id);
    message.put("method", method);
    message.put("params", params);
    write(message);

    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long remaining = deadline - System.nanoTime();
      String line = remaining > 0 ? lines.poll(remaining, TimeUnit.NANOSECONDS) : null;
      if (line == null) {
        throw new AssertionError(method + " got no response within " + timeout);
      }
      if (line.equals(EOF)) {
        throw new AssertionError(
            method + ": server closed stdout (exit " + exitCodeIfDone() + ")");
      }
      Map<String, Object> response;
      try {
        response = McpProtocol.parseJson(line);
      } catch (RuntimeException e) {
        throw new AssertionError("Non-JSON line on the MCP stdout transport: " + line, e);
      }
      if (!Integer.valueOf(id).equals(asInt(response.get("id")))) {
        continue;
      }
      if (response.containsKey("error")) {
        throw new AssertionError(method + " failed: " + McpProtocol.toJson(response.get("error")));
      }
      return (Map<String, Object>) response.get("result");
    }
  }

  Map<String, Object> callTool(String name, Map<String, Object> arguments, Duration timeout)
      throws Exception {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("name", name);
    params.put("arguments", arguments);
    return request("tools/call", params, timeout);
  }

  @SuppressWarnings("unchecked")
  static String text(Map<String, Object> toolResult) {
    StringBuilder text = new StringBuilder();
    for (Object item : (List<Object>) toolResult.get("content")) {
      text.append(((Map<String, Object>) item).get("text"));
    }
    return text.toString();
  }

  static boolean isError(Map<String, Object> toolResult) {
    return Boolean.TRUE.equals(toolResult.get("isError"));
  }

  @SuppressWarnings("unchecked")
  static List<String> names(Object items) {
    List<String> names = new ArrayList<>();
    for (Object item : (List<Object>) items) {
      names.add((String) ((Map<String, Object>) item).get("name"));
    }
    return names;
  }

  boolean isAlive() {
    return process.isAlive();
  }

  private synchronized void write(Map<String, Object> message) throws IOException {
    stdin.write((McpProtocol.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
  }

  private String exitCodeIfDone() {
    try {
      return process.waitFor(2, TimeUnit.SECONDS) ? String.valueOf(process.exitValue()) : "n/a";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "n/a";
    }
  }

  private static Integer asInt(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : null;
  }

  @Override
  public void close() throws Exception {
    try {
      stdin.close();
    } catch (IOException ignored) {
      // already gone
    }
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }
  }
}
