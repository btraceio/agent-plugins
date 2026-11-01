package io.btrace.client;

import io.btrace.core.comm.Command;
import io.btrace.core.comm.CommandListener;
import io.btrace.core.comm.FakePrintableCommand;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Client {
  public static Client last;
  /** Lines every later probe prints after it reports its start, as the agent would. */
  public static final List<String> laterOutput = new ArrayList<>();
  /** What a probe prints while it shuts down after an exit request. */
  public static String exitOutput = "";
  public static boolean failAttach = false;
  public final int port;
  public String attachedPid;
  public int exitCode;
  public String event;
  public boolean disconnected;
  public boolean closed;
  public String submittedFileName;
  public String exitedProbe;
  private final CountDownLatch closeLatch = new CountDownLatch(1);
  private CommandListener listener;

  public Client(int port) {
    this.port = port;
    last = this;
  }

  public byte[] compileSource(String fileName, String source, String classPath, PrintWriter errors) {
    return (fileName + ':' + source + ':' + classPath).getBytes();
  }

  public void attach(String pid, String systemClassPath, String bootstrapClassPath)
      throws java.io.IOException {
    if (failAttach) {
      throw new java.io.IOException("attach failed");
    }
    if (pid.equals("port-busy")) {
      throw new java.io.IOException("Port " + port + " unavailable.");
    }
    if (pid.equals("no-message")) {
      throw new java.io.IOException();
    }
    attachedPid = pid;
  }

  public void submit(
      String host, String fileName, byte[] code, String[] args, CommandListener listener) throws Exception {
    submittedFileName = fileName;
    this.listener = listener;
    listener.onCommand(new FakePrintableCommand(Command.STATUS, host + ':' + fileName));
    for (String line : laterOutput) {
      listener.onCommand(new FakePrintableCommand(Command.MESSAGE, line));
    }
    // Like the real client, keep running the probe command loop until the client is closed.
    closeLatch.await();
  }

  public void connectAndListProbes(String host, CommandListener listener) throws Exception {
    listener.onCommand(new FakePrintableCommand(Command.LIST_PROBES, host));
  }

  public void connectAndExitProbe(String host, String probeId) throws Exception {
    exitedProbe = host + ':' + probeId;
    if (probeId.equals("hang")) {
      // An agent that never confirms the exit: only closing the client ends the wait.
      closeLatch.await();
    }
  }

  public void sendExit(int code) throws Exception {
    exitCode = code;
    if (listener != null) {
      if (!exitOutput.isEmpty()) {
        listener.onCommand(new FakePrintableCommand(Command.MESSAGE, exitOutput));
      }
      listener.onCommand(new Command(Command.EXIT));
    }
  }

  public void sendDisconnect() {
    disconnected = true;
  }

  public void sendEvent() {
    event = "";
  }

  public void sendEvent(String name) {
    event = name;
  }

  public void close() {
    closed = true;
    closeLatch.countDown();
  }
}
