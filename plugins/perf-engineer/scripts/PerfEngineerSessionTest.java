///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//SOURCES PerfEngineerSession.java
//JAVA 17

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Regression tests for PerfEngineerSession using local fake processes only.
 *
 * <p>Run with {@code jbang PerfEngineerSessionTest.java}; exits non-zero if any test fails.
 */
public class PerfEngineerSessionTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Invokes the tool exactly as the supervisor does: the current class path plus the main class. */
    static JsonNode runTool(Path root, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            PerfEngineerSession.class.getName(),
            "--session-root", root.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(p.waitFor() == 0, "tool failed: " + String.join(" ", args));
        return JSON.readTree(out);
    }

    /** A fake asprof: writes the -f output file, then sleeps. Uses exec so SIGTERM ends it directly. */
    static Path fakeProfiler(Path dir, int seconds) throws IOException {
        Path path = dir.resolve("asprof");
        Files.writeString(path,
            "#!/bin/sh\n"
            + "while [ $# -gt 0 ]; do [ \"$1\" = -f ] && out=\"$2\"; shift; done\n"
            + "printf fake-jfr > \"$out\"\n"
            + "exec sleep " + seconds + "\n");
        check(path.toFile().setExecutable(true, true), "chmod fake profiler");
        return path;
    }

    static Process target() throws IOException {
        return new ProcessBuilder("sleep", "60").start();
    }

    static JsonNode waitForState(Path root, String sessionId, Set<String> states) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = runTool(root, "status", sessionId);
            if (states.contains(last.path("state").asText())) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("session did not reach " + states + ": " + last);
    }

    static JsonNode start(Path root, Path tmp, Process target, Path fake, int duration) throws Exception {
        return runTool(root, "start", String.valueOf(target.pid()),
            "--profiler", fake.toString(),
            "--duration", String.valueOf(duration),
            "--output", tmp.resolve("profile.jfr").toString(),
            "--allow-non-java");
    }

    interface Body {
        void run(Path tmp, Path root, Process target) throws Exception;
    }

    static void withTarget(Body body) throws Exception {
        Path tmp = Files.createTempDirectory("perf-engineer-test");
        Process target = target();
        try {
            body.run(tmp, tmp.resolve("sessions"), target);
        } finally {
            target.destroy();
            target.waitFor();
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    static void testBoundedCompletionAndCleanup() throws Exception {
        withTarget((tmp, root, target) -> {
            String id = start(root, tmp, target, fakeProfiler(tmp, 0), 2).get("session_id").asText();
            JsonNode finished = waitForState(root, id, Set.of("completed"));
            check(finished.get("target_matches").asBoolean(), "target should still match");
            check(runTool(root, "cleanup", id).get("cleaned").asBoolean(), "cleanup should report cleaned");
            check(!Files.exists(tmp.resolve("profile.jfr")), "cleanup should remove the artifact");
        });
    }

    static void testExplicitStop() throws Exception {
        withTarget((tmp, root, target) -> {
            String id = start(root, tmp, target, fakeProfiler(tmp, 60), 30).get("session_id").asText();
            waitForState(root, id, Set.of("running"));
            runTool(root, "stop", id);
            check("stopped".equals(waitForState(root, id, Set.of("stopped")).get("state").asText()), "state stopped");
        });
    }

    static void testTargetDisappearanceStopsSession() throws Exception {
        withTarget((tmp, root, target) -> {
            String id = start(root, tmp, target, fakeProfiler(tmp, 60), 30).get("session_id").asText();
            waitForState(root, id, Set.of("running"));
            target.destroy();
            target.waitFor();
            check("target-exited".equals(waitForState(root, id, Set.of("target-exited")).get("state").asText()), "state target-exited");
        });
    }

    static void testHardTimeoutMarksExpired() throws Exception {
        withTarget((tmp, root, target) -> {
            String id = start(root, tmp, target, fakeProfiler(tmp, 60), 1).get("session_id").asText();
            check("expired".equals(waitForState(root, id, Set.of("expired")).get("state").asText()), "state expired");
        });
    }

    interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        List<String> names = List.of(
            "bounded completion and cleanup", "explicit stop", "target disappearance stops session", "hard timeout marks expired");
        List<Test> tests = List.of(
            PerfEngineerSessionTest::testBoundedCompletionAndCleanup,
            PerfEngineerSessionTest::testExplicitStop,
            PerfEngineerSessionTest::testTargetDisappearanceStopsSession,
            PerfEngineerSessionTest::testHardTimeoutMarksExpired);
        int failed = 0;
        for (int i = 0; i < tests.size(); i++) {
            try {
                tests.get(i).run();
                System.out.println("PASS " + names.get(i));
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL " + names.get(i) + ": " + t);
            }
        }
        System.out.println((tests.size() - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
