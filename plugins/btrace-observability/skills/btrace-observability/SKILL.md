---
name: btrace-observability
description: Use when a user wants to understand live Java application behavior—especially endpoint handling, latency, failures, method calls, arguments, return values, or SQL—using BTrace. Start from the user's goal and produce the smallest safe probe and deployment plan.
---

# BTrace Observability

Help users answer a runtime question without requiring BTrace expertise. Translate their wording
into a focused BTrace probe, explain only the minimum needed, and keep production impact low.

## Start with the outcome

Classify the request into one of these goals:

1. **What happens for this endpoint?** Find the concrete Java handler/controller or service method,
   then trace entry, selected arguments, downstream calls, and return/error outcome.
2. **Why is it slow?** Measure a precise method's duration first; add downstream call probes only if
   the first measurement leaves the bottleneck unclear.
3. **Why did it fail?** Observe exceptions at the smallest relevant handler/service boundary.
4. **What data reaches this method?** Capture only the specific argument, return value, or field
   needed to answer the question. Treat values as potentially sensitive.
5. **Is this query or dependency call occurring?** Probe the exact JDBC/client call site, avoid
   broad JVM-wide matching, and redact secrets from output.

Ask only for missing facts that materially affect the probe: target PID or deployment mode, the
endpoint/class/method if known, expected symptom, and whether request data may be printed. If the
application source is available, identify the request handler before proposing a broad probe.

## Safety-first probe design

- Start with one exact class and method. Do not begin with `/.*/` or an entire framework package in
  a busy production JVM.
- Prefer entry/return timing and small counters over logging every invocation.
- Avoid printing credentials, tokens, session IDs, full request bodies, or personal data. Redact or
  omit values by default.
- Keep each diagnostic probe temporary. State the stop/removal plan along with the attach command.
- BTrace scripts run untrusted by default. Do not suggest `-u`, trusted mode, or broad permissions
  unless the requested probe demonstrably needs them and the user confirms a controlled environment.
- Attach requires the target JVM to permit attach and normally to run as the same operating-system
  user. A JVM started with `-XX:+DisableAttachMechanism` cannot be attached to later.

## Probe recipes

Use `io.btrace.core` imports and a `@BTrace` class. Replace the placeholder class and method with
the application handler identified during discovery.

### Endpoint flow: smallest useful first probe

```java
import static io.btrace.core.BTraceUtils.*;
import io.btrace.core.annotations.*;

@BTrace
public class EndpointFlow {
  @OnMethod(clazz = "com.example.orders.OrderController", method = "createOrder")
  public static void entered(@ProbeClassName String clazz, @ProbeMethodName String method) {
    println("entered " + clazz + "." + method);
  }
}
```

If this confirms the handler is reached, add one next probe at the service method or a `Kind.CALL`
location—never both broad matching and argument capture at once.

### Latency and result

```java
import static io.btrace.core.BTraceUtils.*;
import io.btrace.core.annotations.*;
import io.btrace.core.types.AnyType;

@BTrace
public class EndpointLatency {
  @OnMethod(
      clazz = "com.example.orders.OrderService",
      method = "create",
      location = @Location(Kind.RETURN))
  public static void completed(@Duration long duration, @Return AnyType result) {
    println("OrderService.create took " + duration / 1000000 + " ms; result=" + str(result));
  }
}
```

Omit `@Return` if the return value may contain sensitive or high-volume data. If requests are very
frequent, aggregate with a timer/metrics extension instead of logging every call.

### Failure boundary

```java
import static io.btrace.core.BTraceUtils.*;
import io.btrace.core.annotations.*;

@BTrace
public class EndpointFailures {
  @OnMethod(
      clazz = "com.example.orders.OrderService",
      method = "create",
      location = @Location(Kind.ERROR))
  public static void failed(
      @ProbeClassName String clazz, @ProbeMethodName String method, Throwable error) {
    println("failure in " + clazz + "." + method + ": " + str(error));
  }
}
```

Add `jstack()` only for a short, low-traffic troubleshooting window; stack traces are expensive and
can expose application details.

## Discover, deploy, verify, remove

1. **Locate the JVM:** `btrace -l`, then confirm the PID belongs to the intended application.
2. **Create the probe:** save it as a clearly named `.java` file, such as `EndpointLatency.java`.
3. **Attach with captured output:** `btrace -v -o endpoint-latency.log <PID> EndpointLatency.java`.
   For a zero-install local client, `jbang btrace@btraceio <PID> EndpointLatency.java` is suitable.
4. **Exercise the endpoint** once or with controlled traffic. Observe whether the output answers the
   stated question; widen the instrumentation only one step at a time.
5. **Verify active probes:** `btrace -lp <PID>`. Stop the client/probe once done and retain the log
   only under the application's data-handling policy.

For launch-time observation when attach is unavailable, start the JVM with
`-javaagent:/path/to/btrace.jar=script=Probe.class`; choose this only when the application can be
restarted and the probe is known before launch.

## Response format

For each request, deliver:

1. A one-sentence interpretation of the runtime question.
2. The smallest probe that can answer it, with placeholders filled from the user's context.
3. One attach/deploy command and one verification command.
4. A short safety note identifying what the probe intentionally does not capture.
5. The next narrower follow-up probe only if the first result is inconclusive.
