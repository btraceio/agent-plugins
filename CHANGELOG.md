# Changelog

## Unreleased

- Initial Claude Code, Codex, and Pi marketplace structure.
- Goal-first BTrace observability skill suite with cloud/SRE workflows.
- Provider-neutral eval corpus and repository validation gate.
- `btrace-legacy-libs-migration` skill for moving retired `libs`/profile packaging into an extension.
- Moved `btrace-extensions-and-permissions` into `btrace-development`; extension authoring is build-time work, not incident diagnosis.
- `btrace-extension-authoring` skill for designing a new extension against a target library.
- `jafar-perf` `compare` skill covers the `jfr_compare` significance rules and thin-data results that follow jafar `v0.29.0`.
- `btrace-observability` MCP server runs BTrace `3.0.0-RC1` from Maven Central instead of the 3.0.0 snapshot.
- `jafar-perf` skills say how the pinned jfr-mcp `0.29.0` attributes samples to threads, reads `jfr_use`/`jfr_tsa` time windows and counts multi-chunk results, and what changes from the first release after it (btraceio/jafar #204, #206, #248).
