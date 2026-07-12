# BTrace Agent Plugins

This repository is the BTrace plugin marketplace for Claude Code, Codex, and Pi.

Each plugin has one shared implementation under `plugins/`. Tool-specific manifests live beside
one another so the workflow instructions and supporting scripts are maintained once.

## Included plugins

| Plugin | Purpose |
| --- | --- |
| `btrace-development` | Repository conventions and build guidance for BTrace development. |

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
/plugin install btrace-development@btraceio-agent-plugins
```

### Pi

```sh
pi install git:github.com/btraceio/agent-plugins
```

### Codex

Add this repository as a marketplace, then install `btrace-development` from the
`BTrace Agent Plugins` catalog. Codex consumes the catalog at
`.agents/plugins/marketplace.json`.

## Contributing

Keep portable workflow instructions and scripts in `plugins/<name>/skills/`. Add only the
minimum platform-specific metadata or integration configuration required by each host.
