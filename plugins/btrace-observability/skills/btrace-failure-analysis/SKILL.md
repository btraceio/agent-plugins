---
name: btrace-failure-analysis
description: Use when a Java endpoint, service, consumer, or background job fails, returns errors, throws exceptions, or needs an exception source investigation.
---

# Failure Analysis

Observe errors at the highest useful application boundary first, then move inward only if the
exception lacks actionable context. Do not use a JVM-wide error probe by default.

Deploy the Java probe below, not a oneliner. A oneliner cannot print the exception type, and its
`stack` action leaves out the method that failed.

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
      @ProbeClassName String clazz,
      @ProbeMethodName String method,
      @TargetInstance Throwable error) {
    println("failure in " + clazz + "." + method + ": " + name(classOf(error)));
  }
}
```

This prints the exception type only. An exception message can hold customer data, so use `str(error)`
only when the operator has approved that exposure under `btrace-data-safety`.

At `Kind.ERROR` the exception goes to the parameter annotated `@TargetInstance`; from BTrace 3.0 a
plain `Throwable` parameter works too when none is annotated. At `Kind.CATCH` and `Kind.THROW` the
exception parameter must be annotated `@TargetInstance`: there, an unannotated parameter is matched
against the method's own arguments and a handler that does not match them is silently not applied.

Use `jstack()` only for a short, approved window on low/moderate traffic. It can add overhead and
expose implementation details. `jstack()` leaves out the probed method and starts at its caller, so
do not read its top frame as the failing method. `jstack(error)` shows the real throw site but also
prints the exception message, so it needs the same approval as `str(error)`. Correlate output with the application's normal logs and request ID
only when that identifier is safe to emit.
