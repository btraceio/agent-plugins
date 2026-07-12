# Evidence and gates

Load this reference only for artifact creation, benchmark construction, candidate search, or PR
preparation. It keeps the coordinator compact while making the data contract explicit.

## Session layout

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

The session workspace is outside the user's source worktree by default. Use owner-only permissions,
512 MiB per artifact, 1 GiB per session, and seven-day retention. Cleanup removes raw recordings,
probe output, temporary worktrees, and generated binaries while retaining a redacted manifest and
redacted final report.

## Target fingerprint and timing

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

Revalidate the fingerprint before every attach, record, and probe. Cleanup of supervisor child
processes is allowed after target loss and must not attempt a new target attach.

## Hypothesis record

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

Keep measured facts separate from estimates. Record attribution confidence and whether a profile
frame is inlined, generated, native, or ambiguous.

## Workload fingerprint

Permitted default fields are invocation count, latency distribution, null/presence flags, runtime
types, bounded collection/map sizes, nesting depth, string/byte lengths, numeric buckets, enum
frequencies, selected branch choices, and bounded allocation/exception indicators. Do not capture
arbitrary object graphs or sensitive values.

Probe tiers:

1. counters, timestamps, latency;
2. primitive/null flags and runtime type metadata;
3. bounded sizes, lengths, nesting, and branch choices;
4. allowlisted DTO serialization with redaction and size limits, opt-in only.

## Benchmark fidelity

Document production evidence, omissions, setup versus measured code, JMH parameters, JVM arguments,
and result consumption. Use:

- `correlated` when the baseline reproduces the relevant production stack family and every available
  shape metric is within 10%;
- `approximate` when selected dimensions match but important context is omitted;
- `isolated` when the benchmark is only an implementation comparison.

Missing dimensions are not matches. Relaxing 10% requires repeated variance evidence and a manifest
entry naming the metric, old threshold, new threshold, and justification.

Split inputs into training and frozen holdout sets. The model may not inspect holdout results before
candidate ranking is fixed. Run fresh JVM forks/processes and reset relevant caches and fixtures for
baseline and candidates. Reject noisy baselines.

## Typed idea genome

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
  "constraints": ["preserve ordering", "public API unchanged"],
  "expected_mechanism": "reduce allocation",
  "evidence_refs": ["h-001", "shape-002"],
  "risk": "medium",
  "status": "candidate"
}
```

Ideas evolve semantically; source patches are synthesized only after idea selection. Score measured
evidence fit, expected impact, implementation risk, and benchmarkability, but keep estimates distinct
from measurements.

## Candidate gates

```text
source inspection → patch synthesis → formatting → compile → unit tests
→ differential/property checks → benchmark correctness → stable baseline
→ training workloads → frozen holdout → transfer validation → evidence report
```

The default patch scope is the identified module and tests/benchmarks, at most 8 files and 400
changed lines. Scope expansion requires approval. Application-level validation is required for
changes whose cost depends on I/O, concurrency, scheduling, cache state, or framework behavior;
otherwise report `microbenchmark_only` or `approximate`.

## Cross-tool handoff

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
