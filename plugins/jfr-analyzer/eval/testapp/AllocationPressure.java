public class AllocationPressure implements main.Scenario {
    private static final int BUF_COUNT = 256;
    private static final int BUF_SIZE  = 100_000;

    @Override public String id() { return "allocation_pressure"; }

    @Override public void run(long durationMs) throws Exception {
        byte[][] ring = new byte[BUF_COUNT][];
        long deadline = System.currentTimeMillis() + durationMs;
        int idx = 0;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            ring[idx % BUF_COUNT] = new byte[BUF_SIZE];
            ring[idx % BUF_COUNT][0] = (byte) idx;
            idx++;
            if (idx % 100 == 0) Thread.sleep(1);
        }
    }
}
