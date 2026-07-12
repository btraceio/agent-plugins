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
