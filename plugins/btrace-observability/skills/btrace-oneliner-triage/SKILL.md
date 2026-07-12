---
name: btrace-oneliner-triage
description: Use when a user needs a fast, disposable BTrace observation for a live JVM and a full Java probe would be unnecessary or premature.
---

# Oneliner Triage

Use the BTrace oneliner DSL for a short, ad-hoc observation; move to a Java script when the probe
needs state, multiple probe points, reusable logic, or version control.

The shape is:

```text
class-pattern::method-pattern @location [filter] { action [, action]* }
```

Useful starting points:

```bash
# Find slow calls without logging fast traffic
btrace -n 'com.example.orders.OrderService::create @return if duration>100ms { print method, duration }' <PID>

# Confirm a suspected exception path
btrace -n 'com.example.orders.OrderService::create @error { print method, stack }' <PID>

# Count a high-frequency call instead of printing each invocation
btrace -n 'com.example.orders.OrderRepository::find* @entry { count }' <PID>
```

`duration` is only available at `@return` and `@error`, and is measured in nanoseconds. Wildcards
and regex can be valuable for discovery, but add a duration/argument filter or short observation
window before using them on a busy service. Treat `args`, `return`, and `stack` as sensitive output.

Supported locations are `@entry`, `@return`, and `@error`. Common actions are `print`, `count`,
`time`, and `stack`. Use a full Java probe once the investigation needs a timer, aggregation,
multiple locations, or maintained/reviewable diagnostic code.
