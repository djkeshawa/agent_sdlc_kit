#!/usr/bin/env python3
"""Runs the kit's end-to-end test with one command:

    python3 tests/e2e/run.py            (from the kit's root; Linux or macOS)

It starts the mock services (mock.py) on a free local port, runs the whole flow (flow.py)
against ../../template in a temporary folder, stops the mock and removes the folder.
Options: --keep leaves the test project in place and prints where it is; --template DIR tests
another copy of the template. Needs a JDK 17+, git and Python 3. Exit code 0 = every check passed."""
import argparse
import shutil
import socket
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(port, mock):
    for _ in range(100):
        if mock.poll() is not None:
            sys.exit("the mock server stopped at start (exit code %s)" % mock.returncode)
        try:
            urllib.request.urlopen("http://127.0.0.1:%d/__admin/state" % port, timeout=1).read()
            return
        except OSError:
            time.sleep(0.1)
    sys.exit("the mock server did not answer on port %d" % port)


def main():
    ap = argparse.ArgumentParser(description="End-to-end test of the Agentic SDLC kit against mock services.")
    ap.add_argument("--template", default=str(HERE.parent.parent / "template"), help="the template folder to test")
    ap.add_argument("--keep", action="store_true", help="keep the test project for inspection")
    args = ap.parse_args()
    if sys.platform.startswith("win"):
        sys.exit("this test drives the kit through sh; run it on Linux, macOS or WSL")
    template = Path(args.template).resolve()
    if not (template / ".agent" / "Sdlc.java").is_file():
        sys.exit("no template at %s" % template)

    port = free_port()
    work = Path(tempfile.mkdtemp(prefix="sdlc-e2e-"))
    mock = subprocess.Popen([sys.executable, str(HERE / "mock.py"), str(port)])
    try:
        wait_for(port, mock)
        # the flow's repositories have their own base branch: a pull request build of this repository must not name it for them
        env = {k: v for k, v in os.environ.items() if k not in ("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET")}
        code = subprocess.run([sys.executable, str(HERE / "flow.py"), str(template), str(work / "run"), str(port)], env=env).returncode
    finally:
        mock.terminate()
        try:
            mock.wait(timeout=10)
        except subprocess.TimeoutExpired:
            mock.kill()
    if args.keep or code != 0:
        print("test project kept at %s" % (work / "run" / "payments"))
    else:
        shutil.rmtree(work, ignore_errors=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
