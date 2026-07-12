package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
  @SuppressWarnings("unchecked")
  void parsesJsonValuesAndEscapesOutput() {
    Map<String, Object> parsed =
        McpProtocol.parseJson("{\"text\":\"a\\n\\u263a\",\"number\":1.5,\"flag\":true,\"none\":null,\"items\":[1,false]}");

    assertEquals("a\n☺", parsed.get("text"));
    assertEquals(1.5d, parsed.get("number"));
    assertEquals(Boolean.TRUE, parsed.get("flag"));
    assertNull(parsed.get("none"));
    assertEquals(List.of(1, false), parsed.get("items"));
    Map<String, Object> serializable = new LinkedHashMap<>();
    serializable.put("value", "line\n\u0001");
    serializable.put("items", List.of("x", 2));
    assertEquals(
        "{\"value\":\"line\\n\\u0001\",\"items\":[\"x\",2]}", McpProtocol.toJson(serializable));
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
