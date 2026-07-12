# Perf Engineer Skill

Status: Draft design

## Summary

The `perf-engineer` skill guides an evidence-driven optimization loop for a running Java
application. The user supplies a target PID and, optionally, a source directory. The skill records
the application, sends the recording to `jfr-analyzer`, uses BTrace to characterize selected live
entry points, constructs representative JMH benchmarks, evolves optimization ideas, validates
generated patches, and offers the best candidate as a pull request.

The skill must keep three concerns separate:

1. **Observation** finds and characterizes likely bottlenecks.
2. **Optimization search** evolves constrained optimization ideas, not arbitrary source edits.
3. **Implementation validation** turns selected ideas into ordinary code changes and benchmarks.

This separation makes the process auditable and prevents benchmark results from being mistaken for
proof that a production change is safe.

## Goals

- Turn a PID plus an optional source directory into a repeatable optimization investigation.
- Combine async-profiler-backed JFR, `jfr-analyzer`, and BTrace using shared target and time-window
  metadata.
- Identify optimization seams and entry points from profile evidence and source inspection.
- Capture useful workload shape without copying arbitrary production object graphs.
- Generate representative, reviewable JMH benchmarks.
- Explore optimization ideas systematically and measure concrete implementations.
- Produce a PR candidate with evidence, benchmark results, limits, and reproduction instructions.
- Keep all profiling, probing, source edits, and PR creation bounded and user-visible.

## Non-goals

- Replaying production requests exactly by default.
- Capturing arbitrary arguments, credentials, request bodies, or object graphs.
- Claiming that a sampled profile proves causality.
- Optimizing external systems, deployment configuration, or infrastructure without a separate scope.
- Automatically merging or publishing a code change.
- Running an unbounded autonomous optimization loop.

## User contract

The entry point accepts:

```text
/perf-engineer <pid> [source-directory]
```

Before recording, the skill confirms:

- target PID, command line, host, Java version, and process identity;
- source directory, repository, branch, and clean/dirty working-tree state when supplied;
- available async-profiler/JFR and BTrace capabilities;
- recording duration, maximum duration, output location, and stop command;
- whether production-sensitive values may be observed;
- whether the user authorizes profiling and live instrumentation.

These are separate authorization boundaries and are requested only when needed:

- attach and record the target;
- deploy and stop BTrace probes;
- create benchmark/session files;
- synthesize or apply source patches;
- create a branch;
- open a draft PR.

The default recording is bounded. “Until I say stop” means the skill waits for an explicit stop
request but still enforces a hard maximum duration. The recorder owns that timeout, rather than the
chat client. A session has an owner and lease, a durable stop operation, and a recovery path for a
disconnected client. On restart, the skill lists active sessions and offers to stop orphaned ones;
it never silently resumes them.

The initial implementation uses a local session supervisor process. It owns recorder/probe child
processes, writes lease and state transitions to `manifest.json`, accepts an authenticated local
stop request, enforces timeouts independently of the chat client, and marks child processes as
`stopped`, `expired`, `target-exited`, or `failed`. Cleanup is idempotent and retryable after a
crash. A disconnected client does not extend the lease or hard timeout.

## Instrument roles

| Instrument | Primary question | Typical output |
| --- | --- | --- |
| async-profiler | Where is sampled CPU, wall-clock, allocation, lock, or native time going? | Profile artifact and stack distribution |
| JFR | What JVM events and state surround the suspected interval? | Historical event and timeline evidence |
| BTrace | What exact method/path behavior occurs on the live target? | Bounded invocation, latency, and shape summaries |
| JMH | Does a proposed implementation improve the isolated operation? | Reproducible benchmark comparison |

No instrument is treated as authoritative for a question outside its semantics. For example,
sampled stack frequency is not treated as an exact request count, and a microbenchmark result is
not treated as proof of end-to-end improvement.

## End-to-end workflow

### 1. Establish the session

Create a session directory containing:

```text
.perf-engineer/<session-id>/
  manifest.json
  recording/
  hypotheses/
  workload/
  benchmark/
  candidates/
  report/
```

The manifest records target identity, repository state, timestamps, tool versions, permissions,
and user-approved limits. Every downstream artifact references the session ID. The manifest also
stores a target fingerprint:

