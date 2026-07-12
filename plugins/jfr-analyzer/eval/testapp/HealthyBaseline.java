public class HealthyBaseline implements main.Scenario {
    @Override public String id() { return "healthy_baseline"; }

    @Override public void run(long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }
}
