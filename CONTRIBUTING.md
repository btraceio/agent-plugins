# Contributing

Keep each marketplace plugin self-contained and keep reusable skills under its `skills/` directory.
Do not replace embedded operational guidance with links to another repository.

Before opening a pull request:

```sh
scripts/install-git-hooks.sh
export BTRACE_SOURCE_DIR=/path/to/btrace
scripts/validate-marketplace.sh
```

For behavioral changes, add or update an eval case and include a captured response review. Use
conventional commit messages and do not change plugin versions until a release is being published.

Plugins that start the `jfr-mcp` server (`jafar-perf`, `jfr-analyzer`) name its tools in their skills
and agents, and the server lives in another repository. After changing those names, or when a new
`jfr-mcp` release lands, check that every referenced tool still exists:

```sh
node scripts/check-tool-references.js                    # latest published server, via jbang --fresh
node scripts/check-tool-references.js --pinned           # the release the plugins pin
node scripts/check-tool-references.js --jar path/to.jar  # a locally built shadow jar
```

It starts the server, asks it for `tools/list`, and fails naming each missing tool with its file and
line. It is not part of `validate-marketplace.sh`, because it needs the published server.

The plugins pin `jfr-mcp` to an exact release so that a new upstream version cannot change an install
out from under the skills. To move the pin, update `io.btrace:jfr-mcp:<version>` in
`plugins/jafar-perf/.mcp.json`, `plugins/jfr-analyzer/.mcp.json`, `extensions/mcp-servers.ts` and both
plugin READMEs, then run the `--pinned` check. `validate-repository.js` fails if they disagree or if
the `jfr-mcp@btraceio` catalog alias comes back.

