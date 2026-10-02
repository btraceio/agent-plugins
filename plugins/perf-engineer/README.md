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
python3 plugins/perf-engineer/scripts/perf-engineer-session.py start <pid> \
  --event cpu --duration 30 --output /tmp/profile.jfr
python3 plugins/perf-engineer/scripts/perf-engineer-session.py status <session-id>
python3 plugins/perf-engineer/scripts/perf-engineer-session.py stop <session-id>
python3 plugins/perf-engineer/scripts/perf-engineer-session.py cleanup <session-id>
```

Set `ASYNC_PROFILER_HOME` or pass `--profiler` when the async-profiler CLI is not on `PATH`. This
supervises one recording only; BTrace probes and later analysis remain separate workflow phases.

Run the supervisor regression tests with:

```sh
python3 -m unittest discover -s plugins/perf-engineer/scripts -p 'test_*.py' -v
```
