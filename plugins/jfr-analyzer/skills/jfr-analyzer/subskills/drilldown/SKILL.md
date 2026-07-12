---
name: drilldown
description: Phase 2 of jfr-analyzer. Reads focus.json, optionally gates HPROF analysis behind a cost warning, then dispatches parallel perf-engineer agents — one per selected problem area — each writing structured findings to drilldown/<area>.json.
allowed-tools: Read Write Bash(find *) Bash(ls *) mcp__jfr-mcp__jfr_query mcp__jfr-mcp__jfr_hotmethods mcp__jfr-mcp__jfr_stackprofile mcp__jfr-mcp__hdump_summary mcp__jfr-mcp__hdump_report mcp__jfr-mcp__hdump_query mcp__jfr-mcp__hdump_help
---

## Setup

Load session state by finding the most recent session:

If $ARGUMENTS contains a path to a focus.json, use that directly.

Otherwise run: Bash: find .jfr-analyzer -name "focus.json" -maxdepth 3

If multiple focus.json files are found, use the one whose parent directory has the most recent
timestamp in its name (directory names are <basename>-<YYYYMMDD-HHMMSS>).

If no focus.json is found:
  Output: "No active session. Run /jfr-analyzer <file> first."
  Stop.

Read the focus.json. Extract:
  - SESSION_DIR
  - sessionId
  - format
  - sourceRoot (may be null)
  - focusAreas[]

Also read SESSION_DIR/session.json to confirm the session record.

Create a host-appropriate task checklist with one task per focusArea id (e.g. "drill-gc-pressure",
"drill-cpu-hotspots") plus "hprof-gate" if format=hprof.

## HPROF cost gate

If format = "hprof" AND any focusArea has id = "heap-memory":

  Mark hprof-gate in_progress.

  Get the file path from session.json ("file" field).
  Run: Bash: ls -lh <file path>  to get the file size.

  Estimate:
    temp_low_gb  = file_size_gb × 0.10
    temp_high_gb = file_size_gb × 0.30
    time_low_min = 2 + (file_size_gb × 1.0)
    time_high_min = 2 + (file_size_gb × 2.0)

  Output:

  ┌────────────────────────────────────────────────────────────┐
  │  HPROF analysis warning                                    │
  │                                                            │
  │  Heap dump analysis can take several minutes and writes    │
  │  temporary index files to disk alongside the dump          │
  │  (typically 10-30% of the dump file size).                 │
  │                                                            │
  │  Your file is X.X GB — estimated Y-Z min runtime,         │
  │  ~A-B GB of temp files written to the dump directory.      │
  │                                                            │
  │  Proceed with heap analysis? [yes / skip]                  │
  └────────────────────────────────────────────────────────────┘

  (Substitute actual file size, time estimate, and temp estimate for X/Y/Z/A/B)

  If the user responds with anything other than "yes":
    Read focus.json. Find the focusArea with id="heap-memory". Add to it:
      "deferred": true,
      "deferredReason": "user skipped"
    Write the updated focus.json back using Write.
    Output: "Heap analysis deferred. Re-run /jfr-analyzer drilldown to include it later."
    Remove heap-memory from the active list for dispatch below.

  Mark hprof-gate completed.

## Parallel dispatch

Create the drilldown output directory if it doesn't already exist:
  Bash: find SESSION_DIR/drilldown -maxdepth 0 -type d  (check existence)
  If absent: the directory was already created by triage; if somehow missing, note it but proceed.

For each non-deferred area in focusAreas, dispatch a perf-engineer agent in parallel.

Each agent runs independently. Do not wait for one to complete before starting the next.

Construct the prompt for each agent by filling in this template:

---
You are a perf-engineer subagent. Your assignment is to investigate [area.title] in a
[format] profiling session and write your findings as structured JSON.

Session ID: [sessionId]
Session directory: [SESSION_DIR]
Source root: [sourceRoot — or "not available" if null]
Output file: [SESSION_DIR]/drilldown/[area.id].json

Triage evidence for this area:
[area.triageEvidence formatted as readable key: value lines]

