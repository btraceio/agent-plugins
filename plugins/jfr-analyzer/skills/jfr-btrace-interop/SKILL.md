---
name: jfr-btrace-interop
description: Use when a JFR/profile finding should be confirmed with a live BTrace probe, or when BTrace output needs historical JFR context.
---

# JFR ↔ BTrace correlation

Treat JFR and BTrace as complementary instruments:

- JFR provides historical, low-overhead aggregate evidence across a time window.
- BTrace provides a narrow, on-demand observation of a live JVM method, call, return, error, or event.

## Transport selection

Keep the investigation contract independent of the transport used to execute it:

- Use a local CLI or shell command for a one-shot, read-only action when the host provides one and
  its output can be captured reliably.
- Use MCP for stateful sessions, repeated typed queries, probe lifecycle operations, and workflows
  that need capability discovery or structured error handling.
- Do not make a finding depend on an MCP tool name or provider-specific namespace. Record the
  operation, target, time window, and returned evidence in the shared record below.

The current plugin integrations expose MCP for JFR and BTrace. This section is also the contract
for a future CLI adapter: it should produce the same evidence fields rather than a second,
transport-specific investigation format.
- A conclusion is stronger when the two observations share the same target identity and time window.

## JFR → BTrace handoff

When JFR identifies a candidate hotspot or contention path:

1. Record the JFR session path, target host/container, JVM identity, recording start/end, and timezone.
2. Carry forward the candidate class, method, thread group, event type, and hypothesis.
3. Load `btrace-observability` if available. Start with an exact method probe; use duration, count, error, or a bounded call probe rather than a JVM-wide match.
4. State the observation window, expected event rate, output destination, and cleanup command before deployment.
5. Compare BTrace output with the matching JFR interval. Account for sampling, clock differences, and probe overhead.

## BTrace → JFR handoff

When a live BTrace observation finds an unexpected path:

1. Preserve the PID/application identity, probe definition, attach time, detach time, and output path.
2. Use JFR to inspect the same interval for CPU, allocation, GC, locks, safepoints, and thread-state context.
3. Prefer JFR event correlation over adding more broad BTrace instrumentation.
4. If the installed BTrace build supports custom JFR events, use them only with explicit event fields and a bounded recording window.

## Shared evidence record

Keep this metadata with the report:

```json
{
  "target": "host-or-pod / pid / application",
  "jfr_session": "path-or-session-id",
  "jfr_window": "start/end with timezone",
  "btrace_window": "attach/detach with timezone",
  "candidate": "class.method or resource",
  "hypothesis": "plain-language explanation",
  "jfr_evidence": ["..."],
  "btrace_evidence": ["..."],
  "next_action": "..."
}
```

For command or tool output that is not already structured, retain the raw output only as a local
artifact and put a concise, redacted interpretation in `jfr_evidence` or `btrace_evidence`. Include
the command/tool identifier in the corresponding evidence item's `source` field when available.

Never print request bodies, credentials, tokens, or customer identifiers merely to make the correlation easier. Pair with BTrace’s data-safety and probe-lifecycle skills.
