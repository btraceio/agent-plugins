---
name: btrace-failure-analysis
description: Use when a Java endpoint, service, consumer, or background job fails, returns errors, throws exceptions, or needs an exception source investigation.
---

# Failure Analysis

Observe errors at the highest useful application boundary first, then move inward only if the
exception lacks actionable context. Do not use a JVM-wide error probe by default.

```java
import static io.btrace.core.BTraceUtils.*;
import io.btrace.core.annotations.*;

@BTrace
public class Failures {
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

Use `jstack()` only for a short, approved window on low/moderate traffic. It can add overhead and
expose implementation details. Correlate output with the application's normal logs and request ID
only when that identifier is safe to emit.
