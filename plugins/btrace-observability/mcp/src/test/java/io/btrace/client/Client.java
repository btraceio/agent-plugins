package io.btrace.client;

import io.btrace.core.comm.Command;
import io.btrace.core.comm.ExitCommand;
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
  public static boolean ignoreExit = false;
  /** An agent that never answers a probe list request: only closing the client ends the wait. */
  public static boolean hangListProbes = false;
  /** Runs while a probe list request is in flight, to model events that race with it. */
  public static Runnable duringListProbes = null;
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
  private volatile boolean exited;

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
    if (pid.equals("port-in-use")) {
      // The wording of the current BTrace client
      throw new java.io.IOException(
          "Port "
              + port
              + " is in use by another process (often another BTrace agent); choose a free"
              + " agent port with -p <port>.");
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
    // Like the real client, report a closed connection as an exit with code -1.
    if (!exited) {
      exited = true;
      listener.onCommand(new ExitCommand(-1));
    }
  }

  public void connectAndListProbes(String host, CommandListener listener) throws Exception {
    if (hangListProbes) {
      closeLatch.await();
      return;
    }
    if (duringListProbes != null) {
      duringListProbes.run();
    }
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
      if (ignoreExit) {
        return;
      }
      exited = true;
      listener.onCommand(new ExitCommand(code));
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
