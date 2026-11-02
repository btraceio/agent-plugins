package io.btrace.core.comm;

public class ExitCommand extends Command {
  private final int exitCode;

  public ExitCommand(int exitCode) {
    super(EXIT);
    this.exitCode = exitCode;
  }

  public int getExitCode() {
    return exitCode;
  }
}
