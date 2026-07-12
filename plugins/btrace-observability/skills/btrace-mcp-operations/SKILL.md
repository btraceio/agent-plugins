---
name: btrace-mcp-operations
description: Use when an AI client should operate BTrace through the BTrace MCP server to list local JVMs, deploy probes, inspect output, or clean up diagnostic sessions.
---

# MCP Operations

The BTrace MCP server offers structured local-JVM operations such as `list_jvms`, `deploy_oneliner`,
`deploy_script`, `list_probes`, and `exit_probe`. It is suitable when the AI client and target JVM
are on the same host and the operator wants an auditable conversational workflow.

- The MCP server uses the local JVM Attach API; it cannot attach across SSH, Docker, or Kubernetes
  boundaries. For those cases, first use `btrace-runtime-access` to place the client/server in the
  target environment.
- Treat deployment and cleanup as one operation: record the target PID and call `exit_probe` after
  the observation window.
- Use the server's `diagnose_slow_endpoint`, `find_exception_source`, and `profile_method` prompts
  as starting workflows, then apply the other plugin skills for scope and data safety.

The server uses stdio rather than opening a network listener. Keep it local to the target host and
use SSH, `kubectl exec`, or an approved bastion workflow to run it beside a remote target.
