import java.util.concurrent.*;
import java.util.Random;

public class GcPressureParallel implements main.Scenario {
    @Override public String id() { return "gc_pressure_parallel"; }

    @Override public void run(long durationMs) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cpus);
        long deadline = System.currentTimeMillis() + durationMs;
        Random rng = new Random();
        for (int i = 0; i < cpus; i++) {
            pool.submit(() -> {
                long sum = 0;
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    byte[] buf = new byte[1024 + rng.nextInt(9216)];
                    buf[0] = (byte) sum;
                    sum += buf.length;
                }
                return sum;
            });
        }
        Thread.sleep(durationMs);
        pool.shutdownNow();
    }
}
