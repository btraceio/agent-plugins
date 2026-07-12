package io.btrace.compiler.oneliner;

public final class OnelinerValidator {
  private OnelinerValidator() {}

  public static void validate(Object ast, String expression) {
    if (!ast.equals(expression)) {
      throw new IllegalArgumentException("invalid expression");
    }
  }
}
