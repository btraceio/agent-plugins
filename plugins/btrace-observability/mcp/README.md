# BTrace MCP server

This standalone MCP server is part of the BTrace Agent Plugins marketplace. It loads BTrace's
client classes through the public bootstrap loader of the single masked `io.btrace:btrace` JAR;
the client implementation remains masked and is never added to the MCP server's compile classpath.

Run it with JBang:

```sh
jbang src/main/java/io/btrace/mcp/BTraceMcpServer.java
```

The server communicates over stdin/stdout. Use JDK 11 or newer, run it on the host that can attach
to the target JVM, and keep stderr separate from the MCP transport.

Run its unit tests with:

```sh
./gradlew test
```

`./gradlew check` enforces at least 80% line coverage for the MCP Java code.

Run the end-to-end tests with:

```sh
./gradlew e2eTest
```

They exercise every tool and prompt against a sample JVM, observing probe effects through `@Export`
counters read with `jcmd`. No LLM is involved. By default they launch the server through JBang, as
the plugin manifests do, which needs `jbang` on `PATH` and a resolvable `io.btrace:btrace` artifact,
so they are not part of `check`. To test against a BTrace build instead, such as in CI, point them
at its masked JAR:

```sh
BTRACE_JAR=/path/to/btrace/btrace-dist/build/resources/main/v<version>/libs/btrace.jar ./gradlew e2eTest
```
