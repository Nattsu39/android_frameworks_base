# Device signal display curve

`MobileSignalStrength` is an optional SystemUI display policy. Its default resource
is empty, so devices keep the standard telephony levels and carrier inflation.
A device can supply four descending RSRP thresholds to calibrate its display.
The comparison is strict (`RSRP > threshold`), including at every boundary.

The NX809J profile is the `ZTE_STANDARD` LTE/NR curve extracted from RedMagicOS
11.0.21MR1: `[-97, -105, -114, -118, -123]` dBm. Its five levels are normalized
to the existing four bars with `round(stockLevel * 4 / 5)`. The resulting four
thresholds are `[-97, -105, -118, -123]` dBm: the OEM middle levels 2 and 3 both
map to level 2, so the -114 dBm boundary disappears. The OEM XML includes a final
`-1000` sentinel for level zero; the helper returns zero below the last threshold.
The extracted ZTE_STANDARD block is retained here as an independent differential
fixture. The complete source XML has SHA256
`9739028dcfab828da156c6aca17fef440879ca2f2e637efb345af477a5cd625d`.

The OEM profile selection depends on product/carrier properties and private
feature gates. This port deliberately selects the extracted default curve for
NX809J. It does not claim to recreate every operator-specific OEM profile.
The original framework level, dBm, modem reporting criteria, and IMS policy stay
independent of this display policy. LTE/NR share a curve, and `getDbm()` retains
the framework's primary signal selection in NSA. Other RATs keep their original
standard level. Missing or invalid RSRP displays zero.

The coroutine repository, Kairos repository, legacy mobile controller, and
internet details controller share the helper. Carrier-merged Wi-Fi and satellite
levels retain their own scales. Icon drawing, the number of levels, carrier
inflation, CDMA handling, and accessibility descriptions keep their original
behavior; the NX809J configuration uses four bars without inflation.

Run from the Android tree root, with the device overlay checked out:

```sh
python3 frameworks/base/packages/SystemUI/tests/host/mobile-signal/run_tests.py \
  --source frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/connectivity/MobileSignalStrength.java \
  --stock-config frameworks/base/packages/SystemUI/tests/host/mobile-signal/redmagic-signal-strength-config.xml \
  --device-config device/nubia/NX809J/overlay/frameworks/base/packages/SystemUI/res/values/config.xml \
  --jdk-bin prebuilts/jdk/jdk21/linux-x86/bin
```

The runner compiles the production Java helper against small host stubs. It
checks all integer RSRP values around the OEM curve, exact boundaries, the
reported -106 dBm regression, primary-signal selection fixtures, unavailable
measurements, disabled/malformed resources, fallback RATs, and array ownership.
These host checks do not execute Android callbacks or draw the UI. Validate a
built APK separately by comparing live telephony samples against SystemUI's
`MobileConnectionLog` and the rendered signal icon.
