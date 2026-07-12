---
name: btrace-observability
description: Use when a user wants to understand live Java application behavior with BTrace. Coordinate the bundled endpoint, latency, failure, runtime-access, lifecycle, and data-safety skills to answer the incident question.
---

# BTrace Observability

This is the coordinator for the `btrace-observability` plugin. Help users answer a runtime
question without requiring BTrace expertise. Translate their wording into a probe plan, then load
and combine the specialist skills below as needed.

## Skill composition

| Need | Load |
| --- | --- |
| HTTP endpoint or request flow | `btrace-endpoint-diagnostics` |
| Latency, throughput, or a bottleneck | `btrace-latency-analysis` |
| Exceptions, failed requests, or error source | `btrace-failure-analysis` |
| Fast ad-hoc production triage | `btrace-oneliner-triage` |
| Kubernetes, Docker, SSH, bastions, or cloud runtime access | `btrace-runtime-access` |
| Deploying, verifying, reconnecting, or stopping a probe | `btrace-probe-lifecycle` |
| Sensitive data, production load, permissions, or risk | `btrace-data-safety` |
| Extensions, metrics exporters, or permission grants | `btrace-extensions-and-permissions` |
| AI/MCP-guided local diagnostics | `btrace-mcp-operations` |
| Historical profile context or JFR/async-profiler correlation | `jfr-analyzer`, `async-profiler-interop`, and `jfr-btrace-interop` when installed |
| Immutable images, no attach, or launch-time deployment | `btrace-startup-and-packaging` |

For a typical production incident, combine runtime access + the relevant diagnosis skill + lifecycle
+ data safety. Do not force every request through every skill.

When a JFR or other profile is available, use it to establish the historical resource, time window,
and candidate hotspot before widening a live probe. If `jfr-analyzer` is installed, pair it with
`jfr-btrace-interop`: JFR supplies aggregate context and BTrace confirms a narrow live behavior.
If async-profiler is available, use it for a bounded sampled CPU, wall-clock, allocation, lock, or
native profile when that is the unresolved question. Carry target identity, timestamps, hypothesis,
and evidence between all analyses.

The skill suite is grounded in the BTrace hands-on tutorials, Quick Reference, Oneliner Guide, and
provided extension examples. Prefer their verified syntax and deployment patterns over invented DSL
or platform assumptions.

## Start with the outcome

Classify the request into one of these goals:

1. **What happens for this endpoint?** Find the concrete Java handler/controller or service method,
   then trace entry, selected arguments, downstream calls, and return/error outcome.
2. **Why is it slow?** Measure a precise method's duration first; add downstream call probes only if
   the first measurement leaves the bottleneck unclear.
3. **Why did it fail?** Observe exceptions at the smallest relevant handler/service boundary.
4. **What data reaches this method?** Capture only the specific argument, return value, or field
   needed to answer the question. Treat values as potentially sensitive.
5. **Is this query or dependency call occurring?** Probe the exact JDBC/client call site, avoid
   broad JVM-wide matching, and redact secrets from output.

Ask only for missing facts that materially affect the probe: target PID or deployment mode, the
endpoint/class/method if known, expected symptom, and whether request data may be printed. If the
application source is available, identify the request handler before proposing a broad probe.

## Choosing probe breadth

- Match scope to the incident. Exact-method probes are the production default; a bounded package or
  controller-level probe can be appropriate during discovery; JVM-wide probes require a controlled
  environment, explicit duration, and output/overhead guardrails.
- Broadening is a deliberate escalation, not an error. State its match boundary, expected event
  rate, duration, and rollback plan before giving the command.
- Delegate access, lifecycle, and data-handling rules to their specialist skills instead of
  duplicating them here.

## Response format

For each request, deliver:

1. A one-sentence interpretation of the runtime question.
2. The selected skills and why they apply.
3. A probe plan and deploy command with placeholders filled from the user's context.
4. Scope, sensitivity, and rollback notes.
5. The next probe or skill to add only if the first result is inconclusive.
