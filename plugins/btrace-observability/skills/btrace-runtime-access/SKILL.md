---
name: btrace-runtime-access
description: Use when BTrace must reach a Java process through SSH, Docker, Kubernetes, a sidecar, a bastion, or another cloud-runtime boundary.
---

# Runtime Access

The BTrace Attach API is local to the target machine and normally requires the same operating-system
user. Bring the BTrace client into the target host/process namespace; do not assume that a local
client or local MCP server can attach across SSH, Docker, or Kubernetes boundaries.

## SSH / VM

Copy the probe and run BTrace on the target host under the application user:

```bash
scp EndpointLatency.java ops@host:/tmp/
ssh ops@host 'sudo -u appuser btrace -l'
ssh -tt ops@host 'sudo -u appuser btrace -v -o /tmp/endpoint-latency.log <PID> /tmp/EndpointLatency.java'
```

Confirm host, process owner, PID, Java version, and free disk space for logs before attachment.
Use a bastion/standard SSH configuration rather than opening an unauthenticated BTrace listener to
the network. Retrieve logs through the approved operational channel and remove temporary probe
files when finished.

## Docker

Run inside the application container/namespace:

```bash
docker exec <container> jps
docker cp EndpointLatency.java <container>:/tmp/EndpointLatency.java
docker exec -it <container> btrace <PID> /tmp/EndpointLatency.java
```

The container needs a JDK, BTrace, compatible permissions, and access to the target PID namespace.

## Kubernetes

Use an explicitly selected namespace, context, pod, and container. Verify the workload identity
before every `exec`:

```bash
kubectl -n <namespace> get pod -l app=<app>
kubectl -n <namespace> exec <pod> -c <app-container> -- jps
kubectl -n <namespace> cp EndpointLatency.java <pod>:/tmp/EndpointLatency.java -c <app-container>
kubectl -n <namespace> exec -it <pod> -c <app-container> -- btrace <PID> /tmp/EndpointLatency.java
```

For repeated access, use a BTrace sidecar only when the pod shares its process namespace
(`shareProcessNamespace: true`) and the security policy permits the required attach/ptrace actions.
For distroless images, attach-in-container is generally unavailable; plan a launch-time agent or a
purpose-built diagnostic/sidecar image instead. Do not fan out to every replica by default—start
with one representative pod, then use a bounded, labelled rollout if needed.

## Attach blockers

Investigate in this order: wrong PID/namespace, different OS user, missing JDK/Attach API,
`-XX:+DisableAttachMechanism`, container security policy (SELinux/AppArmor/Pod Security), and JDK
dynamic-agent-loading policy. If attach is unavailable and a restart is acceptable, use the
launch-time `-javaagent` path.
