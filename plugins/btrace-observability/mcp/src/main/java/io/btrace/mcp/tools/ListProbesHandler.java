/*
 * Copyright (c) 2008, 2024, Jaroslav Bachorik <j.bachorik@btrace.io>.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.btrace.mcp.tools;

import io.btrace.mcp.BTraceClient;
import io.btrace.mcp.ClientManager;
import io.btrace.mcp.ProbeOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the list_probes MCP tool. The agent lists only probes no client is connected to, so the
 * probe this server's own session holds is reported from {@link ClientManager}. A probe another
 * client (a btrace CLI, another MCP server) is connected to appears in neither list.
 */
public final class ListProbesHandler {
  private static final Logger log = LoggerFactory.getLogger(ListProbesHandler.class);
  private static final int DEFAULT_PORT = 2020;
  private static final int TIMEOUT_SECONDS = 10;

  private ListProbesHandler() {}

  /** Returns tool schema for MCP tools/list. */
  public static Map<String, Object> schema() {
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("name", "list_probes");
    tool.put(
        "description",
        "List the probe this server is connected to on a JVM and the detached probes still running"
            + " there. Probes connected through another client are not listed.");

    Map<String, Object> properties = new LinkedHashMap<>();

    Map<String, Object> pidProp = new LinkedHashMap<>();
    pidProp.put("type", "string");
    pidProp.put("description", "Target JVM PID.");
    properties.put("pid", pidProp);

    Map<String, Object> portProp = new LinkedHashMap<>();
    portProp.put("type", "integer");
    portProp.put("description", "Agent port (2020).");
    properties.put("port", portProp);

    List<String> required = new ArrayList<>();
    required.add("pid");

    Map<String, Object> inputSchema = new LinkedHashMap<>();
    inputSchema.put("type", "object");
    inputSchema.put("properties", properties);
    inputSchema.put("required", required);
    tool.put("inputSchema", inputSchema);
    return tool;
  }

  /** Executes the list_probes tool. */
  public static Map<String, Object> execute(Map<String, Object> arguments) {
    return execute(arguments, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
  }

  static Map<String, Object> execute(Map<String, Object> arguments, long timeoutMillis) {
    String pid = getStringArg(arguments, "pid");
    int port = getIntArg(arguments, "port", DEFAULT_PORT);

    if (pid == null || pid.isEmpty()) {
      return toolResult("Error: 'pid' parameter is required", true);
    }

    // Read before listing: a lost session is forgotten once list_probes has shown its probe.
    String connected = connectedSession(pid);
    BTraceClient client = null;
    try {
      client = ClientManager.getClient(port);
      client.attach(pid, null, ".");

      StringBuilder output = new StringBuilder();
      BTraceClient lister = client;
      // The client reads the reply with no timeout; never let an agent that does not answer block
      // the request.
      FutureTask<Void> list =
          new FutureTask<>(
              () -> {
                lister.listProbes(
                    "localhost", cmd -> output.append(lister.printableText(cmd)));
                return null;
              });
      Thread listerThread = new Thread(list, "btrace-list-probes-" + pid);
      listerThread.setDaemon(true);
      listerThread.start();
      try {
        list.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        return toolResult("Timed out waiting for probe list from PID " + pid, true);
      } catch (ExecutionException e) {
        throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
      }

      ClientManager.forgetLostSession(pid);
      String detached = output.toString().trim();
      String resultText =
          "Probes on PID "
              + pid
              + ":\n"
              + "Connected through this MCP server: "
              + connected
              + "\n"
              + "Detached (stop with stop_detached_probe): "
              + (detached.isEmpty() ? "none" : "\n" + detached)
              + "\n"
              + "Probes connected through another client (a btrace CLI or another MCP server) are"
              + " not listed.";
      return toolResult(resultText, false);
    } catch (Exception e) {
      log.error("Failed to list probes", e);
      return toolResult("Error listing probes on PID " + pid + ": " + e.getMessage(), true);
    } finally {
      closeQuietly(client);
    }
  }

  /** Describes this server's session on the PID, which the agent's list does not include. */
  private static String connectedSession(String pid) {
    int port = ClientManager.sessionPort(pid);
    if (port < 0) {
      return "none";
    }
    ProbeOutput output = ClientManager.getOutput(pid, port);
    if (output == null || !output.isFinished()) {
      return "a probe on port " + port + " (stop with exit_probe)";
    }
    return output.hasExited()
        ? "none (the probe on port " + port + " exited)"
        : "none (the connection to the probe on port "
            + port
            + " was lost; if it is still running, it is listed as detached)";
  }

  private static void closeQuietly(BTraceClient client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (Exception e) {
      log.debug("Failed to close the list_probes client", e);
    }
  }

  private static String getStringArg(Map<String, Object> args, String key) {
    Object val = args == null ? null : args.get(key);
    return val == null ? null : val.toString();
  }

  private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
    Object val = args == null ? null : args.get(key);
    if (val == null) {
      return defaultVal;
    }
    if (val instanceof Number) {
      return ((Number) val).intValue();
    }
    try {
      return Integer.parseInt(val.toString());
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }

  private static Map<String, Object> toolResult(String text, boolean isError) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("type", "text");
    content.put("text", text);
    List<Object> contentList = new ArrayList<>();
    contentList.add(content);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("content", contentList);
    result.put("isError", isError);
    return result;
  }
}
