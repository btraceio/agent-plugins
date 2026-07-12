import java.util.concurrent.*;
import java.util.Random;

public class GcCpuInflation implements main.Scenario {
    @Override public String id() { return "gc_cpu_inflation"; }

    @Override public void run(long durationMs) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cpus);
        long deadline = System.currentTimeMillis() + durationMs;
        Random rng = new Random();

        for (int i = 0; i < cpus; i++) {
            pool.submit(() -> {
                long count = 0;
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    byte[] b = new byte[512 + rng.nextInt(512)];
                    b[0] = (byte) count;
                    count++;
                }
                return count;
            });
        }
        Thread.sleep(durationMs);
        pool.shutdownNow();
    }
}
