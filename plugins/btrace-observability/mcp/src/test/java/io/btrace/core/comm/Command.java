package io.btrace.core.comm;

public class Command {
  public static final int STATUS = 1;
  public static final int EXIT = 2;
  public static final int LIST_PROBES = 3;

  private final int type;

  public Command(int type) {
    this.type = type;
  }

  public int getType() {
    return type;
  }
}
