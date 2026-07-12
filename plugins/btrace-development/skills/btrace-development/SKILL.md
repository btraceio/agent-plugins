---
name: btrace-development
description: Use when implementing, testing, packaging, or reviewing changes in the BTrace repository.
---

# BTrace Development

## Repository conventions

- Read the repository `AGENTS.md` before changing code.
- BTrace is a multi-module Gradle project. Core modules include `btrace-agent`,
  `btrace-client`, `btrace-compiler`, `btrace-instr`, `btrace-runtime`, and `btrace-dist`.
- Do not use fully qualified type names in Java source; add imports instead.
- Run Spotless and relevant module tests before committing changes.

## Build and test

- Prefer a workspace-local Gradle cache in restricted environments:
  `GRADLE_USER_HOME=$(pwd)/.gradle-user`.
- Redirect Gradle output to a log file, filter it to relevant lines, then inspect the filtered log.
- Build the distribution with `:btrace-dist:build` when integration tests need the masked JAR.

## Masked JAR changes

The distribution is one masked `btrace.jar`. When adding classes, decide whether they are:

- agent-only;
- client-only; or
- shared between agent and client.

Update the appropriate classdata preparation task in `btrace-dist/build.gradle`, then rebuild with
`./gradlew clean :btrace-dist:btraceJar` before validating integration behavior.
