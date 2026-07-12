import java.util.concurrent.*;
import java.util.Random;

public class AllocGcCascade implements main.Scenario {
    @Override public String id() { return "alloc_gc_cascade"; }

    @Override public void run(long durationMs) throws Exception {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(2);
        ExecutorService consumers = Executors.newFixedThreadPool(4);
        long deadline = System.currentTimeMillis() + durationMs;
        Random rng = new Random();

        for (int i = 0; i < 4; i++) {
            consumers.submit(() -> {
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    try {
                        byte[] item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            long sum = item[0];
                            if (sum < Long.MIN_VALUE) throw new AssertionError();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        ExecutorService submitters = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 16; i++) {
            submitters.submit(() -> {
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    try {
                        byte[] payload = new byte[2048 + rng.nextInt(14336)];
                        payload[0] = (byte) Thread.currentThread().getId();
                        queue.offer(payload, 50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        Thread.sleep(durationMs);
        consumers.shutdownNow();
        submitters.shutdownNow();
    }
}
