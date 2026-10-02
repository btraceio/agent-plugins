---
name: btrace-mcp-operations
description: Use when an AI client should operate BTrace through the BTrace MCP server to list local JVMs, deploy probes, trigger probe events, or clean up diagnostic sessions.
---

# MCP Operations

The BTrace MCP server offers structured local-JVM operations such as `list_jvms`, `deploy_oneliner`,
`deploy_script`, `send_event`, `detach_probe`, and `exit_probe`. It is suitable when the AI client
and target JVM are on the same host and the operator wants an auditable conversational workflow.

Do not call `list_probes`. The current BTrace client exits the process after returning the probe
list, which terminates the MCP server and drops every open probe session, so `send_event` and
`exit_probe` can no longer reach probes deployed earlier. Track each deployment yourself instead:
target PID, agent port, probe class or oneliner, and observation window.

Deployment results include only the probe's initial output; no tool returns what the probe prints
afterwards. When the operator needs ongoing results, have the probe write them somewhere they can
read, such as exported counters (`@Export` fields, read with `jcmd <PID> PerfCounter.print`).

- The MCP server uses the local JVM Attach API; it cannot attach across SSH, Docker, or Kubernetes
  boundaries. For those cases, first use `btrace-runtime-access` to place the client/server in the
  target environment.
- Treat deployment and cleanup as one operation: record the target PID and call `exit_probe` after
  the observation window.
- Use the server's `diagnose_slow_endpoint`, `find_exception_source`, and `profile_method` prompts
  as starting workflows, then apply the other plugin skills for scope and data safety.

The server uses stdio rather than opening a network listener. Keep it local to the target host and
use SSH, `kubectl exec`, or an approved bastion workflow to run it beside a remote target.

## Choosing MCP versus a CLI

Use the ordinary BTrace CLI or a host-provided wrapper for a simple one-shot action when that is
available and the operator wants a command they can copy, review, and rerun. Use this MCP server
when the workflow needs JVM discovery, multiple related operations, typed tool results, or explicit
probe-session lifecycle in one conversation. Both paths must preserve the same target PID,
observation window, probe identity, output destination, and cleanup command.

When JFR is also involved, pass findings through the `jfr-btrace-interop` evidence record rather
than relying on provider-specific tool names or unstructured transcript text. JFR supplies the
historical interval; BTrace supplies the bounded live confirmation.

The plugin launches the bundled server with JBang. It loads the single masked BTrace distribution,
so do not configure legacy `btrace-client.jar`, `btrace-agent.jar`, or `btrace-boot.jar` paths.
