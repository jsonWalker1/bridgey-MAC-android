# Bridgey KVM Mouse v1 — Final Status

## Status

**Physically validated end-to-end.** Mouse v1 is accepted with the current video geometry and normalized-coordinate mapping. No calibration offsets, Android inset compensation, scale correction, mapper changes, or protocol changes are justified by the measured data.

## End-to-end path

```text
macOS NSEvent
  -> KvmMouseCaptureView
  -> VideoContentGeometry.normalizedPoint(...)
  -> VideoChannelController / encrypted TCP input channel
  -> VideoChannelManager
  -> KvmInputInjector
  -> KvmCoordinateMapper
  -> BridgeyAccessibilityService.dispatchGesture(...)
```

- **Mac capture:** `KvmMouseCaptureView` accepts move, left-button down/up, and dragging within the displayed video content.
- **Geometry:** the presentation layer frame and inverse mouse mapping use the same `VideoContentGeometry.contentRect(...)`. The validated portrait run used a `480 x 900` capture view, a `720 x 1544` source, and content rect `x=30.1554, y=0, width=419.6891, height=900`.
- **Transport:** pointer events are normalized `0...1`, encrypted, authenticated, and sent on the dedicated input TCP channel.
- **Android mapping:** `KvmCoordinateMapper` maps normalized coordinates into the full Android display bounds. The verified device was `1440 x 3088`, density 3.5, density DPI 560, rotation 0. Its reported system insets (`top=125`, `bottom=53`) were not applied as coordinate offsets.
- **Injection:** `BridgeyAccessibilityService` receives the mapped position and injects it through `dispatchGesture()`.

## Physical calibration

The calibration used the preserved `raster.png` (`852 x 1846` RGB PNG), stretched into the Android calibration view (`1440 x 3088`).

The consolidated analysis contains 63 labelled samples: a historical documented 9-point set, a historical raw 27-click set, and a current raw 27-click set. Across all samples, the mean error was `(+3.53, +3.81)` px, MAE was `(6.11, 5.74)` px, and maximum absolute error was `(16.26, 29.64)` px. The isolated 29.64 px bottom-center value was not reproduced in the current run.

No 32 px translation, Android-inset displacement, coordinate-axis inversion, or large aspect/content-rectangle error was measured. A small apparent vertical top-to-bottom drift (~7 px) remains confounded by manual placement and clipped bottom markers; it does not justify a correction. A new 27-click pass is not required. If sub-5-pixel tuning is ever required, use controlled repeats of points 2, 6, and 8 instead of changing production mapping speculatively.

## Known limitations

- Left-button pointer actions, movement, and drag are implemented.
- Right click, middle click, and mouse wheel/scroll are not implemented.
- Android's public Accessibility APIs provide gesture injection, not desktop mouse button semantics.

## Verification

- Android debug build and in-place APK installation: passed.
- Android debug unit tests: passed.
- macOS `build-app.sh`: passed.
- macOS Swift test suite: 149 tests passed, 0 failures.
- The running macOS process was verified as the freshly built debug app.
- Samsung Galaxy S23 Ultra (`SM-S918B`) was connected through ADB.
- Existing trusted pairing, discovery, video/input event flow, and physical pointer injection were verified during the calibration session.

## Diagnostic artifacts retained

- `KVM_CALIBRATION_REPORT.md`
- `KVM_CALIBRATION_PRECISION_REPORT.md`
- Root `raster.png`
- The already-captured temporary runtime log at `/tmp/bridgey-kvm-cal.log`

The temporary calibration Activity, packaged raster duplicate, and verbose per-event calibration logging were removed after the accepted measurement.
