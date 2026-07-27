---
name: btrace-legacy-libs-migration
description: Use when custom classes stopped resolving after upgrading BTrace, when a target passes libs=<profile> or ships a btrace-libs directory, or when an integration relies on btrace.system.appendJar and must become a proper extension.
---

# Legacy libs Migration

The `libs=<profile>` mechanism is gone. A target that still passes it logs an error naming the
profile, starts normally, and loads none of those jars, so the symptom usually appears later as a
probe failing on a type it used to see. Treat "custom classes disappeared after the upgrade" as
this, not as a probe defect.

`btrace.system.appendJar` remains as a stopgap. It is trusted-only, takes one jar, and `trusted`
is an agent argument rather than a system property. Use it to restore a broken deployment, not as
a destination.

Inventory before changing anything: which jars the profile carried, which of their types the
probes actually name, and which of those are application types rather than the integration's own.
Only the types probes name need to survive into the new API; everything else is implementation.

Build one extension project with a single `src/main/java` and the `io.btrace.extension` plugin.
Declare the probe-facing service interfaces in `btraceExtension.services`; the plugin partitions
that one source set into API and implementation artifacts, so do not create separate modules.
Declare application dependencies as implementation-side compile-only so they stay off the API
artifact, and keep application types out of the interfaces — pass objects across and resolve them
in the implementation through the context class loader.

Place each implementation in the same package as the interface it implements, named
`<Interface>Impl`, or ship a provider file for it. Moving implementations into a tidy `impl`
subpackage without a provider file is the most common migration failure: the extension builds,
installs, and loads, then injection fails with `No implementation available for service (interface
returned)`.

Ignore `META-INF/services` entries that appear in the built implementation artifact for shaded
dependencies such as SLF4J or the annotation processor. Every extension has them and they are not
the extension's own wiring; the declared services in the API manifest are what the runtime reads.

Let the plugin scan dependencies and write the merged permission set into the API manifest rather
than listing permissions by hand, then read what it produced. One transitive dependency can make
the whole extension privileged, and a privileged extension is blocked outright unless an operator
grants it. Grants belong in `permissions.properties`; `extensions.conf` only enables and disables.

Recompile probes against the new API artifact after rewriting them onto the service interface.

Verify by inspecting the packaged artifact, installing it under `$BTRACE_HOME/extensions/`,
attaching, and taking one real trace that exercises the injected service. Injection throws by
default, so a link failure is visible; marking an injection optional or selecting shim mode turns
it into a no-op returning defaults and makes an unfinished migration look complete. When a link
fails, `btrace -le <PID>` reports why.

For deployments that cannot manage separate extension artifacts, load `btrace-startup-and-packaging`
for fat agents. For choosing permissions and deciding whether an extension is warranted at all,
load `btrace-extensions-and-permissions`.
