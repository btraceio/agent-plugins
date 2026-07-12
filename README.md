# BTrace Agent Plugins

This repository is the BTrace plugin marketplace for Claude Code, Codex, and Pi.

Each plugin has one shared implementation under `plugins/`. Tool-specific manifests live beside
one another so the workflow instructions and supporting scripts are maintained once.

## Included plugins

| Plugin | Purpose |
| --- | --- |
| `btrace-development` | Repository conventions and build guidance for BTrace development. |
| `btrace-observability` | A composable SRE skill suite for diagnosing Java applications with BTrace probes. |

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

Operational guidance in skills is deliberately self-contained. Keep it in sync with the BTrace
source checkout rather than linking out to source documentation. Before the first push in a clone,
run `scripts/install-git-hooks.sh`; it installs the tracked pre-push hook. Set
`BTRACE_SOURCE_DIR` to a BTrace checkout when it is not available as `../btrace`.
