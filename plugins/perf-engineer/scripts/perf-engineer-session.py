#!/usr/bin/env python3
"""Supervise one bounded async-profiler session.

This intentionally does not run an arbitrary shell command. It resolves an async-profiler CLI,
records a target fingerprint, enforces a deadline independently of the chat client, and persists
session state for status/stop/cleanup operations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import signal
import shutil
import socket
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_DURATION = 30
MAX_DURATION = 600
ARTIFACT_LIMIT = 512 * 1024 * 1024


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def atomic_json(path: Path, value: dict) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, indent=2) + "\n")
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def digest(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode()).hexdigest()


def process_snapshot(pid: int, require_java: bool = True) -> dict:
    start_result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "lstart="],
        check=False,
        capture_output=True,
        text=True,
    )
    command_result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        check=False,
        capture_output=True,
        text=True,
    )
    if start_result.returncode != 0 or command_result.returncode != 0:
        raise RuntimeError(f"target PID {pid} is not running")
    start = start_result.stdout.strip()
    command = command_result.stdout.strip()
    if not start or not command:
        raise RuntimeError(f"target PID {pid} is not running")
    if require_java and "java" not in command.lower():
        raise RuntimeError(f"target PID {pid} does not look like a Java process")
    return {
        "pid": str(pid),
        "host": socket.gethostname(),
        "jvm_start_time": start,
        "command_line_digest": digest(command),
        "executable_or_container_identity": digest(platform.node() + "\n" + command),
        "command_line": command,
    }


def fingerprint_matches(expected: dict, actual: dict) -> bool:
    return all(expected.get(key) == actual.get(key) for key in (
        "pid",
        "host",
        "jvm_start_time",
        "command_line_digest",
        "executable_or_container_identity",
    ))


def resolve_profiler(explicit: str | None) -> str:
    candidates = []
    if explicit:
        candidates.append(explicit)
    home = os.environ.get("ASYNC_PROFILER_HOME")
    if home:
        candidates.extend([str(Path(home) / "bin" / "asprof"), str(Path(home) / "bin" / "profiler.sh")])
    candidates.extend(["asprof", "profiler.sh"])
    for candidate in candidates:
        resolved = shutil.which(candidate)
        if resolved:
            if Path(resolved).name not in ("asprof", "profiler.sh"):
                continue
            return resolved
        if Path(candidate).name in ("asprof", "profiler.sh") and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise RuntimeError("async-profiler CLI not found; set ASYNC_PROFILER_HOME or --profiler")


def session_path(root: Path, session_id: str) -> Path:
    path = (root / session_id).resolve()
    if path.parent != root.resolve():
        raise RuntimeError("invalid session id")
    return path


def update_state(session: Path, state: str, **extra: object) -> None:
    manifest_path = session / "manifest.json"
    manifest = read_json(manifest_path)
    manifest["state"] = state
    manifest["updated_at"] = now()
    manifest.update(extra)
    atomic_json(manifest_path, manifest)


def start(args: argparse.Namespace) -> int:
    if args.duration < 1 or args.duration > MAX_DURATION:
        raise RuntimeError(f"duration must be between 1 and {MAX_DURATION} seconds")
    pid = int(args.pid)
    target = process_snapshot(pid, require_java=not args.allow_non_java)
    profiler = resolve_profiler(args.profiler)
    root = Path(args.session_root).expanduser().resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    session_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    session = session_path(root, session_id)
    session.mkdir(mode=0o700)
    output = Path(args.output).expanduser().resolve()
    if output.exists():
        raise RuntimeError(f"output already exists: {output}")
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    manifest = {
        "session_id": session_id,
        "state": "starting",
        "created_at": now(),
        "updated_at": now(),
        "target": {key: value for key, value in target.items() if key != "command_line"},
        "require_java": not args.allow_non_java,
        "clock": {"wall_start": now(), "monotonic_start_ns": time.monotonic_ns(), "clock_source": "python-time.monotonic_ns"},
        "profiler": {"executable": profiler, "event": args.event, "duration_seconds": args.duration, "output": str(output)},
        "limits": {"max_duration_seconds": MAX_DURATION, "artifact_bytes": ARTIFACT_LIMIT},
        "session_dir": str(session),
    }
    atomic_json(session / "manifest.json", manifest)
    digest_path = session / "target-command-digest.txt"
    digest_path.write_text(target["command_line_digest"] + "\n")
    os.chmod(digest_path, 0o600)
    cmd = [profiler, "-d", str(args.duration), "-e", args.event, "-o", "jfr", "-f", str(output), str(pid)]
    manifest["profiler"]["command"] = cmd
    atomic_json(session / "manifest.json", manifest)
    try:
        supervisor = subprocess.Popen(
            [sys.executable, str(Path(__file__).resolve()), "--session-root", str(root), "run", session_id],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except OSError as exc:
        update_state(session, "failed", reason=f"could not start supervisor: {exc}", finished_at=now())
        raise
    supervisor_pid = session / "supervisor.pid"
    supervisor_pid.write_text(str(supervisor.pid) + "\n")
    os.chmod(supervisor_pid, 0o600)
    update_state(session, "starting", supervisor_pid=supervisor.pid)
    print(json.dumps({"session_id": session_id, "session_dir": str(session), "state": "starting"}))
    return 0


def run(args: argparse.Namespace) -> int:
    session, manifest = load_session(args)
    target = manifest["target"]
    command = manifest["profiler"]["command"]
    log_path = session / "supervisor.log"
    try:
        current = process_snapshot(int(target["pid"]), require_java=manifest.get("require_java", True))
        if not fingerprint_matches(target, current):
            update_state(session, "failed", reason="target fingerprint changed before profiler start", finished_at=now())
            return 1
        with log_path.open("w") as log:
            os.chmod(log_path, 0o600)
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    except (OSError, RuntimeError) as exc:
        update_state(session, "failed", reason=f"could not start profiler: {exc}", finished_at=now())
        return 1
    profiler_pid = session / "profiler.pid"
    profiler_pid.write_text(str(child.pid) + "\n")
    os.chmod(profiler_pid, 0o600)
    update_state(session, "running", profiler_pid=child.pid)
    return run_supervisor(
        session,
        child,
        target,
        int(manifest["profiler"]["duration_seconds"]),
        require_java=manifest.get("require_java", True),
    )


def run_supervisor(
    session: Path,
    child: subprocess.Popen,
    expected: dict,
    duration: int,
    require_java: bool = True,
) -> int:
    deadline = time.monotonic() + duration
    stopping = False
    terminal_state = None
    target_mismatch_count = 0

    def stop(_signum: int, _frame: object) -> None:
        nonlocal stopping, terminal_state
        stopping = True
        terminal_state = "stopped"
        if child.poll() is None:
            child.terminate()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    while child.poll() is None:
        if time.monotonic() >= deadline:
            stopping = True
            terminal_state = "expired"
            child.terminate()
            break
        try:
            current = process_snapshot(int(expected["pid"]), require_java=require_java)
            if not fingerprint_matches(expected, current):
                target_mismatch_count += 1
                if target_mismatch_count >= 2:
                    stopping = True
                    terminal_state = "target-exited"
                    child.terminate()
                    update_state(session, "target-exited", reason="target fingerprint changed", finished_at=now())
                    break
            else:
                target_mismatch_count = 0
        except RuntimeError:
            time.sleep(0.2)
            try:
                current = process_snapshot(int(expected["pid"]), require_java=require_java)
                if fingerprint_matches(expected, current):
                    target_mismatch_count = 0
                    time.sleep(0.8)
                    continue
            except RuntimeError:
                pass
            stopping = True
            terminal_state = "target-exited"
            child.terminate()
            update_state(session, "target-exited", reason="target process disappeared", finished_at=now())
            break
        output = Path(read_json(session / "manifest.json")["profiler"]["output"])
        if output.exists() and output.stat().st_size > ARTIFACT_LIMIT:
            stopping = True
            terminal_state = "failed"
            child.terminate()
            update_state(session, "failed", reason="artifact size limit exceeded", finished_at=now())
            break
        time.sleep(1)
    try:
        child.wait(timeout=15)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait()
    manifest = read_json(session / "manifest.json")
    output = Path(manifest["profiler"]["output"])
    if output.exists():
        os.chmod(output, 0o600)
    if child.returncode == 0 and (not output.exists() or output.stat().st_size == 0) and terminal_state is None:
        terminal_state = "failed"
    if manifest.get("state") not in ("target-exited", "failed"):
        state = terminal_state or ("completed" if child.returncode == 0 else "failed")
        update_state(session, state, exit_code=child.returncode, finished_at=now())
    return 0 if child.returncode == 0 else 1


def load_session(args: argparse.Namespace) -> tuple[Path, dict]:
    root = Path(args.session_root).expanduser().resolve()
    session = session_path(root, args.session_id)
    manifest = read_json(session / "manifest.json")
    return session, manifest


def stop(args: argparse.Namespace) -> int:
    session, manifest = load_session(args)
    if manifest.get("state") not in ("starting", "running"):
        print(json.dumps({"session_id": manifest["session_id"], "state": manifest.get("state")}))
        return 0
    supervisor_pid = int((session / "supervisor.pid").read_text())
    update_state(session, "stop-requested", requested_at=now())
    if supervisor_pid != os.getpid():
        try:
            os.kill(supervisor_pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    print(json.dumps({"session_id": manifest["session_id"], "state": "stop-requested"}))
    return 0


def status(args: argparse.Namespace) -> int:
    session, manifest = load_session(args)
    target = manifest["target"]
    try:
        current = process_snapshot(int(target["pid"]), require_java=manifest.get("require_java", True))
        manifest["target_matches"] = fingerprint_matches(target, current)
    except RuntimeError:
        manifest["target_matches"] = False
    print(json.dumps(manifest, indent=2))
    return 0


def cleanup(args: argparse.Namespace) -> int:
    session, manifest = load_session(args)
    if manifest.get("state") in ("running", "starting", "stop-requested"):
        raise RuntimeError("stop the session before cleanup")
    output = Path(manifest["profiler"]["output"])
    if output.exists():
        output.unlink()
    log = session / "supervisor.log"
    if log.exists():
        log.unlink()
    print(json.dumps({"session_id": manifest["session_id"], "state": manifest["state"], "cleaned": True}))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Supervise one bounded async-profiler session")
    parser.add_argument("--session-root", default="~/.perf-engineer/sessions")
    sub = parser.add_subparsers(dest="operation", required=True)
    start_parser = sub.add_parser("start")
    start_parser.add_argument("pid")
    start_parser.add_argument("--output", required=True)
    start_parser.add_argument("--event", default="cpu")
    start_parser.add_argument("--duration", type=int, default=DEFAULT_DURATION)
    start_parser.add_argument("--profiler")
    start_parser.add_argument("--allow-non-java", action="store_true", help=argparse.SUPPRESS)
    start_parser.set_defaults(func=start)
    run_parser = sub.add_parser("run", help=argparse.SUPPRESS)
    run_parser.add_argument("session_id")
    run_parser.set_defaults(func=run)
    for name, func in (("status", status), ("stop", stop), ("cleanup", cleanup)):
        command = sub.add_parser(name)
        command.add_argument("session_id")
        command.set_defaults(func=func)
    args = parser.parse_args()
    try:
        return args.func(args)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
