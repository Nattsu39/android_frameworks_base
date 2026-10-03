#!/usr/bin/env python3
"""Compile the production display helper and replay OEM curve and primary-RAT fixtures."""

import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET


STUBS = {
    "android/content/res/Resources.java": """package android.content.res;
public class Resources { public int[] getIntArray(int id) { return new int[0]; } }
""",
    "android/telephony/CellSignalStrength.java": """package android.telephony;
public class CellSignalStrength {
    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN = 0;
    public static int getNumSignalStrengthLevels() { return 5; }
}
""",
    "android/telephony/CellSignalStrengthLte.java": """package android.telephony;
public class CellSignalStrengthLte extends CellSignalStrength {}
""",
    "android/telephony/CellSignalStrengthNr.java": """package android.telephony;
public class CellSignalStrengthNr extends CellSignalStrength {}
""",
    "android/telephony/SignalStrength.java": """package android.telephony;
import java.util.Collections;
import java.util.List;
public class SignalStrength {
    private final int level, lteRsrp, nrRsrp;
    private final boolean hasLte, hasNr, lteAsPrimary;
    public SignalStrength(int level, int lte, int nr, boolean hasLte, boolean hasNr,
            boolean lteAsPrimary) {
        this.level = level; lteRsrp = lte; nrRsrp = nr;
        this.hasLte = hasLte; this.hasNr = hasNr; this.lteAsPrimary = lteAsPrimary;
    }
    public int getLevel() { return level; }
    public int getDbm() { return hasLte && lteAsPrimary || !hasNr ? lteRsrp : nrRsrp; }
    public <T extends CellSignalStrength> List<T> getCellSignalStrengths(Class<T> type) {
        if (type == CellSignalStrengthLte.class && hasLte) {
            return Collections.singletonList(type.cast(new CellSignalStrengthLte()));
        }
        if (type == CellSignalStrengthNr.class && hasNr) {
            return Collections.singletonList(type.cast(new CellSignalStrengthNr()));
        }
        return Collections.emptyList();
    }
}
""",
    "com/android/internal/annotations/VisibleForTesting.java": """package com.android.internal.annotations;
public @interface VisibleForTesting {}
""",
    "com/android/systemui/res/R.java": """package com.android.systemui.res;
public final class R {
    public static final class array {
        public static final int config_mobileSignalStrengthRsrpThresholds = 1;
    }
}
""",
}

HARNESS = """package com.android.systemui.statusbar.connectivity;
import android.telephony.SignalStrength;
import java.util.Arrays;
public class MobileSignalStrengthHostTest {
    private static int assertions;
    private static void equal(int expected, int actual, String context) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(context + ": expected " + expected + ", actual " + actual);
        }
    }
    private static int reference(int rsrp, int[] curve) {
        if (rsrp < -156 || rsrp > -31) return 0;
        for (int i = 0; i < curve.length; i++) {
            if (rsrp > curve[i]) return 5 - i;
        }
        return 0;
    }
    public static void main(String[] args) {
        int[] stock = Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray();
        int[] device = Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray();
        MobileSignalStrength custom = new MobileSignalStrength(device);
        equal(6, custom.getNumLevels(), "five bars plus zero");
        equal(0, custom.getLevel(null), "missing report");
        for (int rsrp = -170; rsrp <= -20; rsrp++) {
            equal(reference(rsrp, stock),
                    custom.getLevel(new SignalStrength(1, rsrp, rsrp, false, true, false)),
                    "NR SS-RSRP=" + rsrp);
            if (rsrp >= -140 && rsrp <= -44) {
                equal(reference(rsrp, stock),
                        custom.getLevel(new SignalStrength(1, rsrp, rsrp, true, false, true)),
                        "LTE RSRP=" + rsrp);
            }
        }
        equal(3, custom.getLevel(new SignalStrength(1, -80, -106, true, true, false)),
                "NR primary retains -106 despite stronger LTE");
        equal(5, custom.getLevel(new SignalStrength(1, -80, -106, true, true, true)),
                "NSA LTE-primary policy retains -80");
        equal(0, custom.getLevel(new SignalStrength(0, 0, Integer.MAX_VALUE,
                false, true, false)), "unavailable NR must not look full");
        for (int level = 0; level <= 4; level++) {
            int expected = new int[] {0, 1, 3, 4, 5}[level];
            equal(expected, custom.getLevel(new SignalStrength(level, -90, -90,
                    false, false, false)), "other RAT retains relative standard level");
            MobileSignalStrength standard = new MobileSignalStrength(new int[0]);
            equal(level, standard.getLevel(new SignalStrength(level, -90, -90,
                    true, true, false)), "empty resource preserves standard level");
            equal(5, standard.getNumLevels(), "empty resource preserves four bars");
        }
        for (int[] invalid : new int[][] { {-97}, {-97, -105, -114, -118},
                {-123, -118, -114, -105, -97}, {-97, -105, -105, -118, -123},
                {-20, -105, -114, -118, -123} }) {
            equal(5, new MobileSignalStrength(invalid).getNumLevels(), "invalid resource disabled");
        }
        device[0] = -50;
        equal(5, custom.getLevel(new SignalStrength(4, -80, -80, true, false, true)),
                "caller mutation cannot change curve");
        System.out.println("PASS: " + assertions + " OEM differential and primary-RAT assertions");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--stock-config", type=Path, required=True)
    parser.add_argument("--device-config", type=Path, required=True)
    parser.add_argument("--jdk-bin", type=Path, required=True)
    args = parser.parse_args()
    stock = ET.parse(args.stock_config).find(".//signalstrengthconfig[@operator='ZTE_STANDARD']")
    curve = stock.find("signalstrength[@type='NR'][@signalstrengthtype='ssrsrp']").get("value")
    lte = stock.find("signalstrength[@type='LTE'][@signalstrengthtype='rsrp']").get("value")
    if curve != lte:
        raise ValueError("The shared display profile requires matching OEM LTE and NR curves")
    values = ET.parse(args.device_config).find(".//integer-array[@name='config_mobileSignalStrengthRsrpThresholds']")
    device = ",".join(item.text.strip() for item in values)
    with tempfile.TemporaryDirectory(prefix="mobile-signal-test-") as temporary:
        root = Path(temporary)
        for name, content in STUBS.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        harness = root / "com/android/systemui/statusbar/connectivity/MobileSignalStrengthHostTest.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding="utf-8")
        output = root / "classes"
        output.mkdir()
        subprocess.run([str(args.jdk_bin / "javac"), "-d", str(output), str(args.source),
                        *map(str, root.rglob("*.java"))], check=True)
        subprocess.run([str(args.jdk_bin / "java"), "-cp", str(output),
                        "com.android.systemui.statusbar.connectivity.MobileSignalStrengthHostTest",
                        curve, device], check=True)


if __name__ == "__main__":
    main()
