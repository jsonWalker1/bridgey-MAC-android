# KvmGestureRecognizer

**Feature:** kvm (macOS) · **Status:** production, **frozen**; 2/3/4-finger hardware confirmation
still open (see `BRIDGEY_KVM_STATE.md`).

## Purpose
Turns Mac trackpad touches (`NSTouch`) into discrete phone gestures: 2-finger horizontal swipe =
Back/Forward, 3- and 4-finger swipes = system navigation.

## Why it exists
Android has no "trackpad gesture" input; the Mac must decide that a deliberate swipe happened and send
one discrete action. The decision is fail-safe: anything ambiguous is cancelled, never forced into a
direction.

## Non-obvious decisions (from three rounds of hardware feedback, 2026-09-23)
- **2-finger = plain swipe on the aggregate centroid**, the same technique as 3F/4F. An anchor+zone
  variant (one finger still, one moving) was tuned extensively and reverted: real hands move both
  fingers, NSTouch identities change mid-swipe and the finger count briefly drops to one. Do not
  reintroduce the discriminator without new hardware evidence.
- `twoFingerMinDistance` is larger than the 3F/4F thresholds because horizontal 2-finger motion is
  usually scrolling; it was lowered from 0.25 to 0.17 to accept the user's natural Back swipe.
- All distances are in NSTouch's normalised trackpad space, so they mean the same physical fraction on
  any trackpad. Too slow, too diagonal or too short → cancelled.

## Tests
`KvmGestureRecognizerTests.swift`.

## Related
Android [KvmInputInjector](../../../android/app/src/main/java/dev/bridgey/android/KvmInputInjector.md) ·
[BRIDGEY_KVM_STATE.md](../../../BRIDGEY_KVM_STATE.md) · [KVM_TOUCHPAD_GESTURES_STATUS.md](../../../KVM_TOUCHPAD_GESTURES_STATUS.md)
