package io.btrace.compiler.oneliner;

public final class OnelinerCodeGenerator {
  private OnelinerCodeGenerator() {}

  public static String generate(Object ast, String className) {
    return className + ':' + ast;
  }
}
