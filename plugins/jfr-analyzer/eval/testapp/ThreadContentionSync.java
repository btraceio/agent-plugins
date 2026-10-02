import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ThreadContentionSync implements main.Scenario {
    private final AtomicLong counter = new AtomicLong();

    @Override public String id() { return "thread_contention_sync"; }

    private synchronized void contendedWork() throws InterruptedException {
        Thread.sleep(1);
        counter.incrementAndGet();
    }

    @Override public void run(long durationMs) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors() * 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.currentTimeMillis() + durationMs;
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    try {
                        contendedWork();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        Thread.sleep(durationMs);
        pool.shutdownNow();
    }
}
