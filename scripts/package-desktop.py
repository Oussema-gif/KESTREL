"""Build and verify an OS-native Kestrel desktop app image."""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile

from kestrel_local import ROOT, WINDOWS, java, maven, run

MODULE = ROOT / "desktop-client"
MAIN_CLASS = "com.kestrel.desktop.DesktopLauncher"
ARTIFACT = "desktop-client-0.1.0-SNAPSHOT.jar"
DEPENDENCIES = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies"
RUNTIME_MODULES = "java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs"


def jpackage_tool():
    java_path = shutil.which(java())
    if not java_path:
        raise RuntimeError("Java was not found. Set JAVA_HOME to the full JDK 21.")
    home = Path(java_path).resolve().parents[1]
    tool = home / "bin" / ("jpackage.exe" if WINDOWS else "jpackage")
    if not tool.is_file() or not (home / "jmods/java.base.jmod").is_file():
        raise RuntimeError(
            f"{home} needs jpackage and jmods. Set JAVA_HOME to a full JDK 21."
        )
    version = subprocess.check_output([str(tool), "--version"], text=True).strip()
    if version.split(".", 1)[0] != "21":
        raise RuntimeError(f"Expected JDK 21 jpackage, got {version}.")
    return tool


def build_image(destination):
    tool = jpackage_tool()
    run(maven("-B", "-pl", "desktop-client", "-am", "install"))
    target = MODULE / "target"
    app_jar = target / ARTIFACT
    if not app_jar.is_file():
        raise RuntimeError(f"Missing desktop JAR at {app_jar}.")

    destination.mkdir(parents=True, exist_ok=True)
    image_parent = Path(tempfile.mkdtemp(prefix=platform.system().lower() + "-", dir=destination))
    with tempfile.TemporaryDirectory(prefix="phase10-input-", dir=target) as temporary:
        stage = Path(temporary)
        # copy-dependencies interprets a relative output directory from its module.
        run(maven("-B", "-pl", "desktop-client", DEPENDENCIES,
                  "-DincludeScope=runtime", f"-DoutputDirectory=target/{stage.name}"))
        shutil.copy2(app_jar, stage)
        run([str(tool), "--type", "app-image", "--name", "Kestrel",
             "--app-version", "0.1.0", "--description", "Kestrel FORGE OPS desktop client",
             "--input", str(stage), "--dest", str(image_parent), "--main-jar", ARTIFACT,
             "--main-class", MAIN_CLASS, "--add-modules", RUNTIME_MODULES])

    image = image_parent / "Kestrel"
    launcher = image / ("Kestrel.exe" if WINDOWS else "bin/Kestrel")
    if not launcher.is_file():
        raise RuntimeError(f"jpackage completed without the expected launcher at {launcher}.")
    run([str(launcher), "--self-check"], timeout=30)
    print("PASS. Packaged Kestrel launcher completed its runtime self-check.")
    print(f"App image: {image}")
    print(f"Launch: {launcher}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dest", type=Path, default=ROOT / "target/distribution",
                        help="directory for the generated app image")
    args = parser.parse_args()
    build_image(args.dest.resolve())


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nPackaging stopped.", file=sys.stderr)
        sys.exit(130)
    except subprocess.TimeoutExpired:
        print("FAILED. The packaged launcher self-check timed out.", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as error:
        print(f"FAILED. Packaging or the launcher self-check exited with status {error.returncode}.",
              file=sys.stderr)
        sys.exit(1)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"FAILED. {error}", file=sys.stderr)
        sys.exit(1)
