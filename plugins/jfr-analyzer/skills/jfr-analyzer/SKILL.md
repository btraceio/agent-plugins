---
name: jfr-analyzer
description: Systematic performance investigation of JFR, pprof, OTLP, and HPROF profiling data using USE and TSA methodologies. Entry point for the jfr-analyzer plugin.
allowed-tools: Read mcp__jfr-mcp__jfr_help
---

## Argument parsing

$ARGUMENTS contains the raw arguments from the user invocation.

Parse the first token of $ARGUMENTS:

- If $ARGUMENTS is empty or equals "help" → print help text (see below) and stop.
- If first token is "triage" → SUBCOMMAND=triage; FILE is the second token (may be empty).
- If first token is "drilldown" → SUBCOMMAND=drilldown; FILE is empty (resumes session).
- If first token is "report" → SUBCOMMAND=report; FILE is empty (resumes session).
- If first token is "eval" → SUBCOMMAND=eval; pass $ARGUMENTS unchanged to the eval subskill.
- Otherwise → inspect the first token:
  - If it contains a path separator (/ or \) OR ends with a recognized extension (.jfr, .pb.gz, .pprof, .otlp, .hprof) → SUBCOMMAND=triage; FILE is the first token (auto-chain all phases).
  - If it does not look like a file path → output "Unknown subcommand — run /jfr-analyzer help for usage." and stop.

## Routing

Read the appropriate subskill:

- SUBCOMMAND=triage   → Read `subskills/triage/SKILL.md` relative to this skill directory
- SUBCOMMAND=drilldown → Read `subskills/drilldown/SKILL.md` relative to this skill directory
- SUBCOMMAND=report   → Read `subskills/report/SKILL.md` relative to this skill directory
- SUBCOMMAND=eval     → Read `subskills/eval/SKILL.md` relative to this skill directory

When the investigation involves a live JVM profile or a BTrace finding, also read
`async-profiler-interop/SKILL.md` relative to this skill directory. Use it to choose between
async-profiler, JFR, and BTrace and to preserve the shared evidence record.

The subskill receives $ARGUMENTS unchanged — it is responsible for extracting FILE
or session state from $ARGUMENTS and .jfr-analyzer/.

## Help text

If printing help, output exactly:

```
/jfr-analyzer <file>              Full analysis: triage → drilldown → report
/jfr-analyzer triage <file>       Run triage only (USE + TSA + stackgraph)
/jfr-analyzer drilldown           Resume drilldown on most recent session
/jfr-analyzer report              Generate report for most recent session
/jfr-analyzer eval regen          Regenerate JFR corpus (regen.sh + MCP event extraction)
/jfr-analyzer eval run            Run triage across corpus scenarios
/jfr-analyzer eval score          Score results and generate report
/jfr-analyzer help                Show this help

Supported formats:
  .jfr              Java Flight Recorder (JVM)
  .pb.gz / .pprof   pprof (Go, polyglot)
  .otlp             OpenTelemetry profiles
  .hprof            Java heap dump

Requires: Jafar MCP running — start with: jbang jafar-mcp@btraceio
```
