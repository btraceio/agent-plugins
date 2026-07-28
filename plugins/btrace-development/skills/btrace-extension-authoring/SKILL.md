---
name: btrace-extension-authoring
description: Use when designing a new BTrace extension for a target library, framework, or runtime, deciding how a probe should reach target types safely, or choosing between @ExternalType and hand-written method handles.
---

# Extension Authoring

Start in analysis only. Establish which target entry points are public and stable, which versions
must be supported, and which types a probe actually needs, then say what the extension would look
like before creating any files. Report use of internal or version-sensitive target APIs as a risk
at this point rather than after scaffolding.

Derive the service surface from the probe-facing use cases, because everything downstream follows
from it. Service methods take and return only primitives, `java.*` types, and the extension's own
types; a target object crosses the boundary as `Object` and is resolved inside the implementation.
Keeping target types out of the service signature is what lets the extension load when the target
library is absent or a different version than expected.

Model an individual target type with `@ExternalType`, whose processor emits a companion adapter
with cached method handles. It fits a narrow shape: the whole signature must be nameable at
compile time, so any method that takes or returns a target-library type is out of reach; overloaded
names are rejected outright; fields, constructors, `instanceof`, and non-public members are not
supported; and members of packages a named module does not export are inaccessible. Check a target
method against that list before assuming the annotation can express it.

Note also that static dispatch resolves the owning class through the thread context class loader
while virtual dispatch uses the receiver's defining loader. Prefer virtual dispatch on a handed-off
receiver where a choice exists, because a context loader is whatever the application thread happens
to have set, and is frequently wrong under application servers, OSGi, and plugin loaders. Class
resolution is not cached, so a missing target class throws on every call rather than once, and it
surfaces at the probe call site rather than at load.

Use `ClassLoadingUtil` and `MethodHandleCache` for everything the annotation cannot express, and
for anything that varies across target versions. That cache stores successful lookups and never
stores failures, so catching a lookup failure is a genuine capability check that degrades to a
reduced feature set and recovers if the class appears later. `@ExternalType` offers no such catch
point, so version-variant members belong on the hand-written path.

Nothing in the metadata describes which target versions an extension supports; that is author
discipline. Because binding is per method and per class, a member missing from one version fails
only where it is used. Hand-written stubs of the target API under the test source set give unit
tests realistic fixtures and let a version matrix be exercised without the real dependency.

Name the implementation exactly `<serviceInterfaceFqcn>Impl`, or declare it in
`META-INF/services/<serviceInterfaceFqcn>`. Separating `api` and `impl` packages is reasonable and
requires the provider declaration, because the fallback is an exact name and nothing else. Extend
`Extension` when the service needs the extension context, initialization, or cleanup on detach; a
stateless service is better without it, since instantiation fails when no context is available.

Verify by inspecting the packaged artifacts, installing, attaching, and taking one trace that
exercises the injected service against the real target library rather than a stub.

Packaging mechanics, permission scanning, and the shared project layout are covered by
`btrace-extensions-and-permissions`. Fat agents for deployment are covered by the
`btrace-observability` plugin's startup and packaging guidance.
