"""Run the installer invariants without an Android runtime or Gradle dependency."""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
sources = repo / "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/installer"
with tempfile.TemporaryDirectory(prefix="mybox-installer-classes-") as output:
    subprocess.run([
        "javac", "-encoding", "UTF-8", "-d", output,
        *[str(sources / name) for name in (
            "AtomicPackageDirectory.java", "InstallSessionState.java",
            "PackageSetValidator.java", "ApkNativeLibraries.java",
        )], str(repo / "tests/core/InstallerSelfTest.java"),
    ], check=True)
    subprocess.run(["java", "-ea", "-cp", output, "InstallerSelfTest"], check=True)
