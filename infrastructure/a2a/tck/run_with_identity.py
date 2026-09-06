"""Execute the untouched upstream TCK against the isolated conformance SUT."""

from __future__ import annotations

import os
import subprocess
import sys


environment = dict(os.environ)
command = ["uv", "run", "./run_tck.py", "--sut-host", "http://a2a-tck-sut:8090",
           "--transport", "jsonrpc"]
command.extend(sys.argv[1:])
raise SystemExit(subprocess.run(command, cwd="/tck", env=environment, check=False).returncode)
