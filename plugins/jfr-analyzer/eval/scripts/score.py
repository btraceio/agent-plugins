#!/usr/bin/env python3
"""
jfr-analyzer eval scorer.

Usage:
    python score.py [--scenario <id>] [--multi-judge]

Reads:  eval/results/<id>/run-*/focus.json  and  .../breakpoint.txt
Reads:  eval/corpus/manifest.json
Writes: eval/results/report.md

Prerequisites:
    ANTHROPIC_API_KEY  — required for semantic scoring
    OPENAI_API_KEY     — optional; enables multi-judge mode
"""
import argparse
import json
import math
import os
import sys
from pathlib import Path

EVAL_DIR    = Path(__file__).parent.parent
CORPUS_DIR  = EVAL_DIR / "corpus"
RESULTS_DIR = EVAL_DIR / "results"
MANIFEST    = CORPUS_DIR / "manifest.json"


# ── Data loading ──────────────────────────────────────────────────────────────

def load_manifest() -> dict:
    with open(MANIFEST) as f:
        return json.load(f)


def load_run_outputs(scenario_id: str, n_runs: int) -> list:
    """Load focus.json + breakpoint.txt for each run."""
    runs = []
    for k in range(1, n_runs + 1):
        run_dir = RESULTS_DIR / scenario_id / f"run-{k}"
        focus_path = run_dir / "focus.json"
        bp_path    = run_dir / "breakpoint.txt"
        if not focus_path.exists():
            continue
        with open(focus_path) as f:
            focus = json.load(f)
        breakpoint_text = bp_path.read_text() if bp_path.exists() else ""
        runs.append({"focus": focus, "breakpoint_text": breakpoint_text, "run": k})
    return runs


# ── Structural scoring ────────────────────────────────────────────────────────

def score_structural(scenario: dict, runs: list) -> dict:
    """Structural exact-match scoring for Groups 1 & 2."""
    expected      = scenario["expected"]
    exp_areas     = {a["id"]: a for a in expected.get("focusAreas", [])}
    exp_absent    = set(expected.get("absent_area_ids", []))
    exp_corr_kinds = {c["kind"] for c in expected.get("crossAreaCorrelations", [])}
    exp_start_here = {a["id"] for a in expected.get("focusAreas", []) if a.get("startHere")}

    pass_threshold_recall    = 0.9
    pass_threshold_precision = 0.8

    per_run = []
    for run in runs:
        focus       = run["focus"]
        det_areas   = {a["id"]: a for a in focus.get("focusAreas", [])}
        det_kinds   = {c["kind"] for c in focus.get("crossAreaCorrelations", [])}
        det_start   = {a["id"] for a in focus.get("focusAreas", []) if a.get("startHere")}

        # Recall: expected areas found
        recall = (len(set(exp_areas) & set(det_areas)) / len(exp_areas)
                  if exp_areas else (1.0 if not det_areas else 0.0))

        # Precision: detected areas that are expected
        precision = (len(set(exp_areas) & set(det_areas)) / len(det_areas)
                     if det_areas else (1.0 if not exp_areas else 0.0))

        # startHere accuracy
        start_here_acc = (len(exp_start_here & det_start) / len(exp_start_here)
                          if exp_start_here else (1.0 if not det_start else 0.0))

        # Absent area penalty
        absent_penalty = sum(-0.5 for aid in exp_absent if aid in det_areas)

        # Correlation recall
        corr_recall = (len(exp_corr_kinds & det_kinds) / len(exp_corr_kinds)
                       if exp_corr_kinds else None)

        passed = (
            recall    >= pass_threshold_recall
            and precision >= pass_threshold_precision
            and (not exp_start_here or start_here_acc >= 0.5)
        )

        per_run.append({
            "run":            run["run"],
            "recall":         round(recall, 3),
            "precision":      round(precision, 3),
            "start_here_acc": round(start_here_acc, 3),
            "absent_penalty": absent_penalty,
            "corr_recall":    round(corr_recall, 3) if corr_recall is not None else None,
            "passed":         passed,
        })

    n_total  = len(per_run)
    n_passed = sum(1 for r in per_run if r["passed"])

    pass_at_1 = per_run[0]["passed"] if per_run else False
    pass_at_k = _pass_at_k(n_total, n_passed, k=n_total) if n_total > 1 else float(pass_at_1)

    mean_recall    = _mean([r["recall"]    for r in per_run])
    mean_precision = _mean([r["precision"] for r in per_run])
    ci_recall      = _ci95([r["recall"]    for r in per_run])

    start_majority = (sum(1 for r in per_run if r["start_here_acc"] >= 0.5) > n_total / 2
                      if n_total > 0 else False)

    overall_pass = n_passed >= math.ceil(n_total * 0.6) and start_majority

    return {
        "tier":           "structural",
        "per_run":        per_run,
        "n_runs":         n_total,
        "n_passed":       n_passed,
        "pass_at_1":      pass_at_1,
        "pass_at_k":      round(pass_at_k, 3),
        "mean_recall":    round(mean_recall, 3),
        "mean_precision": round(mean_precision, 3),
        "ci_recall_95":   [round(v, 3) for v in ci_recall],
        "start_majority": start_majority,
        "overall_pass":   overall_pass,
    }


