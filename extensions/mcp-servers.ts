// Pi loads MCP servers only from mcp.json or an extension, never from a package manifest, so this
// registers the servers the shared skills call. A server of the same name in mcp.json takes precedence.
import { fileURLToPath } from "node:url";

const BTRACE_SERVER = fileURLToPath(
  new URL("../plugins/btrace-observability/mcp/src/main/java/io/btrace/mcp/BTraceMcpServer.java", import.meta.url),
);

export default function (pi: {
  registerMcpServer(name: string, config: { command: string; args: string[]; description: string }): void;
}) {
  pi.registerMcpServer("jafar", {
    command: "jbang",
    args: ["jfr-mcp@btraceio", "--stdio", "--attach"],
    description: "Analyse JFR, pprof, OTLP and HPROF profiling data",
  });
  pi.registerMcpServer("btrace", {
    command: "jbang",
    args: [BTRACE_SERVER],
    description: "Attach BTrace probes to a running JVM",
  });
}
