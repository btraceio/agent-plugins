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
