---
name: triage
description: Phase 1 of jfr-analyzer. Opens a profiling file, runs USE+TSA+stackgraph+JVM/GC lanes automatically, presents ranked plain-language problem areas at a breakpoint, and writes focus.json for drilldown.
allowed-tools: Read Write Bash(find *) Bash(mkdir *) Bash(date *) mcp__jfr-mcp__jfr_help mcp__jfr-mcp__jfr_open mcp__jfr-mcp__jfr_summary mcp__jfr-mcp__jfr_use mcp__jfr-mcp__jfr_tsa mcp__jfr-mcp__jfr_stackprofile mcp__jfr-mcp__jfr_query mcp__jfr-mcp__pprof_open mcp__jfr-mcp__pprof_summary mcp__jfr-mcp__pprof_use mcp__jfr-mcp__pprof_tsa mcp__jfr-mcp__pprof_stackprofile mcp__jfr-mcp__otlp_open mcp__jfr-mcp__otlp_summary mcp__jfr-mcp__otlp_use mcp__jfr-mcp__otlp_stackprofile mcp__jfr-mcp__hdump_open mcp__jfr-mcp__hdump_summary
---

## Setup

Parse FILE from $ARGUMENTS:
- If $ARGUMENTS starts with "triage ", FILE is everything after "triage ".
- Otherwise FILE is the first token of $ARGUMENTS.

If FILE is empty: output "Usage: /jfr-analyzer <file>" and stop.

Parse eval flags from $ARGUMENTS:
- EVAL_MODE = true if `--eval` is present anywhere in $ARGUMENTS token list
- OUTPUT_DIR = value after `--output-dir` token if present; null otherwise

If EVAL_MODE:
  FILE = the path token that immediately follows `--eval` (skip `--output-dir` and its value)
  If FILE is empty: output "Usage: /jfr-analyzer triage --eval <file> [--output-dir <path>]" and stop.

Create a host-appropriate task checklist with these tasks:
  - preflight
  - detect-format
  - open-session
  - summary
  - source-context
  - use-report
  - tsa-report
  - stackgraph
  - jvm-gc-lanes
  - synthesize
  - breakpoint

Mark each task in_progress before starting it and completed when done.

## Step 1 — Preflight

Mark preflight in_progress.

▶ Checking Jafar MCP...

Call mcp__jfr-mcp__jfr_help with no arguments.

If the call errors or times out, output:

  ❌ Jafar MCP is not running.

     Start it with:
       jbang jafar-mcp@btraceio

     Then re-run /jfr-analyzer.

Stop if Jafar is not running.

✓ Jafar MCP is running.

Mark preflight completed.

## Step 2 — Format detection

Mark detect-format in_progress.

Inspect the FILE extension and set these variables:

| Extension       | FORMAT | OPEN_TOOL                  | SUMMARY_TOOL                 | USE_TOOL              | TSA_TOOL              | STACK_TOOL                     |
|-----------------|--------|----------------------------|------------------------------|-----------------------|-----------------------|--------------------------------|
| .jfr            | jfr    | mcp__jfr-mcp__jfr_open       | mcp__jfr-mcp__jfr_summary      | mcp__jfr-mcp__jfr_use   | mcp__jfr-mcp__jfr_tsa   | mcp__jfr-mcp__jfr_stackprofile   |
| .pb.gz or .pprof| pprof  | mcp__jfr-mcp__pprof_open     | mcp__jfr-mcp__pprof_summary    | mcp__jfr-mcp__pprof_use | mcp__jfr-mcp__pprof_tsa | mcp__jfr-mcp__pprof_stackprofile |
| .otlp           | otlp   | mcp__jfr-mcp__otlp_open      | mcp__jfr-mcp__otlp_summary     | mcp__jfr-mcp__otlp_use  | (none)                | mcp__jfr-mcp__otlp_stackprofile  |
| .hprof          | hprof  | mcp__jfr-mcp__hdump_open     | mcp__jfr-mcp__hdump_summary    | (none)                | (none)                | (none)                         |

