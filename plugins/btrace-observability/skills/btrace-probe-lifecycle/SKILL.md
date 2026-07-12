---
name: btrace-probe-lifecycle
description: Use when deploying, verifying, reconnecting to, collecting output from, or removing a BTrace probe.
---

# Probe Lifecycle

Every probe plan needs an owner, target identity, output destination, observation window, and stop
condition.

1. Confirm the PID/application/pod before attaching.
2. Attach with an explicit output file when a durable incident record is required:
   `btrace -v -o trace.log <PID> Probe.java`.
3. Exercise the scenario or observe only for the agreed window.
4. Check active probes with `btrace -lp <PID>`; reconnect only when intentionally continuing an
   existing probe with `btrace -r <probe-id> <PID>`.
5. Stop/remove the probe at the end of the window, archive or delete the output according to policy,
   and remove temporary probe files from the target host/container/pod.

When multiple diagnostic probes are necessary, name outputs with the target and purpose so results
can be compared and cleanup is unambiguous.
