# KvmInputInjector

**Feature:** kvm (Android) · **Status:** production, **frozen** (do not change behaviour without
explicit approval; see `BRIDGEY_KVM_STATE.md`).

## Purpose
The single hand-off point between the input channel (already decrypted and sequence-checked
`InputEvent`s) and Android's real injection API (`BridgeyAccessibilityService.dispatchGesture`).

## Why it exists
It is deliberately the **only** class that knows both sides: the transport stays injection-agnostic
and the injector stays transport-agnostic. It is independent of Screen Share — wired whenever the
input channel is open — which makes Headless KVM (no video) the same code path as Visual KVM.

## Ownership / non-responsibilities
Owns pointer handling, the cursor overlay updates, scroll coalescing and the touchpad-gesture
dispatch. Does **not** own the input transport (`VideoChannelManager`, `TcpInputTransport`),
coordinate math (`KvmCoordinateMapper`), keyboard switching or calibration (Mac side).

## Non-obvious decisions (measured on hardware)
- **Scroll coalescing:** a Mac trackpad bursts ~80–90 events/s; deltas are accumulated for a short
  window and dispatched as one gesture. The window was lowered from 80 ms because it collided with
  gesture completion and produced 165–734 ms gaps.
- **Scroll speed multiplier:** Mac scroll points applied 1:1 as Android pixels felt ~10× too slow; one
  named constant scales only the raw delta.
- **Stuck-gesture safety net:** `dispatchGesture` can silently never complete (observed while
  locked); an in-flight flag that never clears would disable all scrolling, so it is force-cleared.
- **Coordinate inset:** `dispatchGesture` works in absolute screen space while the calibrated
  position is correct inside an inset overlay window; the real system inset is measured on every event
  (portrait (0,125) vs landscape (125,105) on an S23 Ultra) and added back.
- Overlay refresh is epsilon-gated so stationary scroll bursts do not churn the main looper.

## Tests
`KvmCoordinateMapperTest.kt`, `ScrollGestureAccumulatorTest.kt`, `InputEventCodec*Test.kt`,
`KvmCursorOverlayTest.kt`.

## Related
[KvmCoordinateMapper](KvmCoordinateMapper.md) · `BridgeyAccessibilityService.kt` ·
[BRIDGEY_KVM_STATE.md](../../../../../../../../BRIDGEY_KVM_STATE.md)
