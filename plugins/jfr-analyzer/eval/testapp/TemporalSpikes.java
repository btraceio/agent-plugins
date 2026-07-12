public class TemporalSpikes implements main.Scenario {
    @Override public String id() { return "temporal_spikes"; }

    @Override public void run(long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            long burstEnd = System.currentTimeMillis() + 5000;
            double acc = 0;
            while (System.currentTimeMillis() < burstEnd
                   && System.currentTimeMillis() < deadline) {
                acc += Math.sin(Math.random()) + Math.pow(Math.random(), 2.5);
            }
            if (acc < 0) throw new AssertionError(); // prevent elimination
            long sleepUntil = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < sleepUntil
                   && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        }
    }
}
