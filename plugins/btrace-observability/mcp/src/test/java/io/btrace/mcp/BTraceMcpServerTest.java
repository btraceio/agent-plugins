package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BTraceMcpServerTest {
  @Test
  void servesInitializeAndCompactToolSchemas() throws Exception {
    String input =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new BTraceMcpServer(
            new McpProtocol(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output))
        .run();

    String[] responses = output.toString(StandardCharsets.UTF_8).split("\\n");
    assertEquals(2, responses.length);
    assertTrue(responses[0].contains("\"protocolVersion\":\"2024-11-05\""));
    assertTrue(responses[1].contains("\"name\":\"deploy_oneliner\""));
    assertTrue(responses[1].contains("\"description\":\"Deploy an oneliner probe.\""));
    assertTrue(responses[1].contains("\"description\":\"List local attachable JVMs.\""));
    assertTrue(responses[1].contains("\"description\":\"Stop and remove an active probe.\""));
  }

  @Test
  void returnsAParseErrorAndContinues() throws Exception {
    String input =
        "{bad}\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\"}\n";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new BTraceMcpServer(
            new McpProtocol(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output))
        .run();

    String response = output.toString(StandardCharsets.UTF_8);
    assertTrue(response.contains("\"code\":-32700"));
    assertTrue(response.contains("\"prompts\":"));
  }

  @Test
  void reportsUnknownMethodsAndMissingToolParameters() throws Exception {
    String input =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"missing\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}\n";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new BTraceMcpServer(
            new McpProtocol(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output))
        .run();

    String response = output.toString(StandardCharsets.UTF_8);
    assertTrue(response.contains("Method not found: missing"));
    assertTrue(response.contains("Missing params"));
    assertTrue(response.contains("Unknown tool: missing"));
  }
}
