# JFR Analyzer

`jfr-analyzer` turns JFR, pprof, OTLP, and HPROF recordings into a structured performance investigation. It uses USE (Utilization, Saturation, Errors) and TSA (Thread State Analysis) reasoning before moving to individual hotspots.

Its `async-profiler-interop` skill connects sampled live profiles with JFR history and exact BTrace
probes, using one target/window/evidence record across the three instruments.

## Host support

The shared skills work in Claude Code, Codex, and Pi. The plugin expects a Jafar MCP server at `http://localhost:3000/mcp/sse`:

```bash
jbang jafar-mcp@btraceio
```

Configure the same `jfr-mcp` server in the host when automatic plugin MCP loading is unavailable. The skills refer to the server by capability (`jfr_open`, `jfr_query`, `jfr_summary`, and related tools); host-specific MCP namespaces may differ.

## Install async-profiler

Async-profiler is optional for ordinary JFR-file analysis, but required for the bundled eval corpus
and for live sampled CPU, wall-clock, allocation, lock, or native profiles. Install a release from
the [async-profiler releases page](https://github.com/async-profiler/async-profiler/releases), then
point the plugin at the extracted directory:

```sh
export ASYNC_PROFILER_HOME="$HOME/.local/lib/async-profiler"
export PATH="$ASYNC_PROFILER_HOME/bin:$PATH"
test -f "$ASYNC_PROFILER_HOME/lib/libasyncProfiler.so" \
  || test -f "$ASYNC_PROFILER_HOME/lib/libasyncProfiler.dylib"
```

On macOS, use the release archive containing `macos`; on Linux, use `linux-x64` or `linux-arm64`
as appropriate. The directory must contain `lib/libasyncProfiler.dylib` or
`lib/libasyncProfiler.so`. The profiler attaches to the target JVM, so the profiler and target
must run on the same host and the user must have the required JVM attach permissions.

The eval regeneration script can perform the same installation interactively when async-profiler
is missing:

```sh
plugins/jfr-analyzer/eval/scripts/regen.sh
```

It prompts before downloading and stores the tool under `$HOME/.local/lib/`.

## Run the eval corpus

From the repository root:

```sh
plugins/jfr-analyzer/eval/scripts/regen.sh
```

The script prompts before installing JBang, async-profiler, or `jq`, then records the corpus and
updates its manifest. After the MCP server is available, run `/jfr-analyzer eval regen` to extract
event types and optionally validate oracle results. Run `/jfr-analyzer eval run` for triage passes,
then `/jfr-analyzer eval score` to generate the report. Scoring creates a local Python environment
under `plugins/jfr-analyzer/eval/scripts/.venv` when needed and may require the judge API key named
in `scripts/score.py`.

## Transport strategy

The analysis workflow is transport-neutral. Prefer a host-provided CLI or shell command for a
single, read-only operation when its output is trustworthy and capturable. Use the Jafar MCP
session for repeated queries, typed results, capability discovery, and multi-phase analysis. No
standalone JFR CLI adapter is bundled yet; future adapters should emit the same evidence fields
used by `jfr-btrace-interop`.

## Commands

```text
/jfr-analyzer recording.jfr
/jfr-analyzer triage recording.jfr
/jfr-analyzer drilldown
/jfr-analyzer report
```

## JFR and BTrace together

Use JFR for historical, aggregate evidence and BTrace for a narrow live confirmation or targeted observation:

1. Preserve the recording path, target identity, and time window.
2. Use JFR to identify the resource, time window, and candidate class/method.
3. Use `btrace-observability` to design the smallest safe live probe.
4. Correlate the probe output with the JFR window; do not treat either signal as proof in isolation.
5. Stop the probe and retain the correlation metadata with the report.

The `jfr-btrace-interop` skill contains the handoff protocol.
