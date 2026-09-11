"""Compile and run only the standalone Java runtime tests with JDK 21."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[1]
DEFAULT_JDK = Path(r"C:\Work\Morphe\toolchains\jdk-21.0.12.1+1")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=DEFAULT_JDK)
    args = parser.parse_args()
    suffix = ".exe" if sys.platform == "win32" else ""
    javac = args.jdk / "bin" / ("javac" + suffix)
    java = args.jdk / "bin" / ("java" + suffix)
    for executable in (javac, java):
        version = subprocess.run([str(executable), "-version"], check=True,
                                 capture_output=True, text=True)
        if not re.search(r"(?:javac |version \")21(?:\.|\")", version.stdout + version.stderr):
            raise RuntimeError(f"JDK 21 required: {executable}")
    sources = [REPO / "extensions/extension/src/main/java/io/github/yydarlinker/hansfix/HansFixRuntime.java"]
    sources.extend(sorted((REPO / "tests/java").rglob("*.java")))
    # Pure diagnostics core only; the Android facade boundary test is an opt-in SDK test.
    sources.extend([
        REPO / "extensions/extension/src/main/java/io/github/yydarlinker/hansfix/diagnostics/DiagnosticStore.java",
        REPO / "tests/diagnostics/DiagnosticStoreTest.java",
    ])
    if len(sources) < 2:
        raise RuntimeError("No Java runtime test sources found")
    output = REPO / "build/runtime-tests"
    output.mkdir(parents=True, exist_ok=True)
    # A fresh child directory prevents stale classes from masking a failed or partial compile.
    classes = tempfile.mkdtemp(prefix="classes-", dir=output)
    subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-Xlint:all",
                    "-classpath", classes, "-d", classes, *map(str, sources)], check=True)
    subprocess.run([str(java), "-ea", "-cp", classes,
                    "io.github.yydarlinker.hansfix.HansFixRuntimeTest"], check=True)
    subprocess.run([str(java), "-ea", "-cp", classes,
                    "io.github.yydarlinker.hansfix.diagnostics.DiagnosticStoreTest"], check=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
