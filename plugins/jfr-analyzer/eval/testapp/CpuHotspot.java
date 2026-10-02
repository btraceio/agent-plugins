import java.util.concurrent.*;

public class CpuHotspot implements main.Scenario {
    @Override public String id() { return "cpu_hotspot"; }

    @Override public void run(long durationMs) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cpus);
        long deadline = System.currentTimeMillis() + durationMs;
        for (int i = 0; i < cpus; i++) {
            pool.submit(() -> {
                double acc = 0;
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    acc += Math.sin(Math.random()) + Math.pow(Math.random(), 3.7);
                }
                return acc;
            });
        }
        Thread.sleep(durationMs);
        pool.shutdownNow();
    }
}