If extension is unrecognized:
  Output: "❌ Unrecognized file extension. Supported: .jfr .pb.gz .pprof .otlp .hprof"
  Stop.

▶ Detected format: [FORMAT]

Mark detect-format completed.

## Step 3 — Create session directory

Mark open-session in_progress.

BASENAME = filename without directory path (e.g. "myapp.jfr")
Run: Bash: date +%Y%m%d-%H%M%S  → store result as TIMESTAMP

Run: Bash: mkdir -p ".jfr-analyzer/${BASENAME}-${TIMESTAMP}/drilldown"

SESSION_DIR = ".jfr-analyzer/${BASENAME}-${TIMESTAMP}"

(The open-session task spans Steps 3 and 4 — do not mark it completed until Step 4 is done.)

## Step 4 — Open session

Call OPEN_TOOL with path=FILE.

Extract sessionId from the response.

Write SESSION_DIR/session.json with the Write tool:
{
  "sessionId": "<returned sessionId>",
  "format": "<FORMAT>",
  "file": "<FILE>",
  "sessionDir": "<SESSION_DIR>",
  "sourceRoot": null
}

▶ Session opened: [sessionId]

Mark open-session completed.

## Step 5 — Summary

Mark summary in_progress.

Call SUMMARY_TOOL with sessionId.

Display a brief overview to the user:
  "Recording: [BASENAME] ([duration], [thread count] threads, [sample count] samples)"

Write the raw summary response to SESSION_DIR/summary.json using the Write tool.

Mark summary completed.

## Step 6 — Source context detection

Mark source-context in_progress.

For JFR and pprof formats only, attempt to auto-detect the source project:

  Extract package/class names from the summary output (look for Java package prefixes like
  "com.example.myapp" in thread names or class names).

  Run: Bash: find . -maxdepth 2 -name "pom.xml" -o -name "build.gradle" -o -name "go.mod" -o -name "build.gradle.kts"

  If a dominant package prefix from the recording appears to match a project in the CWD
  (e.g. the package name matches a directory or build artifact name):
    SOURCE_ROOT="."
    Update session.json sourceRoot field to "."
    Output: "✓ Source: detected in current directory"
    Mark source-context completed and skip the prompt below.

If EVAL_MODE:
  SOURCE_ROOT=null
  Update session.json sourceRoot field to null using the Write tool.
  Mark source-context completed.
  Skip the rest of Step 6 — eval runs non-interactively without source.

If auto-detection is inconclusive (or format is OTLP/HPROF), ask the user once:

  "Source code lets me show you line-level fixes instead of just method names.
   Do you have the source for this application?

     [1] This directory (.)
     [2] Local path (you will be asked for the path)
     [3] GitHub repo — e.g. acme-corp/myapp  (will be cloned to /tmp)
     [4] Skip — proceed without source"

  Handle response:
  - "1" → SOURCE_ROOT="."
  - "2" → ask "Enter local path:" and use that path as SOURCE_ROOT
  - "3" → ask "Enter GitHub repo (org/repo):" then run:
            Bash: gh repo clone <org/repo> /tmp/jfr-src-<sessionId>
            SOURCE_ROOT="/tmp/jfr-src-<sessionId>"
  - "4" or anything else → SOURCE_ROOT=null

  Update session.json with "sourceRoot": SOURCE_ROOT using Write.

Mark source-context completed.

## Step 7 — USE report

Mark use-report in_progress.

For JFR, pprof, OTLP formats: call USE_TOOL with sessionId, includeInsights=true,
resources=["cpu","memory","threads","io"].

For HPROF: call mcp__jfr-mcp__hdump_summary with sessionId to extract available metrics
(total objects, total retained bytes). Store as a minimal USE-equivalent structure.

Write raw result to SESSION_DIR/use-report.json using the Write tool.

▶ USE analysis complete

Mark use-report completed.

## Step 8 — TSA report

Mark tsa-report in_progress.

For JFR and pprof formats: call TSA_TOOL with sessionId, correlateBlocking=true,
topThreads=15, includeInsights=true.

