---
name: btrace-endpoint-diagnostics
description: Use when a user asks what happens when an HTTP endpoint, RPC operation, message handler, or request path is invoked in a live Java application.
---

# Endpoint Diagnostics

Turn a route-level question into a handler-to-dependency trace plan.

1. Identify the concrete handler from source, route mappings, framework diagnostics, or class names.
   Do not guess the method name from the URL when the mapping is available.
2. Choose a boundary: controller/handler for entry, service for business flow, repository/client for
   dependencies. Trace one boundary first, then expand only where the evidence points.
3. If handler discovery is impossible, use a bounded controller/package regex with a time limit and
   a clear expected request rate; replace it with exact matching once the right handler is found.
4. Capture only safe correlation data. Method name and duration are safe defaults; redact IDs and do
   not print authorization headers, cookies, request bodies, or tokens.

Use `@ProbeClassName` and `@ProbeMethodName` to identify the matched handler. Use a `Kind.CALL`
location to observe a specific downstream call from that handler, rather than matching every JVM
method globally.

Example entry probe:

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

Pair this skill with `btrace-latency-analysis` for slow endpoints and
`btrace-failure-analysis` for 5xx/exception investigations.
