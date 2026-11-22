---
name: latency
description: Investigate response-time problems that are not CPU-bound — lock contention, thread parking, executor queue saturation, blocking I/O, and per-endpoint latency attribution. Use when the user reports slow requests, p99 spikes, timeouts, deadlock suspicion, or when triage shows threads blocked rather than running.
allowed-tools: mcp__plugin_jafar-perf_jafar__jfr_tsa mcp__plugin_jafar-perf_jafar__jfr_use mcp__plugin_jafar-perf_jafar__jfr_query mcp__plugin_jafar-perf_jafar__jfr_list_types mcp__plugin_jafar-perf_jafar__jfr_stackprofile mcp__plugin_jafar-perf_jafar__pprof_tsa
---

# Latency analysis

Latency problems are usually *waiting*, and waiting is invisible to CPU profiling. A thread
blocked on a monitor produces no execution samples; the flamegraph looks healthy while the
p99 is ruined.

## 1. Where is the time spent not running?

```
jfr_tsa correlateBlocking=true
```

Thread State Analysis returns:

- `stateDistribution` — the share of thread time in each state. This is the headline number.
- `threadProfiles` and `topThreadsByState` — which threads, not just how many.
- `correlations` — monitor classes and executor queues implicated in blocking.
- `insights.problematicThreads[]` — each with its own `recommendation`.

Read `stateDistribution` first. If most time is `RUNNABLE`, this is a CPU problem — switch to
the `cpu` skill. If it is dominated by blocked, waiting or parked states, continue here.

Check the thread attribution before reading the per-thread fields. jfr-mcp 0.29.0, the
version this plugin pins, reads each sample's thread from `eventThread`, which
`jdk.ExecutionSample` and `jdk.NativeMethodSample` do not have. On a recording with those
samples it files every sample under one thread named `unknown`, so `threadProfiles`,
`topThreadsByState` and `totalThreads` describe that one pseudo-thread, and the active-thread
count of `jfr_use` is wrong for the same reason. `stateDistribution` is still usable, because
it does not depend on the thread. Recordings whose samples carry `eventThread`, such as
`datadog.ExecutionSample`, are not affected. From the first release after 0.29.0, samples are
attributed to their `sampledThread`; events without one, such as `jdk.JavaMonitorEnter` in
`correlations`, still use `eventThread`. A single `unknown` thread holding all the samples
therefore identifies a 0.29.0 result: do not report its per-thread findings.

## 2. Which resource is saturated?

```
jfr_use resources=all
```

The USE method (Utilization, Saturation, Errors) applied to CPU, memory, threads and I/O.
Each resource carries an `assessment`; `insights.bottlenecks[]` names the saturated ones as
`cpu_saturation`, `memory_pressure`, `thread_contention` or `queue_saturation`.

`queue_saturation` is the one most often missed: an executor whose queue depth grows means
requests wait before any code runs for them. No amount of method optimisation fixes it.

Narrow the window when the recording spans a mix of load levels. `jfr_use` and `jfr_tsa`
take the same two optional bounds:

```
jfr_use startTime=<epochNanos> endTime=<epochNanos> resources=threads
jfr_tsa startTime=<epochNanos> endTime=<epochNanos> correlateBlocking=true
```

Pass epoch-nanosecond bounds: the `startTime` of an event, copied from a query result, such
as the events that bracket a spike. The window selects events by their start time. How a
bound is read depends on the server version:

- **jfr-mcp 0.29.0, which this plugin pins,** compares the bounds directly with event start
  times, which are epoch nanoseconds. Epoch bounds therefore work. An offset from the
  recording's start does not: as `startTime` it excludes nothing, and as `endTime` it
  excludes every event.
- **From the first release after 0.29.0,** `startTime` and `endTime` are nanoseconds from the
  recording's start, and a value at or after the recording's start is read as an epoch
  timestamp. Epoch bounds therefore work on both versions.