For OTLP and HPROF: skip (no TSA tool available). Write an empty object {} to
SESSION_DIR/tsa-report.json.

▶ TSA analysis complete (or: TSA not available for [FORMAT] format)

Mark tsa-report completed.

## Step 9 — Stackgraph (temporal axis)

Mark stackgraph in_progress.

For JFR, pprof, OTLP formats: call STACK_TOOL with sessionId.

From the stackprofile result, look for time windows where sample density is more than 2×
the median bucket density — these are spike windows. Record each as:
  { "startNs": <value>, "endNs": <value>, "relativeDensity": <multiplier vs median> }

Write result to SESSION_DIR/stackgraph.json using the Write tool. Include:
  { "raw": <full stackprofile result>, "spikeWindows": [<spike objects or empty array>] }

For HPROF: skip. Write { "raw": null, "spikeWindows": [] } to SESSION_DIR/stackgraph.json.

▶ Temporal analysis complete

Mark stackgraph completed.

## Step 10 — JVM/GC lanes (JFR only)

Mark jvm-gc-lanes in_progress.

Skip this step entirely for pprof, OTLP, and HPROF. For those formats, write
{ "skipped": true, "reason": "not JFR format" } to SESSION_DIR/jvm-gc-lanes.json and mark
jvm-gc-lanes completed.

For JFR only — run each query using mcp__jfr-mcp__jfr_query with sessionId:

**GC pauses:**
  Query 1: events/jdk.GCPhasePause | stats(duration)
  Query 2: events/jdk.GCPhasePause | quantiles(0.5, 0.95, 0.99, path=duration)
  Query 3: events/jdk.GCPhasePause | groupBy(name, agg=sum, value=duration) | top(5)

**Allocation (try each in order; use first that returns rows):**
  Option A: events/jdk.ObjectAllocationSample | groupBy(objectClass, agg=sum, value=weight) | top(10)
            (JDK 16+)
  Option B: events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass, agg=sum, value=bytes) | top(10)
            (older JDKs)
  Record which option was used as allocEventUsed.
  If Jafar returns a tool error (event type not found) or returns a result with zero rows, treat both as 'no rows' and try the next option.

**JIT stalls:**
  Query: events/jdk.Compilation[succeeded = false] | count()

**Safepoints:**
  Query 1: events/jdk.SafepointBegin | stats(duration)
  Query 2: events/jdk.SafepointBegin | quantiles(0.5, 0.95, 0.99, path=duration)

**Contention:**
  Query 1: events/jdk.JavaMonitorEnter | stats(duration)
  Query 2: events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=count) | top(10)

**Virtual thread pinning (JDK 21+ — ignore error if event is absent):**
  Query: events/jdk.VirtualThreadPinned | count()

**Exceptions (try each in order; use first that returns rows):**
  Option A: events/jdk.JavaExceptionThrow | count()
  Option B: events/jdk.ExceptionStatistics | stats(count)
  Record which option was used as exceptionEventUsed.

Store ALL results to SESSION_DIR/jvm-gc-lanes.json as a JSON object using Write:
{
  "allocEventUsed": "<option A/B>",
  "exceptionEventUsed": "<option A/B>",
  "gcPauseStats": <query 1 result>,
  "gcPauseQuantiles": <query 2 result>,
  "gcPhaseBreakdown": <query 3 result>,
  "allocation": <winning option result>,
  "jitFailures": <count>,
  "safepointStats": <query 1 result>,
  "safepointQuantiles": <query 2 result>,
  "contention": <query 1 result>,
  "contentionByMonitor": <query 2 result>,
  "virtualThreadPinned": <count or null if absent>,
  "exceptionCount": <winning option result>
}

▶ JVM/GC lane queries complete

Mark jvm-gc-lanes completed.

## Step 11 — Synthesize problem areas

Mark synthesize in_progress.

Combine USE report, TSA report, stackgraph spike windows, and JVM/GC lane data into a ranked
list of problem areas. For each area below, decide whether it is present and what its impact is:

