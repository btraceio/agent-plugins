package sample.orders;

/**
 * Target JVM for the MCP end-to-end tests: calls {@link #process(int)} in a loop, with every fifth
 * call slow and every seventh call throwing, so latency and failure probes have something to see.
 * It lives outside {@code io.btrace}, whose classes BTrace refuses to instrument.
 */
public final class SampleOrderApp {
  public static final String READY = "SAMPLE_ORDER_APP_READY";

  private SampleOrderApp() {}

  static int process(int id) throws InterruptedException {
    Thread.sleep(id % 5 == 0 ? 60 : 5);
    if (id % 7 == 0) {
      throw new IllegalStateException("order " + id + " rejected");
    }
    return id * 2;
  }

  public static void main(String[] args) throws Exception {
    System.out.println(READY);
    System.out.flush();
    for (int id = 1; ; id++) {
      try {
        process(id);
      } catch (IllegalStateException expected) {
        // failures are part of the workload
      }
    }
  }
}
