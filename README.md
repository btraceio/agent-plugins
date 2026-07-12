# BTrace Agent Plugins

This repository is the BTrace plugin marketplace for Claude Code, Codex, and Pi.

Each plugin has one shared implementation under `plugins/`. Tool-specific manifests live beside
one another so the workflow instructions and supporting scripts are maintained once.

## Included plugins

| Plugin | Purpose |
| --- | --- |
| `btrace-development` | Repository conventions and build guidance for BTrace development. |
| [`btrace-observability`](plugins/btrace-observability/README.md) | A composable SRE skill suite for diagnosing Java applications with BTrace probes. |

## Layout

```text
.claude-plugin/marketplace.json    Claude Code catalog
.agents/plugins/marketplace.json   Codex catalog
package.json                        Pi package manifest
plugins/<name>/skills/              shared Agent Skills content
plugins/<name>/.claude-plugin/      Claude Code manifest
plugins/<name>/.codex-plugin/       Codex manifest
```

## Install

### Claude Code

```text
/plugin marketplace add btraceio/agent-plugins
/plugin install btrace-observability@btraceio-agent-plugins
```

### Pi

```sh
pi install git:github.com/btraceio/agent-plugins
```

### Codex

Add this repository as a marketplace, then install `btrace-observability` from the
`BTrace Agent Plugins` catalog. Codex consumes the catalog at
`.agents/plugins/marketplace.json`.

## Contributing

Keep portable workflow instructions and scripts in `plugins/<name>/skills/`. Add only the
minimum platform-specific metadata or integration configuration required by each host.

### Validation setup (required before pushing)

Operational guidance in skills is deliberately self-contained: copy the relevant BTrace knowledge
into the skill instead of linking to BTrace source documentation. The validation gate keeps that
copy in sync with the BTrace source checkout.

From a fresh clone, install the tracked pre-push hook once:

```sh
scripts/install-git-hooks.sh
```

The gate expects a BTrace checkout at `../btrace`. Otherwise, set its location before validating or
pushing:

```sh
export BTRACE_SOURCE_DIR=/path/to/btrace
scripts/validate-marketplace.sh
git push
```

The hook runs this validation automatically before every push. It validates the Claude marketplace,
JSON manifests, skill front matter, the absence of source-documentation URLs in skills, and selected
facts against the BTrace checkout. Update `references/btrace-source-facts.txt` together with any
intentional change to embedded BTrace operational guidance.
