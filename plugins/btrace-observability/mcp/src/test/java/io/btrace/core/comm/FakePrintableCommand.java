package io.btrace.core.comm;

import java.io.PrintWriter;

public final class FakePrintableCommand extends Command implements PrintableCommand {
  private final String text;

  public FakePrintableCommand(int type, String text) {
    super(type);
    this.text = text;
  }

  @Override
  public void print(PrintWriter writer) {
    writer.print(text);
  }
}
