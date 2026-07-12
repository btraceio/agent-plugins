---
name: async-profiler-interop
description: Use when async-profiler sampling should narrow a JVM performance hypothesis or be correlated with JFR history and a BTrace live probe.
---

# Async-profiler interoperability

Treat async-profiler, JFR, and BTrace as complementary instruments:

- async-profiler provides low-overhead sampled evidence for CPU, wall-clock, allocation, lock, or
  native hotspots over a deliberately bounded interval;
- JFR provides broader historical context, event timelines, and JVM state across a recording;
- BTrace provides exact, on-demand observations at selected methods, calls, returns, exceptions, or
  custom events.

## Installation and availability

Before proposing a live async-profiler run, check `ASYNC_PROFILER_HOME` and verify that its
`lib/` directory contains `libasyncProfiler.so` (Linux) or `libasyncProfiler.dylib` (macOS). If it
is absent, give the operator the installation instructions in the `jfr-analyzer` README and stop;
do not silently download or execute native code. The bundled eval regeneration script may offer an
explicit, interactive download when the operator invokes it.

The profiler and target JVM must be on the same host. Check the target Java version, operating
system/architecture, attach permissions, container boundary, and whether the selected native
library matches the host before presenting a command.

Do not use one instrument as proof when the question needs another instrument's semantics. Preserve
the target identity, recording/profiling interval, clock and timezone, profiler mode, sampling
interval, output path, and cleanup action in every handoff.

## Choosing the next instrument

1. Start with JFR when a recording already covers the incident or when JVM-wide historical context
   is needed.
2. Start with async-profiler when the main uncertainty is sampled CPU, wall-clock, allocation,
   lock, or native-stack attribution and a short profile can safely be taken from the live JVM.
3. Use BTrace after a profile identifies a candidate method or path that needs exact confirmation.
   Keep the probe narrow, bounded, and reversible.
4. Use JFR after BTrace when the live observation raises a broader JVM or timeline question; prefer
   adding a bounded custom JFR event from BTrace only when the installed BTrace build supports it
   and the event schema is explicit.

## Async-profiler → JFR

When an async-profiler result is correlated with JFR:

- align the profiler interval with the JFR recording using absolute timestamps and timezone;
- record the profiler mode and interval because CPU, wall-clock, allocation, and lock profiles do
  not answer the same question;
- compare stacks and top methods with JFR execution, thread, allocation, or lock events without
  treating sampled counts as exact event counts;
- note sampling bias, safepoint effects, native frames, and missing symbols before drawing a causal
  conclusion.

## Async-profiler → BTrace

When a profile identifies a candidate:

1. Capture the candidate class, method, relevant stack context, profile mode, and time window.
2. Ask `btrace-observability` for the smallest probe that can distinguish the competing hypotheses.
3. State the expected event rate, maximum observation duration, output destination, and stop command
   before deployment.
4. Compare BTrace output with the matching profiler interval and account for probe overhead.

Do not turn a sampled stack frame into a broad package probe automatically. Prefer an exact method,
call boundary, exception type, or bounded argument-free event.

## BTrace → async-profiler/JFR

When BTrace finds an unexpected path:

- retain the probe identity, target PID, timestamps, and script/oneliner revision;
- use async-profiler for a short profile if the question is CPU, wall-clock, allocation, lock, or
  native-stack cost;
- use JFR for event history, thread state, GC, safepoints, allocations, or a wider interval;
- correlate all results against the same target and time window before recommending a change.

## Shared evidence record

Use this compact record when handing evidence between skills or providers:

```json
{
  "target": {"pid": "...", "host": "...", "service": "..."},
  "window": {"start": "...", "end": "...", "timezone": "..."},
  "async_profiler": {
    "mode": "cpu|wall|alloc|lock|native",
    "interval": "...",
    "artifact": "...",
    "evidence": ["..."]
  },
  "jfr": {"recording": "...", "evidence": ["..."]},
  "btrace": {"probe": "...", "evidence": ["..."]},
  "hypothesis": "...",
  "next_action": "..."
}
```

If output is unstructured, keep the raw artifact local and put a concise, redacted interpretation
in the relevant evidence list. Never include credentials, tokens, request bodies, or customer data
merely to improve correlation. Pair live instrumentation with the BTrace lifecycle and data-safety
skills, and stop every profiler/probe session at the declared end of its observation window.
