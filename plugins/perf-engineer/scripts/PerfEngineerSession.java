///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//JAVA 17
//COMPILE_OPTIONS -XDignore.symbol.file

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Supervise one bounded async-profiler session.
 *
 * <p>This intentionally does not run an arbitrary shell command. It resolves an async-profiler CLI,
 * records a target fingerprint, enforces a deadline independently of the chat client, and persists
 * session state for status/stop/cleanup operations.
 *
 * <p>Run with {@code jbang PerfEngineerSession.java [--session-root DIR] start|status|stop|cleanup ...}.
 * The detached supervisor re-launches this class from the current JVM's class path, so the tool must
 * be run through JBang (or any launcher that produces a class path), not {@code java File.java}.
 */
public class PerfEngineerSession {

    static final int DEFAULT_DURATION = 30;
    static final int MAX_DURATION = 600;
    static final long ARTIFACT_LIMIT = 512L * 1024 * 1024;
    static final Set<String> PROFILER_NAMES = Set.of("asprof", "profiler.sh");
    static final String[] FINGERPRINT_KEYS = {
        "pid", "host", "jvm_start_time", "command_line_digest", "executable_or_container_identity"
    };

    static final ObjectMapper JSON = new ObjectMapper();
    static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Equivalent of the original RuntimeError: an expected, user-reportable failure. */
    static class ToolException extends RuntimeException {
        ToolException(String message) {
            super(message);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    static void restrict(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    static void writePrivate(Path path, String content) throws IOException {
        Files.writeString(path, content);
        restrict(path);
    }

    static void mkdirPrivate(Path dir) throws IOException {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    static void atomicJson(Path path, Map<String, Object> value) throws IOException {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path tmp = path.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".tmp");
        writePrivate(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static LinkedHashMap<String, Object> readJson(Path path) throws IOException {
        return JSON.readValue(Files.readString(path), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return Optional.ofNullable(System.getenv("HOSTNAME")).orElse("unknown");
        }
    }

    static Path expandUser(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path);
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Runs {@code ps -p pid -o field=}; empty when ps fails or prints nothing. */
    static Optional<String> ps(long pid, String field) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ps", "-p", String.valueOf(pid), "-o", field + "=")
            .redirectErrorStream(false)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.waitFor() != 0 || out.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(out);
    }

    static Map<String, Object> processSnapshot(long pid, boolean requireJava) {
        String start;
        String command;
        try {
            start = ps(pid, "lstart").orElseThrow(() -> new ToolException("target PID " + pid + " is not running"));
            command = ps(pid, "command").orElseThrow(() -> new ToolException("target PID " + pid + " is not running"));
        } catch (IOException e) {
            throw new ToolException("cannot inspect PID " + pid + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while inspecting PID " + pid);
        }
        if (requireJava && !command.toLowerCase().contains("java")) {
            throw new ToolException("target PID " + pid + " does not look like a Java process");
        }
        String host = hostname();
        return mapOf(
            "pid", String.valueOf(pid),
            "host", host,
            "jvm_start_time", start,
            "command_line_digest", digest(command),
            "executable_or_container_identity", digest(host + "\n" + command),
            "command_line", command);
    }

    static boolean fingerprintMatches(Map<String, Object> expected, Map<String, Object> actual) {
        for (String key : FINGERPRINT_KEYS) {
            if (!Objects.equals(expected.get(key), actual.get(key))) {
                return false;
            }
        }
        return true;
    }

    /** Like shutil.which: a bare name is searched on PATH, anything with a separator is checked as-is. */
    static Optional<Path> which(String candidate) {
        if (candidate.contains(File.separator)) {
            Path p = Path.of(candidate);
            return Files.isRegularFile(p) && Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
        }
        String path = Optional.ofNullable(System.getenv("PATH")).orElse("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path p = Path.of(dir, candidate);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    static String resolveProfiler(String explicit) {
        List<String> candidates = new ArrayList<>();
        if (explicit != null) {
            candidates.add(explicit);
        }
        String home = System.getenv("ASYNC_PROFILER_HOME");
        if (home != null && !home.isEmpty()) {
            candidates.add(Path.of(home, "bin", "asprof").toString());
            candidates.add(Path.of(home, "bin", "profiler.sh").toString());
        }
        candidates.add("asprof");
        candidates.add("profiler.sh");
        for (String candidate : candidates) {
            Optional<Path> resolved = which(candidate);
            if (resolved.isPresent()) {
                if (!PROFILER_NAMES.contains(resolved.get().getFileName().toString())) {
                    continue;
                }
                return resolved.get().toString();
            }
        }
        throw new ToolException("async-profiler CLI not found; set ASYNC_PROFILER_HOME or --profiler");
    }

    /** Resolves a session id under {@code root}, rejecting ids that escape it (e.g. {@code ../x}). */
    static Path newSessionPath(Path root, String sessionId) {
        Path path = root.resolve(sessionId).normalize();
        if (!Objects.equals(path.getParent(), root)) {
            throw new ToolException("invalid session id");
        }
        return path;
    }

    static void updateState(Path session, String state, Object... extra) throws IOException {
        Path manifestPath = session.resolve("manifest.json");
        Map<String, Object> manifest = readJson(manifestPath);
        manifest.put("state", state);
        manifest.put("updated_at", now());
        manifest.putAll(mapOf(extra));
        atomicJson(manifestPath, manifest);
    }

    /** Starts {@code command} in its own session when {@code setsid} is available (Linux). */
    static ProcessBuilder detached(List<String> command) {
        List<String> full = new ArrayList<>();
        which("setsid").ifPresent(p -> full.add(p.toString()));
        full.addAll(command);
        return new ProcessBuilder(full);
    }

    static boolean requireJava(Map<String, Object> manifest) {
        return !Boolean.FALSE.equals(manifest.get("require_java"));
    }

    // ── operations ───────────────────────────────────────────────────────────

    record Options(Path sessionRoot, String operation, String sessionId, String pid, String output, String event,
                   int duration, String profiler, boolean allowNonJava) {}

    static int start(Options args) throws IOException {
        if (args.duration() < 1 || args.duration() > MAX_DURATION) {
            throw new ToolException("duration must be between 1 and " + MAX_DURATION + " seconds");
        }
        long pid = Long.parseLong(args.pid());
        Map<String, Object> target = processSnapshot(pid, !args.allowNonJava());
        String profiler = resolveProfiler(args.profiler());
        Path root = args.sessionRoot().toAbsolutePath().normalize();
        mkdirPrivate(root);
        root = root.toRealPath();
        String sessionId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path session = newSessionPath(root, sessionId);
        mkdirPrivate(session);
        Path output = expandUser(args.output()).toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new ToolException("output already exists: " + output);
        }
        mkdirPrivate(output.getParent());

        Map<String, Object> publicTarget = new LinkedHashMap<>(target);
        publicTarget.remove("command_line");
        Map<String, Object> profilerInfo = mapOf(
            "executable", profiler, "event", args.event(), "duration_seconds", args.duration(), "output", output.toString());
        Map<String, Object> manifest = mapOf(
            "session_id", sessionId,
            "state", "starting",
            "created_at", now(),
            "updated_at", now(),
            "target", publicTarget,
            "require_java", !args.allowNonJava(),
            "clock", mapOf("wall_start", now(), "monotonic_start_ns", System.nanoTime(), "clock_source", "java-System.nanoTime"),
            "profiler", profilerInfo,
            "limits", mapOf("max_duration_seconds", MAX_DURATION, "artifact_bytes", ARTIFACT_LIMIT),
            "session_dir", session.toString());
        atomicJson(session.resolve("manifest.json"), manifest);
        writePrivate(session.resolve("target-command-digest.txt"), target.get("command_line_digest") + "\n");
        profilerInfo.put("command", List.of(
            profiler, "-d", String.valueOf(args.duration()), "-e", args.event(), "-o", "jfr", "-f", output.toString(), String.valueOf(pid)));
        atomicJson(session.resolve("manifest.json"), manifest);

        Process supervisor;
        try {
            supervisor = detached(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    PerfEngineerSession.class.getName(),
                    "--session-root", root.toString(), "run", sessionId))
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException e) {
            updateState(session, "failed", "reason", "could not start supervisor: " + e.getMessage(), "finished_at", now());
            throw e;
        }
        writePrivate(session.resolve("supervisor.pid"), supervisor.pid() + "\n");
        updateState(session, "starting", "supervisor_pid", supervisor.pid());
        System.out.println(JSON.writeValueAsString(mapOf("session_id", sessionId, "session_dir", session.toString(), "state", "starting")));
        return 0;
    }

    record Loaded(Path session, Map<String, Object> manifest) {}

    static Loaded loadSession(Options args) throws IOException {
        Path root = args.sessionRoot().toAbsolutePath().normalize();
        Path session = newSessionPath(root.toRealPath(), args.sessionId());
        return new Loaded(session, readJson(session.resolve("manifest.json")));
    }

    static int run(Options args) throws IOException, InterruptedException {
        Loaded loaded = loadSession(args);
        Path session = loaded.session();
        Map<String, Object> manifest = loaded.manifest();
        Map<String, Object> target = obj(manifest.get("target"));
        Map<String, Object> profiler = obj(manifest.get("profiler"));
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) profiler.get("command");
        Path logPath = session.resolve("supervisor.log");
        Process child;
        try {
            Map<String, Object> current = processSnapshot(Long.parseLong((String) target.get("pid")), requireJava(manifest));
            if (!fingerprintMatches(target, current)) {
                updateState(session, "failed", "reason", "target fingerprint changed before profiler start", "finished_at", now());
                return 1;
            }
            Files.createFile(logPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            child = detached(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                .redirectErrorStream(true)
                .start();
        } catch (IOException | ToolException e) {
            updateState(session, "failed", "reason", "could not start profiler: " + e.getMessage(), "finished_at", now());
            return 1;
        }
        writePrivate(session.resolve("profiler.pid"), child.pid() + "\n");
        updateState(session, "running", "profiler_pid", child.pid());
        return runSupervisor(session, child, target, ((Number) profiler.get("duration_seconds")).intValue(), requireJava(manifest));
    }

    /** Mutable supervisor flags, written from the signal handler thread. */
    static class Control {
        volatile String terminalState;
    }

    static int runSupervisor(Path session, Process child, Map<String, Object> expected, int duration, boolean requireJava)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(duration);
        Control control = new Control();
        int targetMismatchCount = 0;
        long expectedPid = Long.parseLong((String) expected.get("pid"));

        sun.misc.SignalHandler stop = sig -> {
            control.terminalState = "stopped";
            if (child.isAlive()) {
                child.destroy();
            }
        };
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"), stop);
        sun.misc.Signal.handle(new sun.misc.Signal("INT"), stop);

        while (child.isAlive()) {
            if (System.nanoTime() >= deadline) {
                control.terminalState = "expired";
                child.destroy();
                break;
            }
            try {
                Map<String, Object> current = processSnapshot(expectedPid, requireJava);
                if (!fingerprintMatches(expected, current)) {
                    targetMismatchCount++;
                    if (targetMismatchCount >= 2) {
                        control.terminalState = "target-exited";
                        child.destroy();
                        updateState(session, "target-exited", "reason", "target fingerprint changed", "finished_at", now());
                        break;
                    }
                } else {
                    targetMismatchCount = 0;
                }
            } catch (ToolException e) {
                Thread.sleep(200);
                try {
                    Map<String, Object> current = processSnapshot(expectedPid, requireJava);
                    if (fingerprintMatches(expected, current)) {
                        targetMismatchCount = 0;
                        Thread.sleep(800);
                        continue;
                    }
                } catch (ToolException ignored) {
                    // still gone: fall through to target-exited
                }
                control.terminalState = "target-exited";
                child.destroy();
                updateState(session, "target-exited", "reason", "target process disappeared", "finished_at", now());
                break;
            }
            Path output = Path.of((String) obj(readJson(session.resolve("manifest.json")).get("profiler")).get("output"));
            if (Files.exists(output) && Files.size(output) > ARTIFACT_LIMIT) {
                control.terminalState = "failed";
                child.destroy();
                updateState(session, "failed", "reason", "artifact size limit exceeded", "finished_at", now());
                break;
            }
            // Wake early when the profiler exits or a signal handler terminates it.
            child.waitFor(1, TimeUnit.SECONDS);
        }
        if (!child.waitFor(15, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor();
        }
        int exit = child.exitValue();
        Map<String, Object> manifest = readJson(session.resolve("manifest.json"));
        Path output = Path.of((String) obj(manifest.get("profiler")).get("output"));
        if (Files.exists(output)) {
            restrict(output);
        }
        String terminal = control.terminalState;
        if (exit == 0 && (!Files.exists(output) || Files.size(output) == 0) && terminal == null) {
            terminal = "failed";
        }
        Object state = manifest.get("state");
        if (!"target-exited".equals(state) && !"failed".equals(state)) {
            updateState(session, terminal != null ? terminal : (exit == 0 ? "completed" : "failed"),
                "exit_code", exit, "finished_at", now());
        }
        return exit == 0 ? 0 : 1;
    }

    static int stop(Options args) throws IOException {
        Loaded loaded = loadSession(args);
        Path session = loaded.session();
        Map<String, Object> manifest = loaded.manifest();
        Object state = manifest.get("state");
        if (!"starting".equals(state) && !"running".equals(state)) {
            System.out.println(JSON.writeValueAsString(mapOf("session_id", manifest.get("session_id"), "state", state)));
            return 0;
        }
        long supervisorPid = Long.parseLong(Files.readString(session.resolve("supervisor.pid")).strip());
        updateState(session, "stop-requested", "requested_at", now());
        if (supervisorPid != ProcessHandle.current().pid()) {
            ProcessHandle.of(supervisorPid).ifPresent(ProcessHandle::destroy);
        }
        System.out.println(JSON.writeValueAsString(mapOf("session_id", manifest.get("session_id"), "state", "stop-requested")));
        return 0;
    }

    static int status(Options args) throws IOException {
        Loaded loaded = loadSession(args);
        Map<String, Object> manifest = loaded.manifest();
        Map<String, Object> target = obj(manifest.get("target"));
        try {
            Map<String, Object> current = processSnapshot(Long.parseLong((String) target.get("pid")), requireJava(manifest));
            manifest.put("target_matches", fingerprintMatches(target, current));
        } catch (ToolException e) {
            manifest.put("target_matches", false);
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return 0;
    }

    static int cleanup(Options args) throws IOException {
        Loaded loaded = loadSession(args);
        Map<String, Object> manifest = loaded.manifest();
        Object state = manifest.get("state");
        if ("running".equals(state) || "starting".equals(state) || "stop-requested".equals(state)) {
            throw new ToolException("stop the session before cleanup");
        }
        Files.deleteIfExists(Path.of((String) obj(manifest.get("profiler")).get("output")));
        Files.deleteIfExists(loaded.session().resolve("supervisor.log"));
        System.out.println(JSON.writeValueAsString(mapOf("session_id", manifest.get("session_id"), "state", state, "cleaned", true)));
        return 0;
    }

    // ── CLI ──────────────────────────────────────────────────────────────────

    static ToolException usage(String message) {
        return new ToolException(message + "\nusage: PerfEngineerSession [--session-root DIR] "
            + "{start PID --output FILE [--event E] [--duration S] [--profiler PATH] | status|stop|cleanup SESSION_ID}");
    }

    static Options parse(String[] argv) {
        String root = "~/.perf-engineer/sessions";
        String operation = null;
        List<String> positional = new ArrayList<>();
        String output = null;
        String event = "cpu";
        int duration = DEFAULT_DURATION;
        String profiler = null;
        boolean allowNonJava = false;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--session-root" -> root = value(argv, ++i, a);
                case "--output" -> output = value(argv, ++i, a);
                case "--event" -> event = value(argv, ++i, a);
                case "--duration" -> duration = Integer.parseInt(value(argv, ++i, a));
                case "--profiler" -> profiler = value(argv, ++i, a);
                case "--allow-non-java" -> allowNonJava = true;
                default -> {
                    if (a.startsWith("--")) {
                        throw usage("unknown option: " + a);
                    }
                    if (operation == null) {
                        operation = a;
                    } else {
                        positional.add(a);
                    }
                }
            }
        }
        if (operation == null || !Set.of("start", "run", "status", "stop", "cleanup").contains(operation)) {
            throw usage("missing or unknown operation");
        }
        if (positional.size() != 1) {
            throw usage(operation + " takes exactly one argument");
        }
        if (operation.equals("start") && output == null) {
            throw usage("start requires --output");
        }
        String arg = positional.get(0);
        return new Options(expandUser(root), operation, operation.equals("start") ? null : arg,
            operation.equals("start") ? arg : null, output, event, duration, profiler, allowNonJava);
    }

    static String value(String[] argv, int i, String option) {
        if (i >= argv.length) {
            throw usage(option + " requires a value");
        }
        return argv[i];
    }

    public static void main(String[] argv) {
        int code;
        try {
            Options args = parse(argv);
            code = switch (args.operation()) {
                case "start" -> start(args);
                case "run" -> run(args);
                case "status" -> status(args);
                case "stop" -> stop(args);
                case "cleanup" -> cleanup(args);
                default -> throw new IllegalStateException(args.operation());
            };
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = 2;
        }
        System.exit(code);
    }
}
