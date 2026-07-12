---
name: btrace-extensions-and-permissions
description: Use when a BTrace investigation needs extensions, metrics exporters, custom services, network/filesystem permissions, or a decision between a built-in probe and a packaged extension.
---

# Extensions and Permissions

Use built-in BTrace functionality for a one-off observation. Use an extension when a team needs a
reusable integration—such as metrics export, a domain-specific helper, or a supported application
library bridge.

- Build/install extension artifacts under `$BTRACE_HOME/extensions/` before attaching.
- Grant only the permission actually required by the extension; do not default to `grantAll=true`.
- Inspect failed extensions with `btrace -le <PID>` when a probe attaches but an extension-backed
  feature is unavailable.
- For long-lived distributed deployments, consider a fat agent with the required extensions bundled
  at build time. Treat that as a reviewed deployment artifact, not an incident-time shortcut.
- Provided-style Spark/Hadoop examples demonstrate object hand-off and context-classloader patterns;
  avoid appending arbitrary libraries to the system classpath.

Relevant references:

- https://github.com/btraceio/btrace/blob/master/docs/tutorials/04-extensions-and-permissions.md
- https://github.com/btraceio/btrace/blob/master/docs/examples/README.md
- https://github.com/btraceio/btrace/blob/master/docs/BTraceExtensionDevelopmentGuide.md
