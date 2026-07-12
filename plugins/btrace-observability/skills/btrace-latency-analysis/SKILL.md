---
name: btrace-latency-analysis
description: Use when a user wants to find why a Java method, endpoint, consumer, or dependency call is slow, has poor throughput, or shows latency spikes.
---

# Latency Analysis

Measure the named boundary first. Use `@Duration` at `Kind.RETURN`; then add only the dependency
boundaries needed to partition the observed time. For high-frequency paths, aggregate or sample
rather than print one line per invocation.

```java
import static io.btrace.core.BTraceUtils.*;
import io.btrace.core.annotations.*;

@BTrace
public class MethodLatency {
  @OnMethod(
      clazz = "com.example.orders.OrderService",
      method = "create",
      location = @Location(Kind.RETURN))
  public static void completed(@Duration long duration) {
    println("OrderService.create took " + duration / 1000000 + " ms");
  }
}
```

State the unit (`@Duration` is nanoseconds), observation window, traffic expectation, and what
constitutes a slow event. If the outer method is slow, instrument the likely repository/client
methods before widening to a package-level scan.
