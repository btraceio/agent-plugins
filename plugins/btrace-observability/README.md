# BTrace Observability

`btrace-observability` helps an operator answer live-Java incident questions without first needing
to know BTrace syntax. Describe the outcome you need; the plugin selects and combines the skills
needed to design, deploy, observe, and remove an appropriate probe.

## Start with an incident question

After installing the plugin, ask in operational language. For example:

- “What happens when `/recommendations` is called?”
- “Why is `POST /orders` slow in this pod?”
- “Find the exception source for these 5xx responses.”
- “Is `OrderRepository.findCandidates` actually issuing a query?”
- “Trace this consumer on one canary pod for five minutes.”
- “The JVM is on a production VM behind a bastion; how can I attach safely?”
- “This is a distroless Kubernetes workload—what is the launch-time BTrace option?”

The plugin should respond with the interpretation of the question, relevant skill combination, a
probe/deployment plan, an observation window, data-handling notes, and cleanup steps. It asks for
only material missing context, such as the target runtime, PID/pod, method/endpoint, and whether
sensitive values may be observed.

## Skill suite

All of these are bundled in this one plugin and may be combined for an incident:

| Skill | Use it for |
| --- | --- |
| `btrace-observability` | Coordinates the investigation and selects specialist skills. |
| `btrace-endpoint-diagnostics` | Request paths, HTTP/RPC handlers, and downstream flow. |
| `btrace-latency-analysis` | Slow endpoints, methods, and dependency calls. |
| `btrace-failure-analysis` | Exceptions, failed requests, and error boundaries. |
| `btrace-oneliner-triage` | Quick, disposable observations using the BTrace DSL. |
| `btrace-runtime-access` | SSH, Docker, Kubernetes, sidecars, and cloud runtime boundaries. |
| `btrace-probe-lifecycle` | Attach, output capture, verification, reconnect, and removal. |
| `btrace-data-safety` | Sensitive output, production load, permissions, and scope escalation. |
| `btrace-extensions-and-permissions` | Metrics exporters, extensions, and minimal permission grants. |
| `btrace-mcp-operations` | Local BTrace MCP server workflows. |
| `btrace-startup-and-packaging` | Launch-time agents, fat agents, immutable images, and distroless workloads. |

## Typical compositions

| Situation | Skills combined |
| --- | --- |
| Slow endpoint in one Kubernetes pod | endpoint diagnostics + latency analysis + runtime access + lifecycle + data safety |
| Repeated 5xx failures on a VM reached by SSH | failure analysis + runtime access + lifecycle + data safety |
| Quick check of a suspected hot method | oneliner triage + lifecycle + data safety |
| Persistent distributed diagnostics | extensions and permissions + startup and packaging + runtime access + data safety |

## Scope and safety

The plugin chooses scope to fit the incident: an exact method is usually appropriate in production;
a bounded package/controller match can be appropriate for discovery; broad matching is an explicit,
time-bounded escalation with an expected event rate and rollback plan. It defaults to method names,
durations, and exception types rather than request bodies, tokens, credentials, or customer data.

Every deployment plan includes a target identity, output destination, observation window, and stop
condition. The BTrace verifier remains enabled by default; unsafe/trusted mode is never assumed.

## MCP server

The host plugin integration installs the bundled stdio MCP server as `btrace`. It requires JBang
and JDK 11 or newer, and it downloads the single masked `io.btrace:btrace` distribution on first
use. The server must run on the host that can attach to the target JVM.

## JFR correlation

When the `jfr-analyzer` plugin is installed, use it for historical profile context and pair it with
the `jfr-btrace-interop` skill. JFR identifies the resource, interval, and candidate hotspot;
BTrace performs the narrow live confirmation. Share target identity, timestamps, hypothesis, and
evidence between the two workflows, and always stop the BTrace probe when the observation window
ends.
