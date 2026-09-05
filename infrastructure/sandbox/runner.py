#!/usr/bin/env python3
"""Local-only fixed-profile sandbox runner. It never accepts caller-supplied commands."""

from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import tempfile
import threading
import sys


EXECUTION_ID = re.compile(r"^[0-9a-f]{32}$")
TASK_DIRECTORY = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
IMAGE_DIGEST = re.compile(r"^[0-9a-f]{64}$")
WORKSPACE_ROOT = Path(os.environ.get("AI_FACTORY_RUNNER_WORKSPACE_ROOT", "/factory-tasks")).resolve()
TOKEN = os.environ.get("AI_FACTORY_SANDBOX_RUNNER_TOKEN", "")
EXPECTED_IMAGE = os.environ.get("AI_FACTORY_SANDBOX_IMAGE", "")
EXPECTED_DIGEST = EXPECTED_IMAGE.rsplit("sha256:", 1)[-1] if "sha256:" in EXPECTED_IMAGE else ""
MAX_OUTPUT = int(os.environ.get("AI_FACTORY_SANDBOX_MAX_OUTPUT_CHARS", "65536"))
PORT = int(os.environ.get("AI_FACTORY_RUNNER_PORT", "8088"))
ALLOWED = frozenset(filter(None, os.environ.get("AI_FACTORY_RUNNER_ALLOWED_PROFILES", "").split(",")))

PROFILES = {
    "patch-check-v1": "git apply --check changes.patch",
    "patch-apply-v1": (
        "git apply --check changes.patch && git apply changes.patch && "
        "git diff --check && git diff --stat"
    ),
    "test-maven-v1": (
        "if [ -z \"$MAVEN_MIRROR_URL\" ]; then echo 'Required Maven mirror is unavailable'; exit 2; fi; "
        "if [ -n \"$ARTIFACTORY_TOKEN\" ]; then MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings.xml'; "
        "else MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings-public.xml'; fi; "
        "if [ -f mvnw ]; then chmod +x mvnw; ./mvnw -B $MAVEN_SETTINGS test; "
        "else mvn -B $MAVEN_SETTINGS test; fi"
    ),
    "test-gradle-v1": (
        "if [ ! -f gradlew ]; then echo 'The immutable Gradle profile requires gradlew'; exit 2; fi; "
        "if [ -z \"$MAVEN_MIRROR_URL\" ]; then echo 'Required Gradle dependency mirror is unavailable'; exit 2; fi; "
        "chmod +x gradlew; ./gradlew --no-daemon --init-script /opt/ai-factory/gradle-mirror.init.gradle test"
    ),
    "test-node-v1": (
        "if [ -z \"$NPM_CONFIG_REGISTRY\" ]; then echo 'Required npm registry is unavailable'; exit 2; "
        "elif [ -f package-lock.json ]; then npm ci --ignore-scripts; "
        "else echo 'The immutable Node profile requires package-lock.json'; exit 2; fi; npm test -- --runInBand"
    ),
    "quality-sonar-v1": (
        "if [ -z \"$SONAR_TOKEN\" ]; then echo 'Required Sonar token is unavailable'; exit 2; "
        "elif [ -f pom.xml ]; then mkdir -p .ai-factory && set -o pipefail && "
        "if [ -n \"$ARTIFACTORY_TOKEN\" ]; then MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings.xml'; "
        "else MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings-public.xml'; fi; mvn -B $MAVEN_SETTINGS "
        "org.sonarsource.scanner.maven:sonar-maven-plugin:sonar "
        "-Dsonar.host.url=\"$SONAR_HOST_URL\" -Dsonar.token=\"$SONAR_TOKEN\" "
        "-Dsonar.qualitygate.wait=true | tee .ai-factory/sonar.txt; "
        "else echo 'SonarQube analysis supports Maven repositories only'; exit 2; fi"
    ),
    "security-syft-trivy-v2": (
        "set -o pipefail && mkdir -p .ai-factory && "
        "syft dir:. -o cyclonedx-json=.ai-factory/sbom.cdx.json >/dev/null && "
        "for attempt in 1 2 3; do trivy fs --download-db-only --timeout 2m && break; "
        "if [ \"$attempt\" -eq 3 ]; then exit 2; fi; sleep \"$attempt\"; done && "
        "trivy fs --skip-db-update --scanners vuln,secret --severity HIGH,CRITICAL "
        "--exit-code 1 --format table . | tee .ai-factory/trivy.txt && "
        "echo 'SBOM: .ai-factory/sbom.cdx.json'"
    ),
}

ACTIVE = {}
ACTIVE_WRITE_WORKSPACES = set()
ACTIVE_LOCK = threading.Lock()


