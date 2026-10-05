/*
 * Copyright (c) 2008, 2026, Jaroslav Bachorik <j.bachorik@btrace.io>.
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
 * Handles the stop_detached_probe MCP tool - reconnects to a probe that a client detached from and
 * stops it.
 */
public final class StopDetachedProbeHandler {
  private static final Logger log = LoggerFactory.getLogger(StopDetachedProbeHandler.class);
  private static final int DEFAULT_PORT = 2020;
  private static final int TIMEOUT_SECONDS = 30;

  private StopDetachedProbeHandler() {}

  /** Returns tool schema for MCP tools/list. */
  public static Map<String, Object> schema() {
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("name", "stop_detached_probe");
    tool.put("description", "Stop and remove a detached probe by its list_probes id.");

    Map<String, Object> properties = new LinkedHashMap<>();

    Map<String, Object> pidProp = new LinkedHashMap<>();
    pidProp.put("type", "string");
    pidProp.put("description", "Target JVM PID.");
    properties.put("pid", pidProp);

    Map<String, Object> probeIdProp = new LinkedHashMap<>();
    probeIdProp.put("type", "string");
    probeIdProp.put("description", "Probe id from list_probes.");
    properties.put("probe_id", probeIdProp);

    Map<String, Object> portProp = new LinkedHashMap<>();
    portProp.put("type", "integer");
    portProp.put("description", "Agent port (2020).");
    properties.put("port", portProp);

    List<String> required = new ArrayList<>();
    required.add("pid");
    required.add("probe_id");

    Map<String, Object> inputSchema = new LinkedHashMap<>();
    inputSchema.put("type", "object");
    inputSchema.put("properties", properties);
    inputSchema.put("required", required);
    tool.put("inputSchema", inputSchema);
    return tool;
  }

  /** Executes the stop_detached_probe tool. */
  public static Map<String, Object> execute(Map<String, Object> arguments) {
    return execute(arguments, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
  }

  static Map<String, Object> execute(Map<String, Object> arguments, long timeoutMillis) {
    String pid = getStringArg(arguments, "pid");
    String probeId = probeId(getStringArg(arguments, "probe_id"));
    int port = getIntArg(arguments, "port", DEFAULT_PORT);

    if (pid == null || pid.isEmpty()) {
      return toolResult("Error: 'pid' parameter is required", true);
    }
    if (probeId == null) {
      return toolResult("Error: 'probe_id' parameter is required", true);
    }

    try {
      BTraceClient client = ClientManager.getClient(port);
      client.attach(pid, null, ".");
      // The client waits for the agent to confirm the exit; never let that block the request.
      FutureTask<Void> stop =
          new FutureTask<>(
              () -> {
                client.exitProbe("localhost", probeId);
                return null;
              });
      Thread stopper = new Thread(stop, "btrace-stop-" + probeId);
      stopper.setDaemon(true);
      stopper.start();
      try {
        stop.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        try {
          client.close();
        } catch (Exception ignore) {
          // best effort; closing the socket ends the stopper's wait
        }
        return toolResult(
            "Timed out waiting for PID " + pid + " to confirm that probe " + probeId + " exited",
            true);
      } catch (ExecutionException e) {
        throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
      }
      return toolResult("Probe " + probeId + " stopped and removed from PID " + pid, false);
    } catch (Exception e) {
      log.error("Failed to stop detached probe", e);
      return toolResult(
          "Error stopping probe " + probeId + " on PID " + pid + ": " + rootMessage(e), true);
    }
  }

  /** Accepts a bare id or a whole list_probes line ({@code <n>: <id> [<class>]}). */
  private static String probeId(String value) {
    if (value == null) {
      return null;
    }
    String entry = value.trim().replaceFirst("^\\d+:\\s+", "");
    return entry.isEmpty() ? null : entry.split("\\s+", 2)[0];
  }

  /** Reflective calls wrap the client's exception; report the client's own message. */
  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getMessage() == null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
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
