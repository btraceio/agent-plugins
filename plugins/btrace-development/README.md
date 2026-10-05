# BTrace Development

`btrace-development` is for contributors working on BTrace itself or building BTrace extensions.
It carries the repository conventions, build and test practice, and extension design guidance an
agent needs to make reviewable changes. For diagnosing live Java applications with BTrace probes,
use the `btrace-observability` plugin instead.

## Start with a development task

After installing the plugin, describe the change you want to make. For example:

- “Add a new class to the agent and make sure it lands in the masked JAR.”
- “Run the tests for `btrace-instr` and fix any Spotless violations.”
- “Design an extension that exposes Kafka consumer lag to probes.”
- “Can `@ExternalType` model this target method, or do I need method handles?”
- “Should this be a built-in probe or a packaged extension, and what permissions does it need?”

## Skill suite

| Skill | Use it for |
| --- | --- |
| `btrace-development` | Repository conventions, Gradle build and test, and masked JAR class placement. |
| `btrace-extension-authoring` | Designing an extension's service surface, `@ExternalType` versus hand-written method handles, class loading, and version tolerance. |
| `btrace-extensions-and-permissions` | Choosing between built-in probes and extensions, installing extensions, and granting minimal permissions. |

## Conventions the plugin applies

- Reads the repository `AGENTS.md` before changing code.
- Uses imports rather than fully qualified type names in Java source.
- Runs Spotless and the relevant module tests before committing.
- Redirects Gradle output to a log, filters it, and reads the filtered result.
- Classifies new classes as agent-only, client-only, or shared, updates the classdata preparation in
  `btrace-dist/build.gradle`, and rebuilds the masked JAR before validating integration behavior.

## Extension safety

Extensions start in analysis: the target entry points, supported versions, and service surface are
settled before any files are created. Service signatures stay free of target-library types so the
extension loads when the target is absent or a different version. Permissions are granted only as
needed; `grantAll=true` and the trusted-only `btrace.system.appendJar` escape hatch are not used
by default. Verification ends with one real trace against the target library, not a stub.

Fat agents and launch-time packaging for deployment are covered by the `btrace-observability`
plugin's startup and packaging guidance.
