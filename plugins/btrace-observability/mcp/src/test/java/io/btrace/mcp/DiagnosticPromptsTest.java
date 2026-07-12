package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.mcp.prompts.DiagnosticPrompts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagnosticPromptsTest {
  @Test
  void listsAllPromptSchemas() {
    List<Map<String, Object>> prompts = DiagnosticPrompts.listPrompts();

    assertEquals(3, prompts.size());
    assertEquals("diagnose_slow_endpoint", prompts.get(0).get("name"));
    assertEquals("find_exception_source", prompts.get(1).get("name"));
    assertEquals("profile_method", prompts.get(2).get("name"));
  }

  @Test
  void rendersEveryPromptWithArguments() {
    assertPrompt(
        "diagnose_slow_endpoint",
        Map.of("endpoint_class", "orders.Api", "endpoint_method", "create", "pid", "42"),
        "orders.Api::create",
        "Target PID: 42");
    assertPrompt(
        "find_exception_source",
        Map.of("exception_class", "orders.FailedOrder", "pid", "42"),
        "orders.FailedOrder",
        "Target PID: 42");
    assertPrompt(
        "profile_method",
        Map.of("class_name", "orders.Api", "method_name", "create", "pid", "42"),
        "orders.Api::create",
        "send_event");
  }

  @Test
  void usesDefaultsAndRejectsUnknownPrompts() {
    Map<String, Object> result = DiagnosticPrompts.getPrompt("profile_method", null);

    assertNotNull(result);
    assertTrue(messages(result).contains("com.example.Service::process"));
    assertNull(DiagnosticPrompts.getPrompt("unknown", Map.of()));
  }

  private static void assertPrompt(
      String name, Map<String, Object> arguments, String... expectedFragments) {
    String text = messages(DiagnosticPrompts.getPrompt(name, arguments));
    for (String fragment : expectedFragments) {
      assertTrue(text.contains(fragment), () -> "Missing " + fragment + " in " + text);
    }
  }

  @SuppressWarnings("unchecked")
  private static String messages(Map<String, Object> result) {
    List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
    Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
    return (String) content.get("text");
  }
}
