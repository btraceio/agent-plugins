package io.btrace.core.comm;

public interface CommandListener {
  void onCommand(Command command) throws Exception;
}
