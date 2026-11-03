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
package io.btrace.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages BTrace client instances per JVM. Tracks active sessions so that tools like send_event,
 * detach_probe, and exit_probe can interact with already-deployed probes.
 */
public final class ClientManager {
  private static final Logger log = LoggerFactory.getLogger(ClientManager.class);

  /** Key is "pid:port", value is the active Client instance. */
  private static final Map<String, BTraceClient> activeClients = new ConcurrentHashMap<>();

  /** Key is "pid:port", value is the output the active probe printed and no tool has read yet. */
  private static final Map<String, ProbeOutput> activeOutputs = new ConcurrentHashMap<>();

  private ClientManager() {}

  /** Creates a new BTrace Client for the given port. Does not reuse existing sessions. */
  public static BTraceClient getClient(int port) throws Exception {
    return BTraceClient.create(port);
  }

  /** Registers a client as active for a given PID and port. */
  public static void registerClient(String pid, int port, BTraceClient client) {
    String key = pid + ":" + port;
    activeClients.put(key, client);
    log.debug("Registered client for {}", key);
  }

  /** Registers a client together with the buffer that collects its probe's output. */
  public static void registerClient(String pid, int port, BTraceClient client, ProbeOutput output) {
    activeOutputs.put(pid + ":" + port, output);
    registerClient(pid, port, client);
  }

  /** Returns the output buffer of the active probe for the given PID and port, or null if none. */
  public static ProbeOutput getOutput(String pid, int port) {
    return activeOutputs.get(pid + ":" + port);
  }

  /** Returns an existing active client for the given PID and port, or null if none. */
  public static BTraceClient getExistingClient(String pid, int port) {
    String key = pid + ":" + port;
    return activeClients.get(key);
  }

  /** Removes and returns an active client for the given PID and port. */
  public static BTraceClient removeClient(String pid, int port) {
    String key = pid + ":" + port;
    BTraceClient removed = activeClients.remove(key);
    activeOutputs.remove(key);
    if (removed != null) {
      log.debug("Removed client for {}", key);
    }
    return removed;
  }

  /**
   * Returns the port of this server's session on the given PID, or -1 if there is none. A JVM has a
   * single agent, and the client silently uses its port whatever port the tool was given, so one
   * JVM has one session however it was requested.
   */
  public static int sessionPort(String pid) {
    String prefix = pid + ":";
    for (String key : activeClients.keySet()) {
      if (key.startsWith(prefix)) {
        return Integer.parseInt(key.substring(prefix.length()));
      }
    }
    return -1;
  }

  /**
   * Returns why a probe cannot be deployed to the given PID, or null if it can. The registry holds
   * one probe per JVM, so deploying over a running one would leave it running where no tool can
   * reach it.
   */
  public static String deployRefusal(String pid) {
    int port = sessionPort(pid);
    if (port < 0) {
      return null;
    }
    ProbeOutput output = getOutput(pid, port);
    if (output == null || !output.isFinished()) {
      return "A probe is already running on PID "
          + pid
          + " (port "
          + port
          + "). Stop it with exit_probe, or leave it running with detach_probe, before deploying"
          + " another.";
    }
    return output.hasExited() ? null : lostConnectionMessage(pid, output);
  }

  /**
   * Returns the tool error for a session whose probe is no longer connected, or null if the session
   * is live or does not exist. Tools that talk to a running probe call this first.
   */
  public static String endedSessionError(String pid, int port) {
    ProbeOutput output = getOutput(pid, port);
    if (output == null || !output.isFinished()) {
      return null;
    }
    return output.hasExited()
        ? "The probe on PID "
            + pid
            + " already exited."
            + ProbeOutput.section("Unread output", output.drain())
        : lostConnectionMessage(pid, output);
  }

  private static String lostConnectionMessage(String pid, ProbeOutput output) {
    return "The connection to the probe on PID "
        + pid
        + " was lost, and the probe may still be running. Find it with list_probes and stop it with"
        + " stop_detached_probe."
        + ProbeOutput.section("Unread output", output.drain());
  }

  /**
   * Forgets a session that lost its connection, once the caller has used a tool that reaches such a
   * probe. Until then deploys stay refused, so that retrying cannot stack a second probe on it.
   */
  public static void forgetLostSession(String pid) {
    int port = sessionPort(pid);
    ProbeOutput output = port < 0 ? null : getOutput(pid, port);
    if (output != null && output.isFinished() && !output.hasExited()) {
      releaseFinished(pid);
    }
  }

  /**
   * Forgets the session on the given PID, if any, and returns what its probe printed that no tool
   * has read yet.
   */
  public static String releaseFinished(String pid) {
    int port = sessionPort(pid);
    if (port < 0) {
      return "";
    }
    ProbeOutput output = getOutput(pid, port);
    BTraceClient client = removeClient(pid, port);
    if (client != null) {
      try {
        client.close();
      } catch (Exception e) {
        log.debug("Failed to close the finished client for {}:{}", pid, port, e);
      }
    }
    return output == null ? "" : output.drain();
  }

  /** Closes all active client sessions. Called on shutdown. */
  public static void closeAll() {
    for (Map.Entry<String, BTraceClient> entry : activeClients.entrySet()) {
      try {
        entry.getValue().close();
        log.debug("Closed client for {}", entry.getKey());
      } catch (Exception e) {
        log.warn("Error closing client for {}", entry.getKey(), e);
      }
    }
    activeClients.clear();
    activeOutputs.clear();
  }
}
