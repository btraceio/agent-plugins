# Perf Engineer

`perf-engineer` coordinates an evidence-driven optimization investigation for a running Java
application. Give it a PID and optionally a source directory. It combines async-profiler/JFR,
`jfr-analyzer`, BTrace workload fingerprints, representative JMH benchmarks, typed optimization
ideas, and explicitly approved candidate patches.

The workflow is deliberately bounded and may conclude that there is insufficient evidence, the
workload is non-replayable, or no meaningful improvement was found. It does not silently capture
arguments, modify the user's worktree, or create a PR.

Initial build-system scope: Gradle, Maven, and Bazel.

## Bounded recording supervisor

The first supervisor implementation manages one async-profiler recording. It validates the target,
enforces a maximum duration, persists state, and supports stop/status/cleanup:

```sh
plugins/perf-engineer/scripts/PerfEngineerSession.java start <pid> \
  --event cpu --duration 30 --output /tmp/profile.jfr
plugins/perf-engineer/scripts/PerfEngineerSession.java status <session-id>
plugins/perf-engineer/scripts/PerfEngineerSession.java stop <session-id>
plugins/perf-engineer/scripts/PerfEngineerSession.java cleanup <session-id>
```

The scripts are executable JBang programs; if the executable bit is lost (for example on a Windows
checkout), run them as `jbang plugins/perf-engineer/scripts/PerfEngineerSession.java <args>` instead.

Set `ASYNC_PROFILER_HOME` or pass `--profiler` when the async-profiler CLI is not on `PATH`. This
supervises one recording only; BTrace probes and later analysis remain separate workflow phases.

Run the supervisor regression tests with:

```sh
plugins/perf-engineer/scripts/PerfEngineerSessionTest.java
```
