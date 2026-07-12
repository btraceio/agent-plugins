# Security policy

Agent plugins are trusted extensions. They may execute scripts, influence agent behavior, invoke
MCP tools, or access systems already available to the host. Review every change as executable code.

Do not include credentials, customer data, production logs, or private probe output in issues or pull
requests. Report suspected vulnerabilities privately to the BTrace maintainers through the security
contact configured for the `btraceio` organization.

Supported versions are the default branch and the latest tagged release. Security fixes should be
kept small, tested by the repository validation gate, and documented in the changelog.
