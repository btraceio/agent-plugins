package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpProtocolTest {
  @Test
  void skipsBlankLinesAndReadsJson() throws Exception {
    McpProtocol protocol =
        new McpProtocol(
            new ByteArrayInputStream("\n  \n{\"id\":7,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());

    Map<String, Object> message = protocol.readMessage();

    assertEquals(7, message.get("id"));
    assertEquals("tools/list", message.get("method"));
    assertNull(protocol.readMessage());
  }

  @Test
  void rejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> McpProtocol.parseJson("{bad}"));
  }

  @Test
  void writesJsonRpcResult() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    McpProtocol protocol = new McpProtocol(new ByteArrayInputStream(new byte[0]), output);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("value", "quoted \"text\"");

    protocol.sendResult(3, result);

    assertEquals(
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"value\":\"quoted \\\"text\\\"\"}}\n",
        output.toString(StandardCharsets.UTF_8));
  }
}
