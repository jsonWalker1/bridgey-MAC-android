# KvmPointerCalibration

**Feature:** kvm (macOS) · **Status:** production, **frozen**.

## Purpose
An empirical offset that makes a Mac click inside the phone-screen window land on the intended pixel
on Android.

## Why it exists / non-obvious decisions
- The residual error was measured (63-sample statistical fit in `KVM_CALIBRATION_MODEL_ANALYSIS.md`,
  later superseded by live on-device re-tuning) and is constant in **Mac window-point space**
  (cursor hot-spot / click precision), not in Android pixels.
- Therefore the offset is applied to the raw window point **before** normalisation
  (`VideoContentGeometry.normalizedPoint`), so it scales correctly when the window is resized. An
  offset added after normalisation drifted visibly on resize.
- Keyed by orientation: all data is portrait; landscape has its own, currently uncalibrated slot so
  tuning one never disturbs the other.

## Tests
`KvmPointerCalibrationTests.swift`, `VideoContentGeometryTests.swift`.

## Related
`VideoContentGeometry.swift` · Android [KvmCoordinateMapper](../../../android/app/src/main/java/dev/bridgey/android/KvmCoordinateMapper.md) ·
[KVM_CALIBRATION_REPORT.md](../../../KVM_CALIBRATION_REPORT.md)