# ── Semantic scoring ──────────────────────────────────────────────────────────

JUDGE_PROMPT_TMPL = """\
SCENARIO: {description}
JUDGE QUESTION: {judge_question}
REQUIRED EVIDENCE: {required_evidence_keywords}
MUST NOT APPEAR: {must_not_hallucinate}

SKILL OUTPUT:
--- focus.json ---
{focus_json}
--- breakpoint text ---
{breakpoint_text}
---

RUBRIC:
0.0 — missed the problem or wrong root cause
0.5 — right area, wrong mechanism or severity
1.0 — correct problem, root cause, and severity

Step 1: reason through what the skill found (2-3 sentences)
Step 2: list which required_evidence_keywords appear
Step 3: list any must_not_hallucinate items that appeared
Step 4: output ONLY JSON: {{"reasoning":"...","evidence_found":[...],"hallucinations":[...],"score":0.0}}
"""


def _call_claude(prompt: str) -> dict:
    import anthropic
    client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])
    msg = client.messages.create(
        model="claude-opus-4-8",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    text = msg.content[0].text
    start = text.rfind("{")
    end   = text.rfind("}") + 1
    if start == -1:
        raise ValueError(f"No JSON in Claude response: {text!r}")
    return json.loads(text[start:end])


def _call_gpt4o(prompt: str) -> dict:
    import openai
    client = openai.OpenAI(api_key=os.environ["OPENAI_API_KEY"])
    resp = client.chat.completions.create(
        model="gpt-4o",
        messages=[{"role": "user", "content": prompt}],
        max_tokens=1024,
    )
    text = resp.choices[0].message.content
    start = text.rfind("{")
    end   = text.rfind("}") + 1
    return json.loads(text[start:end])


def score_semantic(scenario: dict, runs: list, multi_judge: bool) -> dict:
    """LLM-as-judge scoring for Groups 3 & 4."""
    rubric      = scenario["expected"]["rubric"]
    description = scenario["description"]
    pass_threshold = 0.7
    per_run = []

    for run in runs:
        focus_json = json.dumps(run["focus"], indent=2)
        bp_text    = run["breakpoint_text"]

        prompt = JUDGE_PROMPT_TMPL.format(
            description=description,
            judge_question=rubric["judge_question"],
            required_evidence_keywords=", ".join(rubric["required_evidence_keywords"]),
            must_not_hallucinate=", ".join(rubric["must_not_hallucinate"]),
            focus_json=focus_json,
            breakpoint_text=bp_text,
        )

        scores = []
        verdicts = []

        try:
            v = _call_claude(prompt)
            scores.append(v["score"])
            verdicts.append({"provider": "claude", **v})
        except Exception as e:
            verdicts.append({"provider": "claude", "error": str(e), "score": 0.0})
            scores.append(0.0)

        if multi_judge and os.environ.get("OPENAI_API_KEY"):
            try:
                v = _call_gpt4o(prompt)
                scores.append(v["score"])
                verdicts.append({"provider": "gpt-4o", **v})
            except Exception as e:
                verdicts.append({"provider": "gpt-4o", "error": str(e), "score": 0.0})
                scores.append(0.0)

        mean_score = _mean(scores)
        disagreement = (len(scores) > 1 and abs(scores[0] - scores[-1]) > 0.5)
        passed = mean_score >= pass_threshold and not disagreement

        per_run.append({
            "run":          run["run"],
            "mean_score":   round(mean_score, 3),
            "verdicts":     verdicts,
            "disagreement": disagreement,
            "passed":       passed,
        })

    n_total  = len(per_run)
    n_passed = sum(1 for r in per_run if r["passed"])
    pass_at_1 = per_run[0]["passed"] if per_run else False
    pass_at_k = _pass_at_k(n_total, n_passed, k=n_total) if n_total > 1 else float(pass_at_1)
    mean_score = _mean([r["mean_score"] for r in per_run])
    needs_review = any(r["disagreement"] for r in per_run)
    overall_pass = n_passed >= math.ceil(n_total * 0.6) and not needs_review

    return {
        "tier":         "semantic",
        "per_run":      per_run,
        "n_runs":       n_total,
        "n_passed":     n_passed,
        "pass_at_1":    pass_at_1,
        "pass_at_k":    round(pass_at_k, 3),
        "mean_score":   round(mean_score, 3),
        "needs_review": needs_review,
        "overall_pass": overall_pass,
    }


# ── Statistics helpers ────────────────────────────────────────────────────────

def _mean(values: list) -> float:
    return sum(values) / len(values) if values else 0.0


def _ci95(values: list) -> tuple:
    if len(values) < 2:
        v = values[0] if values else 0.0
        return (v, v)
    from scipy import stats
    ci = stats.t.interval(0.95, df=len(values) - 1,
                          loc=_mean(values),
                          scale=stats.sem(values))
    return (max(0.0, ci[0]), min(1.0, ci[1]))


def _pass_at_k(n: int, c: int, k: int) -> float:
    """pass@k = 1 - C(n-c, k) / C(n, k)"""
    if n - c < k:
        return 1.0
    return 1.0 - math.comb(n - c, k) / math.comb(n, k)


# ── Report rendering ──────────────────────────────────────────────────────────

def render_report(results: list, manifest: dict, date: str) -> str:
    structural = [r for r in results if r["score"]["tier"] == "structural"]
    semantic   = [r for r in results if r["score"]["tier"] == "semantic"]

    lines = [f"# jfr-analyzer Eval Results — {date}", ""]

    if structural:
        n_runs = structural[0]["score"]["n_runs"] if structural else 0
        lines += [f"## Structural (Groups 1 & 2) — {n_runs} runs each", ""]
        lines += ["| Scenario | Recall p@1 | p@k | Precision p@1 | startHere | Corr. | Result |"]
        lines += ["|----------|-----------|-----|---------------|-----------|-------|--------|"]
        for r in structural:
            s = r["score"]
            pr = s["per_run"][0] if s["per_run"] else {}
            corr = pr.get("corr_recall")
            corr_str = f"{corr:.1f}" if corr is not None else "N/A"
            sh_votes = sum(1 for x in s["per_run"] if x.get("start_here_acc", 0) >= 0.5)
            result_str = "**PASS**" if s["overall_pass"] else "FAIL"
            lines.append(
                f"| {r['id']:<28} "
                f"| {pr.get('recall', 0):.1f} "
                f"| {s['pass_at_k']:.1f} "
                f"| {pr.get('precision', 0):.1f} "
                f"| {'✓' if s['start_majority'] else '✗'} {sh_votes}/{s['n_runs']} "
                f"| {corr_str} "
                f"| {result_str} |"
            )
        lines.append("")

    if semantic:
        n_runs = semantic[0]["score"]["n_runs"] if semantic else 0
        lines += [f"## Semantic (Groups 3 & 4) — {n_runs} runs each", ""]
        lines += ["| Scenario | Score p@1 | p@k | Agreement | Result |"]
        lines += ["|----------|-----------|-----|-----------|--------|"]
        for r in semantic:
            s = r["score"]
            pr = s["per_run"][0] if s["per_run"] else {}
            agree = "⚠️" if s["needs_review"] else "✓"
            result_str = ("**PASS**" if s["overall_pass"]
                         else ("⚠️ needs-human-review" if s["needs_review"] else "FAIL"))
            lines.append(
                f"| {r['id']:<28} "
                f"| {pr.get('mean_score', 0):.1f} "
                f"| {s['pass_at_k']:.1f} "
                f"| {agree} "
                f"| {result_str} |"
            )
        lines.append("")

    struct_pass  = sum(1 for r in structural if r["score"]["overall_pass"])
    sem_pass     = sum(1 for r in semantic   if r["score"]["overall_pass"])
    struct_recall = (_mean([r["score"]["mean_recall"] for r in structural])
                     if structural else None)
    struct_prec  = (_mean([r["score"]["mean_precision"] for r in structural])
                    if structural else None)
    sem_score    = (_mean([r["score"]["mean_score"] for r in semantic])
                    if semantic else None)

    lines += ["## Summary"]
    if structural:
        lines.append(
            f"Structural: {struct_pass}/{len(structural)} PASS"
            f"  ·  mean recall {struct_recall:.2f}"
            f"  ·  mean precision {struct_prec:.2f}"
        )
    if semantic:
        lines.append(
            f"Semantic:   {sem_pass}/{len(semantic)} PASS"
            f"  ·  mean judge score {sem_score:.2f}"
        )
    return "\n".join(lines) + "\n"


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Score jfr-analyzer eval runs")
    parser.add_argument("--scenario", help="Score only this scenario ID")
    parser.add_argument("--multi-judge", action="store_true",
                        help="Use Claude + GPT-4o as judge panel")
    args = parser.parse_args()

    if "ANTHROPIC_API_KEY" not in os.environ:
        print("ERROR: ANTHROPIC_API_KEY not set (required for semantic scoring)")
        sys.exit(1)

    manifest = load_manifest()
    scenarios = manifest["scenarios"]
    if args.scenario:
        scenarios = [s for s in scenarios if s["id"] == args.scenario]
        if not scenarios:
            print(f"ERROR: scenario '{args.scenario}' not found in manifest")
            sys.exit(1)

    results = []
    for scenario in scenarios:
        sid   = scenario["id"]
        tier  = scenario["expected"]["scoring_tier"]
        n_runs = scenario.get("eval_runs", 5)

        runs = load_run_outputs(sid, n_runs)
        if not runs:
            print(f"  [{sid}] No run outputs found — skipping")
            continue

        print(f"  [{sid}] scoring {len(runs)} runs ({tier})...")
        if tier == "structural":
            score = score_structural(scenario, runs)
        else:
            score = score_semantic(scenario, runs, args.multi_judge)

        results.append({"id": sid, "group": scenario["group"], "score": score})
        status = "PASS" if score["overall_pass"] else "FAIL"
        print(f"    → {status}")

    from datetime import datetime, timezone
    date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    report = render_report(results, manifest, date_str)

    report_path = RESULTS_DIR / "report.md"
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report)
    print(f"\nReport written to {report_path}")
    print(report)


if __name__ == "__main__":
    main()
