#!/usr/bin/env python3
"""Regression tests for perf-engineer-session.py using local fake processes only."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("perf-engineer-session.py")


class SessionSupervisorTest(unittest.TestCase):
    def run_tool(self, root: Path, *args: str) -> dict:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--session-root", str(root), *args],
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(result.stdout)

    def fake_profiler(self, directory: Path, seconds: int) -> Path:
        path = directory / "asprof"
        path.write_text(
            "#!/usr/bin/env python3\n"
            "import pathlib, sys, time\n"
            "output = pathlib.Path(sys.argv[sys.argv.index('-f') + 1])\n"
            "output.write_bytes(b'fake-jfr')\n"
            f"time.sleep({seconds})\n"
        )
        path.chmod(0o700)
        return path

    def target(self) -> subprocess.Popen:
        return subprocess.Popen(["sleep", "60"])

    def wait_for_state(self, root: Path, session_id: str, states: set[str]) -> dict:
        deadline = time.monotonic() + 10
        last = {}
        while time.monotonic() < deadline:
            last = self.run_tool(root, "status", session_id)
            if last.get("state") in states:
                return last
            time.sleep(0.2)
        self.fail(f"session did not reach {states}: {last}")

    def test_bounded_completion_and_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "sessions"
            target = self.target()
            try:
                fake = self.fake_profiler(Path(tmp), 0)
                started = self.run_tool(
                    root,
                    "start",
                    str(target.pid),
                    "--profiler",
                    str(fake),
                    "--duration",
                    "2",
                    "--output",
                    str(Path(tmp) / "profile.jfr"),
                    "--allow-non-java",
                )
                session_id = started["session_id"]
                finished = self.wait_for_state(root, session_id, {"completed"})
                self.assertTrue(finished["target_matches"])
                cleaned = self.run_tool(root, "cleanup", session_id)
                self.assertTrue(cleaned["cleaned"])
            finally:
                target.terminate()
                target.wait()

    def test_explicit_stop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "sessions"
            target = self.target()
            try:
                fake = self.fake_profiler(Path(tmp), 60)
                started = self.run_tool(
                    root,
                    "start",
                    str(target.pid),
                    "--profiler",
                    str(fake),
                    "--duration",
                    "30",
                    "--output",
                    str(Path(tmp) / "profile.jfr"),
                    "--allow-non-java",
                )
                session_id = started["session_id"]
                self.wait_for_state(root, session_id, {"running"})
                self.run_tool(root, "stop", session_id)
                stopped = self.wait_for_state(root, session_id, {"stopped"})
                self.assertEqual("stopped", stopped["state"])
            finally:
                target.terminate()
                target.wait()

    def test_target_disappearance_stops_session(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "sessions"
            target = self.target()
            try:
                fake = self.fake_profiler(Path(tmp), 60)
                started = self.run_tool(
                    root,
                    "start",
                    str(target.pid),
                    "--profiler",
                    str(fake),
                    "--duration",
                    "30",
                    "--output",
                    str(Path(tmp) / "profile.jfr"),
                    "--allow-non-java",
                )
                session_id = started["session_id"]
                self.wait_for_state(root, session_id, {"running"})
                target.terminate()
                target.wait()
                failed = self.wait_for_state(root, session_id, {"target-exited"})
                self.assertEqual("target-exited", failed["state"])
            finally:
                if target.poll() is None:
                    target.terminate()
                    target.wait()

    def test_hard_timeout_marks_expired(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "sessions"
            target = self.target()
            try:
                fake = self.fake_profiler(Path(tmp), 60)
                started = self.run_tool(
                    root,
                    "start",
                    str(target.pid),
                    "--profiler",
                    str(fake),
                    "--duration",
                    "1",
                    "--output",
                    str(Path(tmp) / "profile.jfr"),
                    "--allow-non-java",
                )
                expired = self.wait_for_state(root, started["session_id"], {"expired"})
                self.assertEqual("expired", expired["state"])
            finally:
                target.terminate()
                target.wait()


if __name__ == "__main__":
    unittest.main()
