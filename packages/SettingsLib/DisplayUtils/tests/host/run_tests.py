#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile and test the real brightness conversion code without an Android runtime."""

import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE = HERE.parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk-bin", type=Path, help="Directory containing Java 17+ java and javac")
    args = parser.parse_args()
    jdk_bin = args.jdk_bin
    if jdk_bin is None:
        javac = shutil.which("javac")
        if javac is None:
            parser.error("Provide --jdk-bin or put a Java 17+ JDK on PATH")
        jdk_bin = Path(javac).resolve().parent
    suffix = ".exe" if os.name == "nt" else ""
    javac = jdk_bin / ("javac" + suffix)
    java = jdk_bin / ("java" + suffix)
    source_directory = MODULE / "src/com/android/settingslib/display"
    sources = [source_directory / "BrightnessUtils.java", source_directory / "NitsBrightnessCurve.java"]
    sources.extend(HERE.rglob("*.java"))
    cases = [
        ("nits", "NX809J", "false", "local:4630947168392018835"),
        ("linear", "NX809J", "true", "local:4630947168392018835"),
        ("nits", "NX809J", None, "local:4630947168392018835"),
        ("hlg", "another-device", None, "local:4630947168392018835"),
        ("hlg", "NX809J", "false", "local:another-panel"),
    ]
    with tempfile.TemporaryDirectory(prefix="brightness-curve-test-") as temporary:
        subprocess.run([str(javac), "-d", temporary, *map(str, sources)], check=True)
        for mode, device, gamma_disabled, panel in cases:
            command = [str(java), f"-Dro.product.device={device}", f"-Dtest.panel={panel}"]
            if gamma_disabled is not None:
                command.append(f"-Dsys.brightness.disable_gamma_conversion={gamma_disabled}")
            command.extend(["-cp", temporary, "com.android.settingslib.display.BrightnessCurveTest", mode])
            subprocess.run(command, check=True)
    print("PASS: all five device/property configurations")


if __name__ == "__main__":
    main()