```json
{
  "pid": "...",
  "host": "...",
  "jvm_start_time": "...",
  "command_line_digest": "...",
  "executable_or_container_identity": "...",
  "clock": {
    "wall_start": "...",
    "monotonic_start_ns": "...",
    "clock_source": "..."
  }
}
```

Before every attach, record, and probe operation, revalidate the fingerprint. A reused PID, changed
command line, changed JVM start time, or changed host/container identity is a hard stop. Cleanup of
the supervisor's own child processes remains allowed after target loss; it uses recorded child
process IDs and session identity rather than attempting to attach to the target again.

When a source directory is supplied, exploration uses a separate worktree or temporary clone. The
user’s original worktree is never modified during profiling, benchmark generation, or candidate
search. The manifest records the exact base commit and excludes unrelated dirty changes from every
candidate.

The session workspace is outside the source worktree by default. Only an explicitly approved
benchmark or patch worktree may contain generated files. Candidate patches are computed against the
recorded base commit and are never applied to the user's original dirty worktree automatically.

### 2. Record the target

Use async-profiler in a JFR-compatible bounded recording mode when available. Select the event mode
from the user’s symptom or from a short diagnostic pass:

- CPU or wall-clock for latency and hot-code questions;
- allocation for allocation pressure and GC-related hypotheses;
- lock for contention;
- native when the evidence points below Java frames.

Record the mode, interval, stack mode, duration, output path, and exact start/end timestamps. If the
target is in a container or remote host, resolve that boundary before attaching; do not imply that a
local PID is reachable from another machine.

If the target exits, becomes unreachable, or fails fingerprint validation, stop dependent probes and
recordings, mark the session `target-exited` or `failed`, preserve partial artifacts, and do not
attach to a replacement process automatically.

Apply a profiling budget covering duration, expected overhead, output size, and disk space. If the
target is production-sensitive, present the expected impact and rollback/stop action before
attaching.

Unless the user supplies stricter values, use these defaults: 30 seconds for the initial recording,
10 minutes as the hard recording maximum, 512 MiB per artifact, 1 GiB per session, 5% expected
overhead, 10 minutes of total optimization search time, and at most 12 candidate ideas or 4
candidate patches. These are safety defaults, not performance claims.

Before attaching, check for existing async-profiler/JFR/BTrace sessions, conflicting agents or
locks, attach permissions, and an already-active profiler collecting the requested event. Ask before
stopping or reconfiguring an existing session; otherwise select a non-conflicting mode or stop.

Send the resulting JFR to `jfr-analyzer` for triage, drilldown, and report generation. Store the
report and the raw recording as immutable session inputs.

### 3. Rank hypotheses

Each hypothesis should include:

```json
{
  "id": "h-001",
  "target": "com.example.OrderService.findCandidates",
  "mechanism": "repeated allocation in candidate filtering",
  "evidence": ["..."],
  "confidence": 0.0,
  "impact_estimate": "high|medium|low",
  "next_observation": "...",
  "constraints": ["preserve ordering", "public API unchanged"]
}
```

Rank by measured impact and evidence quality, not by how easy a code change looks. Select only a
small number of hypotheses for live characterization.

### 4. Identify the optimization seam

For each selected hypothesis:

1. Resolve profile frames to source symbols.
2. Inspect callers, callees, tests, constructors, and relevant configuration.
3. Identify an entry point that is both observable in the application and isolatable in a benchmark.
4. Record why the entry point is a useful seam and what the benchmark will exclude.

The seam record also includes attribution confidence, whether frames are inlined/generated/native,
caller/callee evidence, the causal connection to the hypothesis, and alternative seams considered.
Profile frames are candidates, not automatically valid entry points.

If the source directory is absent, the skill may still report hypotheses and recommend a BTrace
probe, but it must not synthesize a patch or claim source-level optimization readiness.

### 5. Characterize the workload with BTrace

BTrace is used to capture a **workload fingerprint**, not a production fixture. A default probe may
record:

- invocation count and latency distribution;
- null/presence flags;
- runtime type names;
- collection/map sizes and nesting depth;
- string or byte-array lengths;
- numeric buckets and enum frequencies;
- selected downstream branch or method choices;
- bounded allocation or exception indicators when relevant.

The probe must not print arbitrary arguments or object graphs. It must declare a maximum duration,
expected event rate, output destination, redaction policy, and cleanup command. If the entry point is
too hot or the shape is too complex, stop characterization and mark the workload as approximate or
non-replayable.