Cross-area correlations detected during triage:
[If focus.json contains crossAreaCorrelations entries that include this area's id, list them here
as readable lines. If none involve this area, write "none". These represent interactions between
problem areas that affect how findings in this area should be interpreted:]

  gc-queue-coupling: GC stop-the-world pauses are inflating queue wait times. When investigating
    thread-contention, check whether queue wait spikes align with GC pause windows rather than
    assuming CPU-bound work. When investigating gc-pressure, note that fixing GC will also
    reduce apparent thread queue saturation.

  gc-cpu-inflation: GC threads are consuming CPU alongside application threads. When
    investigating cpu-hotspots, check whether GC-related methods (G1/ZGC/Shenandoah worker
    threads) appear in hotmethods. If they do, the application's true CPU demand is lower than
    the total reported utilization suggests.

  alloc-gc-latency-cascade: High allocation rate is driving GC frequency, which causes pauses,
    which cause queue backlog. When investigating any of the three involved areas, note that
    reducing the allocation rate is the highest-leverage fix — it resolves GC pressure and
    queue saturation as side effects.

## Drill strategy for [area.id]

[Copy the FULL drill instructions block for area.id from the "Per-area drill instructions"
section below — do not summarize or abbreviate]

## Output format

Use the Write tool to write [SESSION_DIR]/drilldown/[area.id].json with this schema:

{
  "area": "[area.id]",
  "summary": "<2-3 sentence plain-language summary, no jargon>",
  "evidence": [
    {
      "label": "<short label>",
      "value": "<what you found>",
      "explanation": "<plain-language explanation>",
      "query": "<the query you ran>"
    }
  ],
  "hints": [
    {
      "description": "<concrete action>",
      "impact": "high|moderate|low",
      "code_before": "<code snippet or null>",
      "code_after": "<improved code or null>"
    }
  ]
}

If a Jafar MCP call fails, still write the JSON with whatever evidence was collected and
add "error": "<brief description>" at the top level.

If source root is available, for each hot method identified:
  1. Run: find [sourceRoot] -name "[ClassName].java" (JFR) or "[filename].go" (pprof)
  2. Read the source file
  3. Reason about why the method is hot before writing hints
  4. Include before/after code snippets in hints where possible
---

## Per-area drill instructions

### gc-pressure (JFR only)

Run these queries using mcp__jfr-mcp__jfr_query:
  events/jdk.GCPhasePause | groupBy(name, agg=sum, value=duration) | top(5)
  events/jdk.GCPhasePause | quantiles(0.5, 0.95, 0.99, path=duration)
  events/jdk.GarbageCollection | stats(duration)

Also run mcp__jfr-mcp__jfr_hotmethods (to see if GC threads appear in top methods).

Identify: dominant GC phase (e.g. G1 Major, G1 Young, ZGC Concurrent), pause p50/p95/p99 in
milliseconds, whether GC threads appear in hotmethods, and which GC algorithm is in use.

If Jafar returns a tool error or zero rows for any query, note "event not present" in evidence.

### allocation-pressure (JFR only)

Try allocation events in this order. Use the first that returns rows (a tool error or zero rows
means try the next option):
  Option A: events/jdk.ObjectAllocationSample | groupBy(objectClass, agg=sum, value=weight) | top(10)
            (JDK 16+)
  Option B: events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass, agg=sum, value=bytes) | top(10)
            (older JDKs)

Then run:
  mcp__jfr-mcp__jfr_stackprofile (to find allocation call stacks)

Note which event was used in evidence[0].label. For sampled allocation events, state whether the
weight represents estimated bytes or sample count. Identify: top allocating class+method,
allocation rate, and whether allocations occur inside a tight loop.

### thread-contention (all formats)

Run: {fmt}_tsa with correlateBlocking=true, topThreads=20
  (replace {fmt} with jfr, pprof, otlp, or hdump based on format)

Note: HPROF format has no TSA tool — skip the {fmt}_tsa call for hdump format. Run only the hdump-specific contention analysis if available, or report that thread state analysis is not supported for heap dumps.

Note: OTLP format has no TSA tool — skip the {fmt}_tsa call for otlp format. Rely on stackprofile-based thread analysis: run otlp_stackprofile and identify methods that dominate samples during contention windows.

For JFR additionally run these mcp__jfr-mcp__jfr_query calls:
  events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=count) | top(10)
  events/jdk.JavaMonitorEnter | stats(duration)
  events/jdk.JavaMonitorWait | groupBy(monitorClass, agg=count) | top(10)
  events/jdk.VirtualThreadPinned | count()   (ignore error if event is absent)