def bounded_process(script, workspace, execution_id, timeout_seconds, max_output):
    execution_temp = Path(tempfile.mkdtemp(prefix=f"ai-factory-sandbox-{execution_id}-"))
    environment = os.environ.copy()
    environment["TMPDIR"] = str(execution_temp)
    try:
        process = subprocess.Popen(
            ["bash", "-lc", script], cwd=workspace, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, start_new_session=True, env=environment,
        )
        with ACTIVE_LOCK:
            ACTIVE[execution_id] = process
        chunks = deque()
        retained = 0
        truncated = False

        def read_output():
            nonlocal retained, truncated
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    return
                chunks.append(chunk)
                retained += len(chunk)
                while retained > max_output and chunks:
                    overflow = retained - max_output
                    first = chunks[0]
                    if len(first) <= overflow:
                        retained -= len(chunks.popleft())
                    else:
                        chunks[0] = first[overflow:]
                        retained -= overflow
                    truncated = True

        reader = threading.Thread(target=read_output, name=f"runner-output-{execution_id}", daemon=True)
        reader.start()
        timed_out = False
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
        finally:
            reader.join(timeout=10)
            if process.stdout is not None:
                process.stdout.close()
            with ACTIVE_LOCK:
                ACTIVE.pop(execution_id, None)
        output = b"".join(chunks).decode("utf-8", errors="replace")
        return process.returncode, output, truncated, timed_out
    finally:
        shutil.rmtree(execution_temp, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "ai-factory-sandbox-runner/1"

    def log_message(self, fmt, *args):
        print("runner:", fmt % args, flush=True)

    def send_json(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        supplied = self.headers.get("Authorization", "")
        return len(TOKEN) >= 32 and hmac.compare_digest(supplied, "Bearer " + TOKEN)

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"status": "UP"})
        elif self.path == "/v1/executions":
            if not self.authorized():
                self.send_json(401, {"error": "unauthorized"})
                return
            with ACTIVE_LOCK:
                execution_ids = sorted(ACTIVE)
            self.send_json(200, {"execution_ids": execution_ids})
        else:
            self.send_json(404, {"error": "not_found"})

    def do_DELETE(self):
        if not self.authorized():
            self.send_json(401, {"error": "unauthorized"})
            return
        match = re.fullmatch(r"/v1/executions/([0-9a-f]{32})", self.path)
        if not match:
            self.send_json(404, {"error": "not_found"})
            return
        with ACTIVE_LOCK:
            process = ACTIVE.get(match.group(1))
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
        self.send_json(202, {"cancelled": process is not None})

    def do_POST(self):
        if self.path != "/v1/executions":
            self.send_json(404, {"error": "not_found"})
            return
        if not self.authorized():
            self.send_json(401, {"error": "unauthorized"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 8192:
                raise ValueError("invalid request size")
            request = json.loads(self.rfile.read(length))
            allowed_fields = {
                "execution_id", "task_directory", "profile_id", "image_digest",
                "timeout_seconds", "max_output_chars",
            }
            if not isinstance(request, dict) or set(request) - allowed_fields:
                raise ValueError("request contains unsupported fields")
            execution_id = request["execution_id"]
            task_directory = request["task_directory"]
            profile_id = request["profile_id"]
            image_digest = request["image_digest"]
            timeout_seconds = int(request["timeout_seconds"])
            max_output = int(request.get("max_output_chars", MAX_OUTPUT))
            if not EXECUTION_ID.fullmatch(execution_id):
                raise ValueError("invalid execution_id")
            if not TASK_DIRECTORY.fullmatch(task_directory):
                raise ValueError("invalid task_directory")
            if profile_id not in ALLOWED or profile_id not in PROFILES:
                raise ValueError("profile is not allowed by this runner")
            if not IMAGE_DIGEST.fullmatch(image_digest) or image_digest != EXPECTED_DIGEST:
                raise ValueError("image digest does not match the running image")
            if timeout_seconds < 1 or timeout_seconds > 3600:
                raise ValueError("invalid timeout")
            if max_output < 1024 or max_output > MAX_OUTPUT:
                raise ValueError("invalid output limit")
            workspace = (WORKSPACE_ROOT / task_directory).resolve()
            if workspace.parent != WORKSPACE_ROOT or not workspace.is_dir():
                raise ValueError("unknown task workspace")
            write_workspace = profile_id == "patch-apply-v1"
            with ACTIVE_LOCK:
                if execution_id in ACTIVE:
                    raise ValueError("execution is already active")
                if write_workspace and workspace in ACTIVE_WRITE_WORKSPACES:
                    raise ValueError("workspace already has an active write execution")
                if write_workspace:
                    ACTIVE_WRITE_WORKSPACES.add(workspace)
            try:
                exit_code, output, truncated, timed_out = bounded_process(
                    PROFILES[profile_id], workspace, execution_id, timeout_seconds, max_output
                )
            finally:
                if write_workspace:
                    with ACTIVE_LOCK:
                        ACTIVE_WRITE_WORKSPACES.discard(workspace)
            self.send_json(200, {
                "exit_code": exit_code,
                "output": output,
                "output_truncated": truncated,
                "timed_out": timed_out,
            })
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
            self.send_json(400, {"error": str(exception)})
        except Exception:
            self.send_json(500, {"error": "runner execution failed"})


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--execute-profile" and sys.argv[3].isdigit():
        profile = sys.argv[2]
        if profile not in PROFILES:
            raise SystemExit("unknown immutable sandbox profile")
        exit_code, output, _, timed_out = bounded_process(
            PROFILES[profile], Path.cwd(), "0" * 32, int(sys.argv[3]), MAX_OUTPUT
        )
        print(output, end="")
        raise SystemExit(124 if timed_out else exit_code)
    if len(TOKEN) < 32 or not IMAGE_DIGEST.fullmatch(EXPECTED_DIGEST) or not ALLOWED:
        raise SystemExit("runner token, immutable image digest and allowed profiles are required")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
