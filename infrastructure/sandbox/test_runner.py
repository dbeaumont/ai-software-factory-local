import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request


TOKEN = "a" * 64
DIGEST = "b" * 64
PORT = 18088
RUNNER = Path(__file__).with_name("runner.py")
RUNNER_SPEC = importlib.util.spec_from_file_location("sandbox_runner", RUNNER)
RUNNER_MODULE = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(RUNNER_MODULE)


class SandboxProcessTest(unittest.TestCase):
    def test_timeout_kills_the_process_group(self):
        with tempfile.TemporaryDirectory() as workspace:
            _, output, _, timed_out = RUNNER_MODULE.bounded_process(
                "sleep 30 & echo $!; wait", Path(workspace), "d" * 32, 1, 4096
            )
            child_pid = int(output.strip())

            self.assertTrue(timed_out)
            for _ in range(20):
                state = subprocess.run(
                    ["ps", "-p", str(child_pid), "-o", "stat="],
                    capture_output=True, text=True, check=False,
                ).stdout.strip()
                if not state or state.startswith("Z"):
                    break
                time.sleep(0.05)
            self.assertTrue(not state or state.startswith("Z"), f"child process remains active: {state}")

    def test_execution_temporary_directory_is_removed(self):
        with tempfile.TemporaryDirectory() as workspace:
            exit_code, output, _, timed_out = RUNNER_MODULE.bounded_process(
                "touch \"$TMPDIR/marker\"; printf '%s' \"$TMPDIR\"",
                Path(workspace), "e" * 32, 2, 4096,
            )

            self.assertEqual(0, exit_code)
            self.assertFalse(timed_out)
            self.assertFalse(Path(output).exists())


class SandboxRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.workspace = Path(self.temporary.name)
        (self.workspace / "task-1").mkdir()
        env = os.environ.copy()
        env.update({
            "AI_FACTORY_SANDBOX_RUNNER_TOKEN": TOKEN,
            "AI_FACTORY_SANDBOX_IMAGE": "sha256:" + DIGEST,
            "AI_FACTORY_RUNNER_ALLOWED_PROFILES": "patch-check-v1,patch-apply-v1",
            "AI_FACTORY_RUNNER_WORKSPACE_ROOT": str(self.workspace),
            "AI_FACTORY_SANDBOX_MAX_OUTPUT_CHARS": "4096",
            "AI_FACTORY_RUNNER_PORT": str(PORT),
        })
        self.process = subprocess.Popen(
            ["python3", str(RUNNER)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        for _ in range(50):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=0.2)
                break
            except Exception:
                time.sleep(0.05)
        else:
            self.fail("runner did not become ready")

    def tearDown(self):
        self.process.terminate()
        self.process.wait(timeout=5)
        self.temporary.cleanup()

    def request(self, payload):
        request = urllib.request.Request(
            f"http://127.0.0.1:{PORT}/v1/executions",
            data=json.dumps(payload).encode(),
            headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"},
            method="POST",
        )
        return urllib.request.urlopen(request, timeout=5)

    def test_lists_active_executions_only_with_authentication(self):
        request = urllib.request.Request(
            f"http://127.0.0.1:{PORT}/v1/executions",
            headers={"Authorization": "Bearer " + TOKEN},
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual([], json.load(response)["execution_ids"])

    def valid_payload(self):
        return {
            "execution_id": "c" * 32,
            "task_directory": "task-1",
            "profile_id": "patch-check-v1",
            "image_digest": DIGEST,
            "timeout_seconds": 2,
            "max_output_chars": 4096,
        }

    def test_executes_only_registered_profile(self):
        response = json.load(self.request(self.valid_payload()))
        self.assertNotEqual(0, response["exit_code"])
        self.assertFalse(response["timed_out"])

    def test_applies_a_registered_patch_to_the_task_workspace(self):
        task = self.workspace / "task-1"
        subprocess.run(["git", "init", "-q", str(task)], check=True)
        (task / "message.txt").write_text("before\n")
        (task / "changes.patch").write_text(
            "diff --git a/message.txt b/message.txt\n"
            "--- a/message.txt\n"
            "+++ b/message.txt\n"
            "@@ -1 +1 @@\n"
            "-before\n"
            "+after\n"
        )
        payload = self.valid_payload()
        payload["profile_id"] = "patch-apply-v1"

        response = json.load(self.request(payload))

        self.assertEqual(0, response["exit_code"])
        self.assertEqual("after\n", (task / "message.txt").read_text())

    def test_rejects_caller_supplied_command(self):
        marker = self.workspace / "escaped"
        payload = self.valid_payload()
        payload["command"] = f"touch {marker}"
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(payload)
        self.assertEqual(400, error.exception.code)
        error.exception.close()
        self.assertFalse(marker.exists())

    def test_rejects_wrong_image_digest(self):
        payload = self.valid_payload()
        payload["image_digest"] = "d" * 64
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(payload)
        self.assertEqual(400, error.exception.code)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
