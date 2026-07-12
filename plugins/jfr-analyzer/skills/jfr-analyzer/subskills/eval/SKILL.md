---
name: eval
description: Eval subskill for jfr-analyzer. Routes regen|run|score|help. Manages corpus regeneration (MCP event extraction + oracle validation), eval runs, and scoring.
allowed-tools: Read Write Bash(find *) Bash(ls *) mcp__jfr-mcp__jfr_open mcp__jfr-mcp__jfr_list_types mcp__jfr-mcp__jfr_close
---

## Argument parsing

Parse the first token of $ARGUMENTS after "eval":
- Empty or "help" → print help and stop.
- "regen"  → SUBCOMMAND=regen; optional `--validate` flag
- "run"    → SUBCOMMAND=run; optional `--scenario <id>`, `--formats jfr,otlp,pprof`, `--runs N`, `--parallel N`
- "score"  → SUBCOMMAND=score; optional `--scenario <id>`, `--multi-judge`

EVAL_DIR = resolve four levels up from this SKILL.md to reach the jfr-analyzer plugin root, then append `/eval`.

## Help text

If SUBCOMMAND=help or $ARGUMENTS is empty, output exactly:

```
/jfr-analyzer eval regen [--validate]     Regenerate JFR corpus, extract event types, optionally oracle-validate
/jfr-analyzer eval run   [options]         Run triage against corpus scenarios
/jfr-analyzer eval score [options]         Score eval results and generate report

eval run options:
  --scenario <id>          Score only this scenario
  --formats  jfr,otlp,pprof  Formats to test (default: jfr)
  --runs N                 Runs per scenario (default: uses eval_runs in manifest)
  --parallel N             Max concurrent scenarios (default: 4)

eval score options:
  --scenario <id>          Score only this scenario
  --multi-judge            Use Claude + GPT-4o judge panel (requires OPENAI_API_KEY)
```

## regen subcommand

### Phase 1 — Run regen.sh (shell, no MCP)

Run:
```bash
bash <EVAL_DIR>/scripts/regen.sh
```

Report exit status. If non-zero, show last 20 lines of output and stop.

### Phase 2 — Event type extraction (MCP)

Read `<EVAL_DIR>/corpus/manifest.json`.

For each scenario in the manifest:

1. `file_path` = `<EVAL_DIR>/corpus/<scenario.file>`
2. Call `mcp__jfr-mcp__jfr_open` with path=`<file_path>`
3. Extract `sessionId` from the response.
4. Call `mcp__jfr-mcp__jfr_list_types` with `sessionId`
5. Call `mcp__jfr-mcp__jfr_close` with `sessionId`
6. Collect the list of event type names from the jfr_list_types response

After iterating all scenarios, use the Write tool to update manifest.json:
For each scenario by index, set `jfr_event_types` to the list of type names collected in step 6.

### Phase 3 — Oracle validation (if `--validate` flag or user approves)

If `--validate` was NOT passed, ask the user:
  "Run oracle validation? This runs triage against each recording to verify ground truth. [y/N]"

For each scenario where `expected.scoring_tier = "structural"`:

  Run triage in eval mode by reading triage SKILL.md with arguments:
  `--eval <EVAL_DIR>/corpus/<id>.jfr --output-dir <EVAL_DIR>/results/<id>/oracle/`

  After triage completes, Read `<EVAL_DIR>/results/<id>/oracle/focus.json`. Check:
  - Every `expected.focusAreas[].id` appears in `focusAreas[].id` in the output
  - No `expected.absent_area_ids` appear in output `focusAreas[].id`
  - Every `expected.crossAreaCorrelations[].kind` appears in output `crossAreaCorrelations[].kind`

  If any check fails, output:
  ```
  ❌ Oracle validation FAILED for <id>
     Missing areas: [...]
     Unexpected areas: [...]
     Missing correlations: [...]

     Fix options:
     1. Adjust the workload in eval/testapp/scenarios/<ScenarioClass>.java and re-run regen.sh
     2. Update expected ground truth in manifest.json if the skill output is actually correct
  ```
  Stop after first failure.

For each scenario where `expected.scoring_tier = "semantic"`:
  Print the rubric and the oracle focus.json side-by-side for human review.
  Ask: "Does this look correct for <id>? [y/N]"

### Commit prompt after successful regen

After Phase 2 (and Phase 3 if run), output:
```
✓ Corpus regenerated. Stage and commit:
  git add jfr-analyzer/eval/corpus/
  git commit -m "feat(jfr-analyzer): regenerate eval corpus"
```

## run subcommand

Parse options from $ARGUMENTS:
- `--scenario <id>` → filter to that scenario only
- `--formats <csv>` → comma-split; default `jfr`
- `--runs N` → override `eval_runs` from manifest
- `--parallel N` → max concurrent scenarios (default 4)

Read manifest.json. For each scenario (filtered if --scenario):

N_RUNS = `--runs` value if provided, else `scenario.eval_runs`.

For each run index k = 1 to N_RUNS (runs within a scenario are SEQUENTIAL — do NOT parallelize):
  Read the triage subskill and execute it with arguments:
  `--eval <EVAL_DIR>/corpus/<scenario.id>.jfr --output-dir <EVAL_DIR>/results/<scenario.id>/run-<k>/`

Dispatch batches of `--parallel` scenarios concurrently. Within each scenario, runs are sequential.

If `--formats` includes `otlp` or `pprof`:
  After the JFR run batch, derive formats:
  ```bash
  jfrconv <EVAL_DIR>/corpus/<id>.jfr <EVAL_DIR>/corpus/<id>.otlp   # for otlp
  jfrconv <EVAL_DIR>/corpus/<id>.jfr <EVAL_DIR>/corpus/<id>.pb.gz  # for pprof
  ```
  Run additional triage passes against each derived format file, with output dirs named `<id>-otlp/run-<k>/` etc.

After all runs complete, output:
```
✓ Eval runs complete. Results in eval/results/
  Next: /jfr-analyzer eval score
```

## score subcommand

Parse `--scenario` and `--multi-judge` flags from $ARGUMENTS.

Check if `<EVAL_DIR>/scripts/.venv` exists:
- If not: run `cd <EVAL_DIR>/scripts && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`

Run:
```bash
cd <EVAL_DIR>/scripts && .venv/bin/python score.py [--scenario <id> if set] [--multi-judge if set]
```

Display the generated report content.
