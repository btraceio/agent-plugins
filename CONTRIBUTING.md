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
node scripts/check-tool-references.js                    # published server, via jbang
node scripts/check-tool-references.js --jar path/to.jar  # a locally built shadow jar
```

It starts the server, asks it for `tools/list`, and fails naming each missing tool with its file and
line. It is not part of `validate-marketplace.sh`, because it needs the published server.

