///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS io.projectreactor:reactor-core:3.7.0
// Scenario files live in the same directory. regen.sh passes them via -s= flags
// because jbang v0.101 does not resolve //SOURCE from subdirectories reliably.

import java.util.*;
import java.util.concurrent.*;

public class main {

    interface Scenario {
        String id();
        void run(long durationMs) throws Exception;
    }

    static final Map<String, Scenario> REGISTRY = new LinkedHashMap<>();

    static {
        register(new CpuHotspot());
        register(new GcPressureParallel());
        register(new AllocationPressure());
        register(new ThreadContentionSync());
        register(new Safepoints());
        register(new TemporalSpikes());
        register(new HealthyBaseline());
        register(new AllocGcCascade());
        register(new GcCpuInflation());
        register(new GcQueueCoupling());
        register(new ReactiveNoBackpressure());
        register(new ReactiveBackpressure());
        register(new CfFanoutLargeHeap());
        register(new G1HumongousAlloc());
        register(new ZgcAllocationStalls());
        register(new VirtualThreadPinning());
    }

    static void register(Scenario s) {
        REGISTRY.put(s.id(), s);
    }

    public static void main(String[] args) throws Exception {
        String scenariosProp = System.getProperty("scenarios", "");
        int durationSec = Integer.parseInt(System.getProperty("duration", "30"));
        long durationMs = durationSec * 1000L;

        if (scenariosProp.isBlank()) {
            System.err.println("Usage: jbang main.java -Dscenarios=<id1,id2,...> [-Dduration=30]");
            System.err.println("Available: " + String.join(", ", REGISTRY.keySet()));
            System.exit(1);
        }

        List<String> ids = Arrays.asList(scenariosProp.split(","));
        List<Scenario> toRun = new ArrayList<>();
        for (String id : ids) {
            String trimmed = id.trim();
            Scenario s = REGISTRY.get(trimmed);
            if (s == null) {
                System.err.println("Unknown scenario: " + trimmed);
                System.err.println("Available: " + String.join(", ", REGISTRY.keySet()));
                System.exit(1);
            }
            toRun.add(s);
        }

        System.out.println("Starting scenarios: " + ids + " for " + durationSec + "s");

        List<Thread> threads = new ArrayList<>();
        for (Scenario s : toRun) {
            Thread t = Thread.ofPlatform().name("scenario-" + s.id()).start(() -> {
                try {
                    s.run(durationMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // normal shutdown signal — not an error
                } catch (Exception e) {
                    System.err.println("Scenario " + s.id() + " failed: " + e.getMessage());
                }
            });
            threads.add(t);
        }

        Thread.sleep(durationMs);
        System.out.println("Duration elapsed — shutting down");

        for (Thread t : threads) {
            t.interrupt();
            t.join(2000);
        }

        System.out.println("Done");
        System.exit(0); // force JVM exit — scenario threads may be non-daemon and keep JVM alive
    }
}
