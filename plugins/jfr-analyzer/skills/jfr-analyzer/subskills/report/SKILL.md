---
name: report
description: Phase 3 of jfr-analyzer. Reads all drilldown/<area>.json findings, deduplicates overlapping evidence, ranks by user-visible impact, produces plain-language finding cards, and writes report.json with a sphinx-optimize bridge stub.
allowed-tools: Read Write Bash(find *) Bash(ls *)
---

## Setup

Find session state using the same logic as drilldown/SKILL.md:
  If $ARGUMENTS contains a path to a focus.json, use that.
  Otherwise: Bash: find .jfr-analyzer -name "focus.json" -maxdepth 3
  Pick the most recent by directory timestamp.
  If none found: output "No active session. Run /jfr-analyzer <file> first." and stop.

Read SESSION_DIR/focus.json and SESSION_DIR/session.json.

Find drilldown files:
  Bash: find SESSION_DIR/drilldown -name "*.json" -maxdepth 1

Read all found drilldown JSON files.

If no drilldown files are found:
  Output: "No drilldown results found. Run /jfr-analyzer drilldown first."
  Stop.

Create a host-appropriate task checklist: aggregate, deduplicate, rank, executive-summary, cards,
report-json, final-output.

## Step 1 — Aggregate

Mark aggregate in_progress.

For each drilldown/<area>.json:
  1. Read the file and parse it as `finding`. Extract: finding.area (the id), finding.summary, finding.evidence[], finding.hints[].
     If the file does not contain an 'area' field, or if no focusArea in focus.json has an id matching finding.area, skip this file and output a warning: 'Warning: drilldown file [filename] has no matching focus area — skipping.'
  2. Look up the matching focusArea in focus.json where focusArea.id == finding.area.
     Use focusArea.title as the finding's display title.
     Use finding.impact if present in the drilldown JSON; otherwise fall back to focusArea.impact as the finding's impact level. (Drilldown agents may optionally emit a top-level "impact" field to upgrade or downgrade the triage severity assessment.)
     If focusArea has deferred=true, skip this finding (it was not analyzed).

Also collect any focusAreas with deferred=true that have no drilldown file — these will be
rendered as DEFERRED sections in the output.

Mark aggregate completed.

## Step 2 — Deduplicate

Mark deduplicate in_progress.

For each pair of findings:
  If their top evidence items point to the same class or method name (compare the "value"
  field of evidence[0] for common identifiers like class names or method signatures):
    Keep the finding with the higher impact level.
    Append the lower-impact finding's evidence items to the kept finding's evidence list.
    Add a note to the kept finding: "See also: [other finding title]"
    Remove the lower-impact duplicate from the findings list.

If findings is empty after deduplication:
  Output: "No analyzable findings (all areas were skipped or deferred)."
  Write SESSION_DIR/report.json using the Write tool:
  {
    "session": "<session.sessionId>",
    "format": "<session.format>",
    "file": "<session.file>",
    "source_root": "<session.sourceRoot or null>",
    "findings": []
  }
  Stop.

Mark deduplicate completed.

## Step 3 — Rank by impact

Mark rank in_progress.

Sort the remaining findings:
  1. Areas where focusArea.startHere=true first (these were marked by triage as highest potential gain).
  2. Within each startHere group: HIGH impact before MODERATE before LOW.
  3. Within the same impact level: sort by frequency × duration when those metrics are available in evidence.
     A 4ms stall at 500 times/second outranks a 200ms stall at 2 times/hour.

Assign sequential finding numbers starting from 1 (e.g. "FINDING 1 of 4").

Mark rank completed.

## Step 4 — Executive summary

Mark executive-summary in_progress.

Write 2-3 sentences in plain English:
  - What is the most significant bottleneck and what user-visible symptom does it cause?
  - What is the single most impactful action to take first?

Rules:
  - No jargon without inline definition.
  - No method names without explanation of what they do.
  - A user who has never read a flame graph should understand the summary.

Mark executive-summary completed.

## Step 5 — Finding cards

Mark cards in_progress.

For each finding (in ranked order), output:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FINDING [N] of [M] — [finding.title]      [[IMPACT] IMPACT]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

What's happening:
  [finding.summary — 2-3 sentences, plain language, no jargon without inline definition]

Root cause evidence:
  [For each item in finding.evidence (up to 4 items):]
  → [evidence.value]
     ([evidence.explanation])

Optimization hints:
  [For each hint in finding.hints (up to 3, highest impact first):]
  [N]. [hint.description]
       — estimated impact: [hint.impact]
  [If hint.code_after is not null:]
       Before:
         [hint.code_before]
       After:
         [hint.code_after]

Queries used (re-run or adapt in Jafar):
  [For each unique query string from finding.evidence[*].query (deduplicated):]
  [query string]

For each deferred area, output:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFERRED — [focusArea.title]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
This area was not analyzed. To investigate it, re-run:
  /jfr-analyzer drilldown
and respond "yes" when asked about [focusArea.title].

Mark cards completed.

## Step 6 — Write report.json

Mark report-json in_progress.

Determine suggested_targets for the sphinx-optimize bridge from the top finding's id:
  gc-pressure or allocation-pressure → ["cpu", "memory"]
  thread-contention or cpu-hotspots or safepoints or virtual-thread-pinning → ["cpu"]
  heap-memory → ["memory"]
  temporal-spikes → ["cpu", "memory"]

Use the suggested_targets value computed from the mapping table above — do not hardcode ["cpu","memory"].

If the top finding was merged from multiple source areas during deduplication, compute suggested_targets as the union of the mapped values for all contributing area ids (deduplicated). For example, if gc-pressure and cpu-hotspots were merged, the union of ["cpu","memory"] and ["cpu"] is ["cpu","memory"].

Write SESSION_DIR/report.json using the Write tool:

{
  "session": "<session.sessionId>",
  "format": "<session.format>",
  "file": "<session.file>",
  "source_root": "<session.sourceRoot or null>",
  "findings": [
    {
      "id": "<focusArea.id>",
      "title": "<focusArea.title>",
      "impact": "<high|moderate|low>",
      "summary": "<finding.summary>",
      "hints": [<hint objects from drilldown>],
      "evidence": {
        "queries": [<unique query strings from evidence[*].query>],
        "metrics": {<key metric values extracted from evidence[*].value>}
      }
    }
  ],
  "_sphinx_optimize_bridge": {
    "version": 1,
    "source": "<session.format>",
    "top_finding": "<id of the first (highest-impact) finding>",
    "suggested_targets": <use the suggested_targets value computed from the mapping table above>
  }
}

Mark report-json completed.

## Step 7 — Final output

Mark final-output in_progress.

Print:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ANALYSIS COMPLETE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

[executive summary text]

[all finding cards in ranked order]

[all deferred sections]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Full report saved to: [SESSION_DIR]/report.json

To continue investigating interactively:
  1. If the host supports specialist workers, dispatch the bundled perf-engineer role; otherwise
     perform the same review sequentially.
  2. Provide the recording file: [session.file]

Mark final-output completed.
