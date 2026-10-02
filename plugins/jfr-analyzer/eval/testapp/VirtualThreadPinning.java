import java.util.concurrent.*;

public class VirtualThreadPinning implements main.Scenario {
    private final Object lock = new Object();

    @Override public String id() { return "virtual_thread_pinning"; }

    @Override public void run(long durationMs) throws Exception {
        int jdkMajor;
        try {
            String version = System.getProperty("java.specification.version");
            jdkMajor = Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            jdkMajor = 8;
        }
        if (jdkMajor < 21) {
            System.out.println("[virtual_thread_pinning] JDK < 21 detected — skipping scenario");
            Thread.sleep(durationMs);
            return;
        }

        int concurrency = Runtime.getRuntime().availableProcessors() * 8;
        long deadline = System.currentTimeMillis() + durationMs;
        ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < concurrency; i++) {
            vte.submit(() -> {
                while (!Thread.currentThread().isInterrupted()
                       && System.currentTimeMillis() < deadline) {
                    synchronized (lock) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            });
        }
        Thread.sleep(durationMs);
        vte.shutdownNow();
    }
}