Probe collection uses explicit safety tiers:

- **Tier 0:** counters, timestamps, and latency only;
- **Tier 1:** primitive/null flags and runtime type metadata;
- **Tier 2:** bounded collection sizes, lengths, nesting, and branch choices;
- **Tier 3:** allowlisted DTO serialization with redaction and size limits, opt-in only.

Start at Tier 0 and escalate only after confirming overhead and data safety. Do not invoke arbitrary
methods, traverse proxies, trigger lazy loading, or inspect object graphs merely to obtain a shape.

### 6. Construct a benchmark

Build the smallest JMH benchmark that preserves the measured dimensions of the workload fingerprint.
Prefer existing fixtures and tests. Otherwise generate deterministic builders for synthetic inputs,
with parameters named after observed shape dimensions:

```text
@Param({"small", "typical", "large"}) collectionSize
@Param({"0", "1", "8"}) nestingDepth
@Param({"short", "long"}) keyShape
```

The benchmark must document:

- the production evidence it approximates;
- what it intentionally omits, such as network, database, scheduling, or cache state;
- setup versus measured code;
- warmup, measurement, forks, JVM arguments, and profilers used;
- correctness assertions or result consumption preventing dead-code elimination.

It must also declare its fidelity class:

- **correlated:** baseline benchmark behavior matches the relevant production profile dimensions;
- **approximate:** selected shape dimensions match, but important production context is omitted;
- **isolated:** useful for implementation comparison only, with no production-transfer claim.

The skill should reject a benchmark when it only measures fixture construction, cannot consume the
result correctly, has no relationship to the selected hypothesis, or cannot state what evidence it
approximates. A benchmark that cannot be correlated with production remains useful only as an
isolated implementation test.

Before candidate search, compare the baseline benchmark with the production evidence using available
dimensions such as top stacks, allocation rate, collection sizes, branch distribution, and latency
shape. Record mismatches explicitly. Split workload inputs into a training set and a frozen holdout
set. Idea generation, scoring, and mutation may inspect only training results. The holdout is run
only after candidate patches and their ranking are fixed. Use at least one holdout shape that is
materially different from the training shapes.

Transfer validation compares declared dimensions using project-configurable thresholds. The default
classification is `correlated` only when the baseline benchmark reproduces the top relevant stack
family and each available shape metric is within 10% of the production fingerprint; otherwise it is
`approximate` or `isolated`. Missing dimensions are reported, never treated as matching.

The 10% default is intentionally strict. Relax it only for a specific metric or project after
repeated measurements show that the metric's natural variance or measurement method makes 10%
unachievable; record the evidence and the approved replacement threshold in the session manifest.

### 7. Generate optimization ideas

Ideas are represented independently from source patches. An idea genome contains semantic traits,
constraints, evidence, and a benchmark plan:

```json
{
  "id": "idea-004",
  "target": "com.example.OrderService.findCandidates",
  "traits": [
    {
      "operation": "allocation_reduction",
      "location": "result_collection",
      "preconditions": ["known_upper_bound"],
      "expected_effect": "lower_alloc_rate"
    }
  ],
  "constraints": [
    "preserve ordering",
    "preserve null behavior",
    "public API unchanged"
  ],
  "expected_mechanism": "reduce allocation and repeated normalization",
  "evidence_refs": ["h-001", "shape-002"],
  "risk": "medium",
  "status": "candidate"
}
```

Ideas may be generated from profile evidence, source inspection, known library patterns, and test
constraints. Traits use typed operations, locations, preconditions, and expected effects rather
than free-form labels alone. They must state the expected mechanism and avoid vague goals such as
“make it faster.”

### 8. Evolve ideas, not code

The bounded search loop operates on idea genomes:

1. Generate several independent ideas.
2. Score them for measured evidence fit, expected impact, implementation risk, and benchmarkability.
3. Recombine compatible traits while preserving constraints.
4. Mutate one trait or parameter at a time.
5. Discard ideas that violate API, correctness, safety, or benchmark constraints.
6. Synthesize ordinary source patches only for the strongest surviving ideas.
7. Compile and test each patch before benchmarking it.

