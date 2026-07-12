public class G1HumongousAlloc implements main.Scenario {
    private static final int OBJECT_SIZE = 600_000;

    @Override public String id() { return "g1_humongous_alloc"; }

    @Override public void run(long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        byte[] last = null;
        long iter = 0;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            last = new byte[OBJECT_SIZE];
            last[0] = (byte) iter;
            iter++;
            if (iter % 100 == 0) Thread.yield();
        }
        if (last == null) throw new AssertionError();
    }
}
