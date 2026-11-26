"""Common Fedora/Windows entry point for Kestrel's local launcher and test runner."""
import argparse
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"
OPENER = build_opener(ProxyHandler({}))
SERVICES = {
    "maintenance": (8081, "/api/v1/auth/me", 401, ""),
    "inventory": (8082, "/services/inventory?wsdl", 200, ""),
    "telemetry": (9090, None, None, "-exec"),
}


def maven(*args):
    return ["cmd.exe", "/d", "/c", "mvnw.cmd", *args] if WINDOWS else ["./mvnw", *args]


def java():
    name = "java.exe" if WINDOWS else "java"
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / name) if home else name


def run(command, **options):
    subprocess.run(command, cwd=ROOT, check=True, **options)


def check_default_ports():
    for name in SERVICES:
        key = name.upper() + "_PORT"
        if os.environ.get(key, str(SERVICES[name][0])) != str(SERVICES[name][0]):
            raise RuntimeError(f"{key} must use the default port for this launcher.")


def jar_command(name):
    suffix = SERVICES[name][3]
    module = f"{name}-service"
    artifact = Path(module) / "target" / f"{module}-0.1.0-SNAPSHOT{suffix}.jar"
    if not (ROOT / artifact).is_file():
        raise RuntimeError(f"Missing {artifact}. Run the prepare mode first.")
    return [java(), "-jar", str(artifact)]


def prepare():
    if not (ROOT / ".env").is_file():
        raise RuntimeError("Create and configure .env using the repository README first.")
    run(maven("-B", "install"))
    run([java(), "scripts/GenerateKeys.java", os.getenv("KESTREL_KEYS_DIR") or ".kestrel/keys"])
    run(["docker", "compose", "up", "-d", "--wait", "postgres"])


def connected(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def service_ready(name):
    port, path, expected, _ = SERVICES[name]
    if path is None:
        return connected(port)
    try:
        with OPENER.open(f"http://127.0.0.1:{port}{path}", timeout=1) as response:
            return response.status == expected
    except HTTPError as response:
        with response:
            return response.code == expected
    except (URLError, TimeoutError):
        return False


def ensure_running(children):
    for name, child in children.items():
        if child.poll() is not None:
            raise RuntimeError(f"{name} exited with code {child.returncode}. See .kestrel/logs.")


def wait_for_services(children):
    pending = set(children)
    deadline = time.monotonic() + 60
    while pending and time.monotonic() < deadline:
        ensure_running(children)
        for name in sorted(pending):
            if service_ready(name):
                print(f"{name} ready on 127.0.0.1:{SERVICES[name][0]}", flush=True)
                pending.remove(name)
        if pending:
            time.sleep(0.25)
    if pending:
        raise RuntimeError(f"Startup timed out for {', '.join(sorted(pending))}. See .kestrel/logs.")


def stop(children, process_group=False):
    for child in children.values():
        if child.poll() is None:
            try:
                if process_group and WINDOWS:
                    subprocess.run(["taskkill.exe", "/PID", str(child.pid), "/T", "/F"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                elif process_group:
                    os.killpg(child.pid, signal.SIGTERM)
                else:
                    child.terminate()
            except ProcessLookupError:
                pass
    for child in children.values():
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            if child.poll() is None:
                if process_group and not WINDOWS:
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                else:
                    child.kill()
            child.wait()


def start_stack(with_desktop):
    check_default_ports()
    for name, (port, *_rest) in SERVICES.items():
        if connected(port):
            raise RuntimeError(f"{name} port {port} is occupied. Stop the existing process first.")
    prepare()
    logs = ROOT / ".kestrel" / "logs"
    logs.mkdir(parents=True, exist_ok=True)

    children = {}
    desktop = {}
    try:
        for name in SERVICES:
            with (logs / f"{name}.log").open("wb") as log:
                children[name] = subprocess.Popen(
                    jar_command(name), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT
                )
        wait_for_services(children)
        if with_desktop:
            group = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
                     if WINDOWS else {"start_new_session": True})
            desktop["desktop"] = subprocess.Popen(
                maven("-pl", "desktop-client", "javafx:run"), cwd=ROOT, **group
            )
        print("Services running. Press Ctrl+C to stop this launcher's processes.", flush=True)
        while True:
            ensure_running(children)
            if desktop:
                code = desktop["desktop"].poll()
                if code is not None:
                    return code
            time.sleep(0.25)
    finally:
        try:
            stop(desktop, process_group=True)
        finally:
            stop(children)


def launch(mode):
    if mode in ("all", "backend"):
        return start_stack(with_desktop=mode == "all")
    if mode == "prepare":
        prepare()
    elif mode == "desktop":
        run(maven("-pl", "desktop-client", "javafx:run"))
    else:
        run(jar_command(mode))
    return 0


def smoke():
    # Keep tests on Testcontainers even when a developer shell has a DB override.
    env = os.environ.copy()
    for prefix in ("KESTREL_TEST_DB", "KESTREL_INVENTORY_TEST_DB"):
        for suffix in ("URL", "USER", "PASSWORD"):
            env.pop(f"{prefix}_{suffix}", None)
    env["KESTREL_RUN_DB_TESTS"] = "true"
    env["KESTREL_RUN_INVENTORY_DB_TESTS"] = "true"
    run(maven("-B", "verify"), env=env)
    if not (ROOT / "desktop-client/target/test-classes/com/kestrel/desktop/TelemetryFreshnessSmoke.class").is_file():
        raise RuntimeError("Phase 8 freshness test is missing. Apply Phase 8 before running this suite.")
    classpath = os.pathsep.join(("desktop-client/target/classes", "desktop-client/target/test-classes"))
    run([java(), "-cp", classpath, "com.kestrel.desktop.TelemetryFreshnessSmoke"])
    print("PASS. Maven reactor, isolated database tests, and desktop freshness.")


def main():
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    start = subcommands.add_parser("start")
    start.add_argument("mode", nargs="?", default="all",
                       choices=("all", "backend", "prepare", *SERVICES, "desktop"))
    check = subcommands.add_parser("check")
    check.add_argument("--live", action="store_true", help="run the live REST/SOAP/gRPC test")
    check.add_argument("--machine-code", default="MACHINE-001")
    args = parser.parse_args()
    if args.command == "start":
        return launch(args.mode)
    if args.live:
        from kestrel_e2e import verify
        verify(ROOT, args.machine_code)
    else:
        smoke()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nStopped.")
        sys.exit(130)
    except subprocess.CalledProcessError as error:
        print(f"FAILED. Subprocess exited with status {error.returncode}.", file=sys.stderr)
        sys.exit(1)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"FAILED. {error}", file=sys.stderr)
        sys.exit(1)
