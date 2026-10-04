# KvmCoordinateMapper

**Feature:** kvm (Android) · **Status:** production, **frozen**.

## Purpose
Pure math: normalised pointer position (0…1, the wire format of `InputEvent.Pointer`) → on-screen
pixel position, clamped to the screen.

## Why it exists
Both KVM modes send the same normalised coordinates — Visual KVM derives them from a click inside the
displayed video rectangle (`VideoContentGeometry` on the Mac), Headless KVM from a virtual cursor the
Mac moves with relative deltas — so the wire protocol does not change between modes. The arithmetic is
separate from Android framework classes purely so it has real unit tests.

## Invariants
Inputs outside 0…1 are clamped; results never leave `[0, size-1]`. The system inset is added later by
[KvmInputInjector](KvmInputInjector.md), not here.

## Tests
`KvmCoordinateMapperTest.kt`.

## Related
[KvmInputInjector](KvmInputInjector.md) · macOS `KvmPointerCalibration.swift`, `VideoContentGeometry.swift`