If VirtualThreadPinned count > 0, add a dedicated evidence item:
  label: "Virtual thread pinning"
  explanation: "Virtual threads (JDK 21+) are being pinned to their carrier threads.
    Pinning happens inside synchronized blocks or native method calls and prevents
    the JVM from reusing the underlying OS thread for other virtual threads, reducing
    the scalability benefit of virtual threads."

### cpu-hotspots (JFR, pprof, OTLP only — not applicable to HPROF)
(replace {fmt} with jfr, pprof, otlp, or hdump based on the format field in your prompt)

Note: HPROF (heap dump) format has no CPU sample data — this area should not be selected for HPROF sessions.

Note: OTLP format does not have otlp_hotmethods or otlp_callgraph. For OTLP, skip {fmt}_hotmethods and {fmt}_callgraph. Instead, derive hot methods using: otlp_query groupBy(stackTrace/0/name, sum(cpu)) | top(20). Then run otlp_flamegraph direction=top-down as the only flamegraph step.

Note: pprof format does not have pprof_callgraph — skip the {fmt}_callgraph step for pprof format.

Run: {fmt}_hotmethods   (top 20 by default)  [skip for OTLP — use otlp_query instead, see Note above]
Run: {fmt}_flamegraph   direction=top-down
Run: {fmt}_callgraph    [skip for OTLP and pprof — see Notes above]

Identify: top-3 methods by sample percentage, their caller chains, and self-vs-total time
split. For the #1 hotmethod, estimate what percentage of total CPU it represents.

### safepoints (JFR only)

Run these mcp__jfr-mcp__jfr_query calls:
  events/jdk.SafepointBegin | stats(duration)
  events/jdk.SafepointBegin | quantiles(0.5, 0.95, 0.99, path=duration)
  events/jdk.SafepointBegin | groupBy(cause, agg=sum, value=duration) | top(5)

Identify: dominant safepoint cause (e.g. "RevokeBias", "Deoptimize", "G1IncCollectionPause"),
p95/p99 duration in milliseconds, and total safepoint time as a percentage of recording duration.

### virtual-thread-pinning (JFR only)

Run these mcp__jfr-mcp__jfr_query calls:
  events/jdk.VirtualThreadPinned | count()
  events/jdk.VirtualThreadPinned | stats(duration)

If the above return results, also run:
  events/jdk.VirtualThreadPinned | groupBy(stackTrace/frames/0/method/name, agg=count) | top(10)

Identify: total pinning events, duration distribution, and top pinning locations in the call
stack (the synchronized block or native method causing pinning).

### heap-memory (HPROF only)

Run: mcp__jfr-mcp__hdump_report   (full heap analysis — this is the primary analysis call)

Then run targeted hdump_query calls to identify top retained objects. Refer to
mcp__jfr-mcp__hdump_help for available HdumpPath query syntax.

Identify: largest retained object graphs by size, potential memory leaks (many instances with
large retained size), and GC roots holding large graphs.

### temporal-spikes (all formats with spike windows)
(replace {fmt} with jfr, pprof, otlp, or hdump based on the format field in your prompt)

Note: HPROF format has no stackprofile tool — skip this area for hdump format. Triage should not create a temporal-spikes focus area for HPROF since spikeWindows will be empty, but if it is received, output: 'Temporal spike analysis not supported for HPROF format.'

For each spike window in triageEvidence.spikeWindows:
  Run: {fmt}_stackprofile with startTime=<window.startNs>, endTime=<window.endNs>

Also run a baseline comparison:
  1. Re-run {fmt}_stackprofile across the full recording window (no startTime/endTime) to get the
     full density distribution.
  2. From that distribution, identify a window of the same duration as the spike window that falls
     outside all spikeWindows and whose sample density is nearest to the median density.
  3. Run {fmt}_stackprofile with startTime=<baseline.startNs> and endTime=<baseline.endNs> to get
     the baseline profile.

Compare: what methods appear in the spike window but not the baseline, or have significantly
higher sample percentage (>2× baseline) during the spike. These are the likely causes.

## After all agents complete

Once all dispatched agents have written their output files:

Output: "▶ Drilldown complete. Generating report..."

Auto-chain: Read the sibling `../report/SKILL.md` file relative to this skill directory.
