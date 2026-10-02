import java.util.Random;

public class ZgcAllocationStalls implements main.Scenario {
    @Override public String id() { return "zgc_allocation_stalls"; }

    @Override public void run(long durationMs) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        long deadline = System.currentTimeMillis() + durationMs;
        Random rng = new Random();

        Thread[] threads = new Thread[cpus];
        for (int i = 0; i < cpus; i++) {
            threads[i] = Thread.ofPlatform().name("zgc-stall-" + i).start(() -> {
                long sum = 0;
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    byte[] b = new byte[rng.nextInt(100_000)];
                    b[0] = (byte) sum;
                    sum += b.length;
                }
                if (sum < 0) throw new AssertionError();
            });
        }
        Thread.sleep(durationMs);
        for (Thread t : threads) { t.interrupt(); t.join(1000); }
    }
}
