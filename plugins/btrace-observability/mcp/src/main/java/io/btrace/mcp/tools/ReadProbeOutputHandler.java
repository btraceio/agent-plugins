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

import io.btrace.mcp.ClientManager;
import io.btrace.mcp.ProbeOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the read_probe_output MCP tool - returns what a probe deployed in this session printed
 * since the previous read, without stopping it.
 */
public final class ReadProbeOutputHandler {
  private static final int DEFAULT_PORT = 2020;
  /** Upper bound for wait_seconds, so one call cannot hold the conversation for long. */
  static final int MAX_WAIT_SECONDS = 30;

  private ReadProbeOutputHandler() {}

  /** Returns tool schema for MCP tools/list. */
  public static Map<String, Object> schema() {
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("name", "read_probe_output");
    tool.put("description", "Read what an active probe printed since the last read.");

    Map<String, Object> properties = new LinkedHashMap<>();

    Map<String, Object> pidProp = new LinkedHashMap<>();
    pidProp.put("type", "string");
    pidProp.put("description", "Target JVM PID.");
    properties.put("pid", pidProp);

    Map<String, Object> waitProp = new LinkedHashMap<>();
    waitProp.put("type", "integer");
    waitProp.put("description", "Seconds to wait for new output (0-30, default 0).");
    properties.put("wait_seconds", waitProp);

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

  /** Executes the read_probe_output tool. */
  public static Map<String, Object> execute(Map<String, Object> arguments) {
    String pid = getStringArg(arguments, "pid");
    int port = getIntArg(arguments, "port", DEFAULT_PORT);

    if (pid == null || pid.isEmpty()) {
      return toolResult("Error: 'pid' parameter is required", true);
    }

    ProbeOutput output = ClientManager.getOutput(pid, port);
    if (output == null) {
      return toolResult(
          "No active BTrace session for PID "
              + pid
              + ". Only probes deployed in this session and not yet stopped or detached can be read.",
          true);
    }
    int waitSeconds = Math.max(0, Math.min(MAX_WAIT_SECONDS, getIntArg(arguments, "wait_seconds", 0)));
    String text;
    try {
      text = output.drainAwaiting(waitSeconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      text = output.drain();
    }
    if (text.isEmpty()) {
      return toolResult(
          "No new output from the probe on PID "
              + pid
              + " since the last read"
              + (waitSeconds > 0 ? " (waited " + waitSeconds + "s)." : "."),
          false);
    }
    return toolResult("Output from the probe on PID " + pid + " since the last read:\n" + text, false);
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