Confirm the window before reading the metrics, and quote it in the report. From the first
release after 0.29.0, the result's `timeWindow` repeats `startTime` and `endTime` as given
and adds `recordingStartEpochNanos`, `startTimeEpochNanos` and `endTimeEpochNanos`, which
show how the bounds were read. Check that the last two cover the interval you meant.

- A `timeWindow` with only `startTime` and `endTime` comes from 0.29.0. The window was
  applied as given, so it is right only if both bounds were epoch nanoseconds from this
  recording. A result for offset bounds is not windowed: do not report it as such.
- From the first release after 0.29.0, if the recording's start cannot be read, the call fails
  rather than guessing. Report the failure; do not substitute the whole-recording result.
- `jfr_tsa` answering with only a `message` that no execution samples fell in the window
  means the window is empty, not that the threads were idle.

To run a `jfr_query` over the same window, put the two epoch values in the root brackets
(see the `jfrpath` skill for why the lower bound belongs there):

```
jfr_query query="events/jdk.JavaMonitorEnter[startTime>=<startTimeEpochNanos> and startTime<=<endTimeEpochNanos>] | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)"
```

## 3. Which lock?

Monitor contention shows up as `jdk.JavaMonitorEnter` (blocked acquiring) and
`jdk.JavaMonitorWait` (waiting on a condition). They mean different things — do not merge
them.

```
jfr_query query="events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)"
```

To find *what code* was contending, read the stack traces of the monitor events themselves.
Each event records the stack of the thread that blocked or waited:

```
jfr_flamegraph eventType=jdk.JavaMonitorEnter direction=bottom-up format=folded
```

Do not use `jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, …)` for this.
`decorateByTime` joins events that overlap in time **on the same thread**, and JFR takes no
execution sample of a thread while it waits, so almost every row comes back undecorated.
Those rows do not show an uncontended baseline. On jfr-mcp 0.29.0 the default thread paths
are `eventThread/javaThreadId` on both sides, and execution samples have no `eventThread`, so
that join decorates no sample at all.

## 4. Parking and sleeping

`jdk.ThreadPark` covers `LockSupport.park`, which is what every `java.util.concurrent` lock,
queue and future uses. Group by the parked class to tell a healthy idle pool from a stalled
one:

```
jfr_query query="events/jdk.ThreadPark | groupBy(parkedClass/name, agg=sum, value=duration) | top(10, by=value)"
```

A thread pool parked on its own work queue is idle and healthy. A request thread parked on a
`CompletableFuture` or a connection pool is a latency bug.

## 5. Per-endpoint attribution

When the recording carries request context (a Datadog profiler's `datadog.Endpoint`, or your
own event type), attribute waiting to the endpoint that suffered it:

```
jfr_query query="events/jdk.JavaMonitorEnter | decorateByKey(datadog.Endpoint, key=localRootSpanId, decoratorKey=localRootSpanId, fields=endpoint) | groupBy($decorator.endpoint, agg=sum, value=duration)"
```

`decorateByKey` is a correlation-key join, not a time join — use it whenever a shared id
exists, because it is both cheaper and exact.

## 6. Blocking I/O

```
jfr_query query="events/jdk.SocketRead[duration > 10ms] | groupBy(address, agg=sum, value=duration) | top(10, by=value)"
jfr_query query="events/jdk.FileRead[duration > 10ms] | groupBy(path, agg=count) | top(10, by=count)"
```

Filters accept duration literals (`10ms`, `1s`) and size units. Slow I/O to one address is a
dependency problem, not a JVM problem — say so plainly rather than proposing JVM tuning.

## What not to conclude

- A high *count* of monitor events is not contention; a high *summed duration* relative to
  the recording's wall clock is. Always divide by the recording duration.
- JFR's monitor events have a duration threshold (commonly 10 ms or 20 ms depending on
  settings). Contention below the threshold is invisible, so absence of events is not
  absence of contention. Check `jdk.ActiveSetting` if the threshold matters to the
  conclusion.
- `jfr_tsa` correlations are associations in time, not proof of causation. Report them as
  "concurrent with", and prove causation with a code path or a fix that measurably helps.