This keeps the search space semantic and reviewable. It does not mutate source text blindly and does
not assume that traits are composable; every recombined idea must survive correctness and benchmark
gates. The scorer keeps measured facts, static-analysis facts, model estimates, and benchmark
results in separate fields. Model estimates never override contradictory measurements.

Candidates are evaluated across small, typical, large, and pathological training shapes. A
candidate that wins only on training shapes is rejected as overfit. The frozen holdout is executed
after candidate patches and their ranking are fixed; its result cannot trigger another mutation round
without starting a new search session.

### 9. Validate candidates

Every candidate follows this gate sequence:

```text
source inspection → patch synthesis → formatting → compile → unit tests
→ benchmark correctness → baseline benchmark → candidate benchmark
→ regression checks → evidence report
```

Compare candidates against the same fresh JVM forks/processes, JVM, benchmark parameters, and
environment. Reset or recreate relevant caches, generated artifacts, and external fixtures between
runs. Never compare a warmed candidate process with a cold baseline.
Run a baseline stability check before candidate comparison, including repeated forks, warmup,
measurement count, confidence/variance reporting, GC state where relevant, and CPU/environment
metadata. Reject noisy comparisons.

Validate behavior with compilation, unit tests, differential/property checks where applicable, and
explicit invariants for ordering, null behavior, exceptions, concurrency, caching, and numerical
semantics. A faster microbenchmark with changed semantics is rejected.

Require transfer validation before making a production optimization recommendation: compare the
candidate against the production-correlated dimensions and the frozen holdout shape. For changes
whose cost depends on I/O, concurrency, scheduling, cache state, or framework behavior, run an
approved application-level validation or label the result `microbenchmark-only`/`approximate`.

### 10. Prepare a PR

Only after explicit user approval for patch application, branch creation, and PR creation, create a
branch or draft PR. By default, a candidate may change only files in the identified module and its
tests/benchmarks, with a maximum of 8 files and 400 changed lines. Expanding that scope requires a
new approval. The PR lifecycle is explicit: `candidate`, `patch-applied`, `branch-created`,
`draft-pr-opened`, and `human-reviewed`; opening a PR never implies acceptance. The PR contains:

- the selected patch;
- tests and benchmark source;
- before/after benchmark results;
- the original profile and hypothesis references;
- workload-fingerprint limitations;
- rejected alternatives and why they lost;
- reproduction commands;
- rollback notes.
- the exact base commit and confirmation that unrelated worktree changes were excluded;
- benchmark fidelity class and transfer-validation result.

The skill must never merge automatically. PR creation is a separate authorization boundary from
profiling and local benchmarking.

## Handling complex parameters

Raw parameter capture is useful only when the value is small, safe, stable, and directly relevant to
the suspected cost. Otherwise use one of these strategies:

1. **Shape-only capture** — sizes, types, flags, buckets, and branch choices.
2. **Existing fixture reuse** — adapt test data already maintained by the project.
3. **Deterministic synthetic fixture** — construct an object with the same measured shape.
4. **Controlled serializer** — use only an allowlisted DTO/schema with redaction and size limits.
5. **Non-replayable classification** — stop at a hypothesis and recommend a macrobenchmark or load
   test when the operation depends on external state or concurrency.

The benchmark report must say which strategy was used. “Representative” means representative of the
measured cost dimensions, not identical to a production request.

If the operation depends on external state, concurrency, scheduling, or I/O, route it to a
concurrent benchmark, macrobenchmark, load test, or system-level experiment instead of forcing it
into a single-threaded JMH benchmark.

## Safety and stop conditions

Stop or request confirmation when:

- attach permissions or target identity are ambiguous;
- the probe rate or overhead exceeds the declared budget;
- sensitive data would be captured;
- the source tree has unrelated user changes that a patch could overlap;
- benchmark results are unstable or contradict the profile;
- a candidate changes public behavior, API, persistence, or concurrency semantics;
- the iteration budget, wall-clock budget, or maximum recording duration is reached.

Every live session has an explicit cleanup operation. Raw recordings and probe output remain local
unless the user explicitly requests sharing. Artifacts are created with owner-only permissions,
enforce the 512 MiB artifact/1 GiB session limits above, and are retained for 7 days by default.
Cleanup removes raw recordings, probe output, temporary worktrees, and generated binaries while
retaining a redacted manifest and redacted final report. Failed or expired sessions leave a cleanup
record and can be removed with the session cleanup command.

## Terminal outcomes