| Area id             | Trigger condition                                                                        | Default impact |
|---------------------|------------------------------------------------------------------------------------------|----------------|
| gc-pressure         | (JFR only) USE cpu.assessment=HIGH AND GC-related OR gcPauseQuantiles.p95 > 50ms         | high           |
| allocation-pressure | (JFR only) Top allocating class bytes rate is high; HIGH if co-occurring with gc-pressure | moderate       |
| thread-contention   | USE threads.assessment=HIGH OR contention.totalEvents > 100 OR virtualThreadPinned count > 0 | high if p99 contention > 100ms, else moderate |
| cpu-hotspots        | USE cpu.utilization > 60% AND no dominant GC cause                                        | high           |
| safepoints          | safepoint p99 > 50ms OR safepoint p95 > 20ms                                              | moderate; low if p99 < 10ms |
| virtual-thread-pinning | virtualThreadPinned count > 0 (JFR only)                                               | high if count > 100, else moderate |
| heap-memory         | FORMAT=hprof (always present)                                                              | high           |
| temporal-spikes     | spikeWindows array is non-empty                                                            | moderate; high if relativeDensity > 5 |

When thread-contention is triggered by queue saturation (HIGH_QUEUE_SATURATION):
  - First check for GC-induced saturation (see cross-area rules below) — it takes precedence
    over the cpu-bound/io-bound classification.
  - If not GC-induced: check TSA stateDistribution. If RUNNABLE% > 80% across pool threads,
    the queued tasks are CPU-bound — increasing pool size will NOT help, it only adds more CPU
    contention. If WAITING/BLOCKED% is dominant instead, pool size may help (I/O-bound tasks).
  - Record which case applies as queueSaturationKind: "gc-induced", "cpu-bound", or "io-bound"
    and surface this in the breakpoint explanation.

Cross-area correlation checks (run after all individual areas are classified):

  A. GC-induced queue saturation
     Trigger: gc-pressure is present AND thread-contention is present (HIGH_QUEUE_SATURATION).
     Check: compare gcPauseP99 (from gcPauseQuantiles) against avgQueueTime (from TSA report).
     Rule: if gcPauseP99 > avgQueueTime × 0.20, then GC stop-the-world pauses are likely
     responsible for the queue buildup — all threads freeze during GC, requests queue up, and
     on resume the pool faces a backlog. Set queueSaturationKind = "gc-induced".
     In this case, the primary fix is reducing GC pressure (gc-pressure area), not tuning the
     thread pool.
     Record: { kind: "gc-queue-coupling", areas: ["gc-pressure","thread-contention"],
               finding: "GC pauses are inflating queue wait times",
               gcPauseP99Ms: <value>, avgQueueTimeMs: <value> }

  B. GC masking true CPU utilization
     Trigger: gc-pressure is present AND (cpu-hotspots is present OR cpu.utilization > 60%).
     Interpretation: The reported CPU utilization includes GC worker threads. If GC is
     frequent, a significant share of CPU is GC overhead, not application work. cpu-hotspots
     findings should be read in this context — hotmethods dominated by GC threads mean the
     application's actual CPU demand is lower than it appears.
     Record: { kind: "gc-cpu-inflation", areas: ["gc-pressure","cpu-hotspots"],
               finding: "GC threads are inflating reported CPU utilization" }

  C. Allocation-GC-latency cascade (compound root cause)
     Trigger: all three of allocation-pressure, gc-pressure, AND thread-contention are present.
     Interpretation: This is a compound feedback loop — high allocation rate triggers frequent
     GC, GC pauses freeze threads, frozen threads let the work queue back up. The root cause is
     allocation, not thread pool sizing or CPU. Fixing allocation rate resolves GC pressure and
     queue saturation together.
     Record: { kind: "alloc-gc-latency-cascade",
               areas: ["allocation-pressure","gc-pressure","thread-contention"],
               finding: "Allocation rate is driving GC frequency which is causing queue saturation" }

