---
name: btrace-startup-and-packaging
description: Use when dynamic attach is unavailable or unsuitable, or when BTrace must be deployed at JVM startup in immutable images, distroless containers, Kubernetes, Spark, or Hadoop.
---

# Startup and Packaging

Choose launch-time deployment when the target cannot be dynamically attached to, lacks a shell/JDK,
or is an immutable/distroless production image. Start the JVM with a known probe:

```bash
java -javaagent:/path/to/btrace.jar=script=Probe.class -jar application.jar
```

For a self-contained deployment that needs BTrace plus extensions, build and review a fat agent JAR.
This is especially useful for Kubernetes, Spark, and Hadoop, but it changes the deployment surface:
test it in the same Java/runtime environment and roll it out like an application artifact.

Use the Kubernetes sidecar pattern for persistent diagnostic availability only when process namespace
sharing and platform security policy permit it. For distroless workloads, prefer the startup agent or
an intentionally designed sidecar rather than attempting to inject a shell at incident time.

For a sidecar, mount probes through a controlled volume/configuration source, use a fixed image
version, and send diagnostics to the platform logging pipeline. For a fat agent, verify the embedded
extensions and their permissions before deployment; embedding is part of the runtime trust boundary.