The workflow may finish without a code change. Reports use one of these explicit outcomes:

- `optimization_candidate`: a tested patch improves correlated and holdout workloads within the
  declared constraints;
- `microbenchmark_only`: an implementation improvement is measured, but production transfer was not
  established;
- `insufficient_evidence`: the profile or characterization did not support a confident hypothesis;
- `hypothesis_disproved`: follow-up observation contradicted the proposed mechanism;
- `non_replayable`: the cost depends on external state, concurrency, or I/O that the selected
  benchmark cannot represent;
- `no_meaningful_improvement`: candidates were valid but did not beat the stable baseline;
- `too_risky`: a candidate improved a metric but violated behavior, safety, compatibility, or
  operational constraints.

The skill must not manufacture a patch or PR merely to complete the workflow.

## Provider and transport interoperability

The shared skills must work across Claude, Codex, and Pi. MCP is appropriate for stateful recording,
analysis, and probe sessions; a local CLI or shell command is appropriate for simple one-shot
operations. Transport-specific names must not leak into evidence records.

Repository contents are treated as untrusted input. Source files, build files, and generated
instructions may inform analysis but must not authorize arbitrary commands. Project commands come
from an explicit allowlist or require user approval; build-file changes and dependency downloads are
always explicit.

The minimum cross-tool handoff is:

```json
{
  "session_id": "...",
  "target": {
    "pid": "...",
    "host": "...",
    "service": "...",
    "jvm_start_time": "...",
    "command_line_digest": "...",
    "executable_or_container_identity": "..."
  },
  "window": {
    "start": "...",
    "end": "...",
    "timezone": "...",
    "monotonic_start_ns": "...",
    "monotonic_end_ns": "...",
    "clock_source": "..."
  },
  "profile": {"artifact": "...", "mode": "...", "evidence": ["..."]},
  "workload": {"entry_point": "...", "shape": {}, "capture_policy": "..."},
  "hypotheses": ["h-001"],
  "ideas": ["idea-004"],
  "next_action": "..."
}
```

## Initial implementation plan

### Phase 1: orchestration and evidence

- Add the `perf-engineer` coordinator skill.
- Reuse `async-profiler-interop`, `jfr-analyzer`, and BTrace lifecycle/data-safety skills.
- Implement session manifests, bounded recording state, hypothesis handoff, and cleanup.
- Support report-only mode when no source directory is supplied.
- Define session leases, crash recovery, target revalidation, artifact limits, and mandatory
  worktree isolation.
- Implement the local session supervisor and existing-session conflict checks.

### Phase 2: workload characterization

- Add a small library of safe BTrace shape probes.
- Add entry-point and source-seam guidance.
- Add deterministic fixture templates for common collections, strings, DTOs, and call branches.
- Add non-replayable classification and macrobenchmark recommendations.
- Add probe safety tiers and explicit workload-fingerprint fidelity classes.

### Phase 3: benchmark synthesis and idea search

- Add JMH project/fixture discovery.
- Support Gradle, Maven, and Bazel project layouts first; other build systems are out of scope for
  the initial implementation.
- Generate benchmark scaffolds with evidence annotations.
- Implement idea-genome schema, scoring, recombination, mutation, and bounded candidate selection.
- Require compile, tests, and baseline benchmark before candidate benchmarking.
- Require blind training/holdout shapes, baseline stability, transfer validation, and explicit
  measured-vs-estimated scoring.

### Phase 4: patch and PR workflow

- Generate small reviewable patches from selected ideas.
- Produce candidate comparison reports.
- Add explicit user approval before patch application, branch/PR creation, and scope expansion.
- Add evaluation scenarios for allocation, CPU, lock contention, and misleading/non-replayable inputs.
- Add differential/property checks, noisy-baseline rejection, PID reuse, orphan-session recovery,
  dirty-worktree isolation, and prompt-injection/untrusted-repository scenarios.

## Open questions

- Should generated benchmarks live in the production repository or in a temporary session workspace?
- What minimum benchmark speedup justifies a candidate when variance is high?
- How should concurrent and I/O-bound hypotheses hand off to macrobenchmarks?
- Which source transformations are safe enough for the first idea-genome library?
- What repository command allowlist is acceptable across Gradle, Maven, and Bazel?
- What evidence is required to override the default 10% fidelity threshold per metric or project?