Store all detected correlations as crossAreaCorrelations (empty array if none match) and include
them in focus.json (see Step 13). Surface detected correlations in the breakpoint output as a
"CROSS-AREA NOTE" paragraph before the area list if any correlations exist.

Mark the top 1-2 areas as startHere=true (highest potential gain first). If two areas tie,
prefer: gc-pressure > thread-contention > cpu-hotspots > allocation-pressure > safepoints.
Exception: if correlation C (alloc-gc-latency-cascade) is detected, also mark
allocation-pressure as startHere=true alongside gc-pressure — fixing allocation is the
highest-leverage action even though gc-pressure ranks higher by default.

Mark synthesize completed.

## Step 12 — Present breakpoint

Mark breakpoint in_progress.

Output:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TRIAGE COMPLETE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Under "→ START HERE (highest potential gain)" list each area where startHere=true, numbered:
  N. [area title]                                [HIGH IMPACT]
     [2-3 sentences plain language — no jargon without definition — explain user-visible effect
      and what fixing it would improve]

Under "WORTH INVESTIGATING LATER" list each area where impact=moderate and startHere=false:
  N. [area title]                                [MODERATE]
     [1-2 sentence explanation and why it's lower priority]

Under "MINOR — UNLIKELY TO MOVE THE NEEDLE" list each area where impact=low:
  N. [area title]                                [LOW]
     [1-sentence explanation]

End with:
  Investigate [all / 1 / 1,2 / etc.] →

If EVAL_MODE:
  USER_RESPONSE = "all"
  Capture the breakpoint display text (the full ━━━ TRIAGE COMPLETE ━━━ block as a string).
  BREAKPOINT_SAVE_PATH = OUTPUT_DIR/breakpoint.txt if OUTPUT_DIR is set, else SESSION_DIR/breakpoint.txt
  Write the captured breakpoint text to BREAKPOINT_SAVE_PATH using the Write tool.
  Proceed directly to Step 13 — do not wait for user input.

If not EVAL_MODE:
  Wait for user input.

## Step 13 — Write focus.json and auto-chain

Parse user input:
- "all" → include all identified areas
- Comma-separated numbers (e.g. "1,3") → include those numbered areas
- Single number → include that area

Populate triageEvidence.metrics with the most relevant raw metric values for each area (e.g. gcPauseP99Ms, contentionTotalEvents, allocBytesPerSec) extracted from use-report.json, tsa-report.json, and jvm-gc-lanes.json.

For the temporal-spikes area, copy spikeWindows from stackgraph.json into triageEvidence.spikeWindows (the array of { startNs, endNs, relativeDensity } objects computed in Step 9). For all other areas, omit spikeWindows or set it to [].

FOCUS_PATH = OUTPUT_DIR/focus.json if OUTPUT_DIR is set, else SESSION_DIR/focus.json
Write FOCUS_PATH using the Write tool:
{
  "sessionId": "<sessionId>",
  "format": "<FORMAT>",
  "sessionDir": "<SESSION_DIR>",
  "sourceRoot": "<sourceRoot or null>",
  "crossAreaCorrelations": [
    {
      "kind": "<gc-queue-coupling | gc-cpu-inflation | alloc-gc-latency-cascade>",
      "areas": ["<area-id>", ...],
      "finding": "<one-sentence plain-language description>",
      "<optional metric keys>": "<metric values used to detect this correlation>"
    }
  ],
  "focusAreas": [
    {
      "id": "<area id from table above>",
      "title": "<human-readable title>",
      "impact": "<high|moderate|low>",
      "startHere": <true for areas marked startHere=true in Step 11, false otherwise>,
      "triageEvidence": {
        "metrics": {
          "note": "populate with relevant raw metric values from USE/TSA/JVM lane results for this area"
        },
        "spikeWindows": [<spike objects from stackgraph.json for temporal-spikes area; empty array for all other areas>]
      }
    }
  ]
}

Mark breakpoint completed.

Output:
  ▶ Starting drilldown on [N] selected area(s)...

Auto-chain: Read the sibling `../drilldown/SKILL.md` file relative to this skill directory.
