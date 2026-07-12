# BTrace observability evals

`btrace-observability.json` is a provider-neutral eval corpus for the bundled skill suite. Each case
contains a realistic operator prompt, the skills expected to participate, phrases that should appear
in a good answer, phrases that indicate unsafe guidance, and a human-readable rubric.

The corpus does not assume a particular model or vendor runner. Capture one response per case as
JSONL:

```json
{"id":"endpoint-local-first-look","text":"...model response..."}
```

Score responses with:

```sh
node scripts/score-evals.js eval-responses.jsonl
```

To connect a model/provider-specific runner, make an executable that reads one JSON object from
stdin (`id`, `prompt`, and `requiredSkills`) and writes the response text to stdout. Run the corpus
through it with:

```sh
node scripts/run-evals.js ./my-agent-runner eval-responses.jsonl
node scripts/score-evals.js eval-responses.jsonl
```

The scorer is intentionally lexical and advisory: it catches missing operational guardrails and
obvious unsafe suggestions (while allowing a response to explicitly warn against a dangerous
pattern), but human review remains necessary for probe correctness.
