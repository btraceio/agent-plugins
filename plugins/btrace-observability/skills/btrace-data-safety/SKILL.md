---
name: btrace-data-safety
description: Use when a BTrace diagnostic may affect production performance, access sensitive data, require unsafe/trusted mode, or need a wider instrumentation scope.
---

# Data Safety and Production Guardrails

Before deployment, state the target, scope, expected rate, output fields, duration, retention, and
rollback trigger. Default to metadata (class, method, duration, exception type) instead of values.

- Never print secrets, bearer tokens, cookies, authorization headers, complete request/response
  bodies, credentials, or private customer data.
- Treat `@Return`, method arguments, SQL text, stack traces, and object `str()` output as sensitive
  until reviewed.
- Prefer aggregation, sampling, or a fixed time window for hot paths.
- BTrace's default verifier restrictions are a safety feature. Do not use `-u`, trusted mode, or
  broad permissions unless the user has justified the need and explicitly approved a controlled
  environment.
- For broad probes, set a narrow package/framework boundary, a maximum duration, and an explicit
  stop command. Escalate to JVM-wide matching only after less invasive options fail.

The correct scope is the smallest scope that produces useful evidence within the incident window;
that can be exact-method, bounded package, or deliberately broad discovery instrumentation.
