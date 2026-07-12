import java.util.concurrent.*;
import java.util.Random;

public class GcQueueCoupling implements main.Scenario {
    @Override public String id() { return "gc_queue_coupling"; }

    @Override public void run(long durationMs) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        long deadline = System.currentTimeMillis() + durationMs;
        Random rng = new Random();

        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            final byte[] payload = new byte[4096 + rng.nextInt(8192)];
            payload[0] = (byte) rng.nextInt();
            pool.submit(() -> {
                long sum = payload[0];
                if (sum < Byte.MIN_VALUE) throw new AssertionError();
            });
            Thread.sleep(1);
        }
        pool.shutdown();
    }
}
