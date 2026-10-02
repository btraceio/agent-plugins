import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;

public class CfFanoutLargeHeap implements main.Scenario {
    private static final int FAN_WIDTH   = 40;
    private static final int CHUNK_BYTES = 50_000_000;

    @Override public String id() { return "cf_fanout_large_heap"; }

    @Override public void run(long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            List<CompletableFuture<byte[]>> futures = new ArrayList<>(FAN_WIDTH);
            for (int i = 0; i < FAN_WIDTH; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    byte[] chunk = new byte[CHUNK_BYTES];
                    chunk[0] = 1;
                    try { Thread.sleep(200); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return chunk;
                }));
            }
            List<byte[]> results = new ArrayList<>(FAN_WIDTH);
            for (var f : futures) {
                try { results.add(f.get(5, TimeUnit.SECONDS)); }
                catch (Exception ignored) {}
            }
            // results goes out of scope → GC triggered
        }
    }
}
