# BRIDGEY — KVM / MOUSE INPUT STATE

> Current handoff/state document for Bridgey's KVM and mouse-input work. This allows a fresh coding agent (Codex, Claude Code, etc.) to continue without relying on previous chat history.

## 1. PROJECT

Bridgey is an Android ↔ macOS Continuity system.

Primary test devices:
- Android: Samsung Galaxy S23 Ultra (SM-S918B)
- Android version: Android 16
- Mac: MacBook M4 15-inch

KVM goals:
1. Mac → Android mouse/pointer control
2. Mac → Android keyboard/text input
3. Later, richer KVM/game-control functionality

## 2. DEVELOPMENT RULE

Do not assume chat history is available. The repository and this file are the source of truth.

Before changing anything:
1. Inspect the repository and Git status.
2. Read this file and `CLAUDE.md` / `AGENTS.md` if present.
3. Inspect the actual current implementation.
4. Distinguish VERIFIED / IMPLEMENTED / NOT YET VERIFIED / HYPOTHESIS.
5. Do not turn a hypothesis into an architectural decision without testing.

## 3. FROZEN KVM FOUNDATION

Frozen baseline commit:

`1facc3d` — `KVM Input Foundation baseline`

Do not modify casually:
- `InputTransport.kt`
- `InputTransport.swift`
- `TcpInputTransport.kt`
- `TCPInputTransport.swift`
- `KvmInputInjector.kt`
- `BridgeyAccessibilityService.kt`
- `BridgeyInputMethodService.kt`
- IME manifest/configuration
- `KvmCoordinateMapper.kt`
- KVM coordinate mapper tests
- KVM security/channel implementation
- KVM feature gate

If a frozen component must change, explain why and get approval first. Prefer presentation/geometry/input-capture solutions.

## 4. TRANSPORT ARCHITECTURE

```text
macOS
  │
  ├── CONTROL CHANNEL
  ├── VIDEO TRANSPORT
  └── INPUT TRANSPORT
          │
          ▼
       Android
```

Video and input use dedicated transports to avoid video TCP head-of-line blocking affecting KVM responsiveness.

TCP first. UDP/QUIC may be considered later, but not as a solution to a geometry problem.

## 5. KVM STATES

```text
OFF
VIEWING
FOCUSED
```

Do not redesign the state machine during mouse calibration.

## 6. POINTER EVENT MODEL

Coordinates are normalized:

```text
x = 0.0 ... 1.0
y = 0.0 ... 1.0
```

Pipeline:

```text
Mac mouse position
        ↓
Mac video/content geometry
        ↓
normalized coordinates
        ↓
INPUT TRANSPORT
        ↓
Android KVM coordinate mapper
        ↓
Android display coordinates
        ↓
AccessibilityService
        ↓
dispatchGesture()
```

## 7. ANDROID POINTER INJECTION

Real pointer injection is VERIFIED.

Previously tested:
- AccessibilityService enabled
- paired Bridgey session
- input reaches Android
- normalized coordinates mapped
- `dispatchGesture()` called
- gestures completed

Full path verified:

```text
Mac
→ input.offer
→ TCP input transport
→ ACTIVE session
→ Pointer DOWN/UP
→ coordinate mapping
→ AccessibilityService
→ dispatchGesture
```

## 8. KEYBOARD / TEXT INPUT

InputMethodService path is also VERIFIED.

Previously:
- 13/13 text events succeeded
- 7/7 key events succeeded

Keys included Enter, Backspace, Left, Right, Up, Down, Escape.

Universal arbitrary key injection is not available through normal public Android APIs. Current design uses AccessibilityService for pointer gestures and InputMethodService for text/key input.

## 9. MAC MOUSE INPUT V1

Implemented on macOS.

`VideoContentGeometry.swift`
- contains `normalizedPoint(...)`
- converts Mac-space point to normalized video coordinates

`ScreenShareWindow.swift`
- contains `KvmMouseCaptureView`
- handles mouseDown, mouseDragged, mouseUp, mouseMoved

Existing Bridgey input path is used, conceptually:
- `videoChannel.offerInput(...)`
- `sendInputEvent(.pointer(...))`

Closing the screen-share window calls `stopInput()`.

## 10. MOUSE V1 SCOPE

Implemented:
- pointer movement
- left mouse click
- pointer drag
- normalized coordinates
- Mac → Android input transport
- Android AccessibilityService injection

Not implemented:
- right mouse button
- middle mouse button
- mouse wheel
- richer mouse semantics

Reason: frozen pointer wire format is effectively a fixed 9-byte representation, and Android `dispatchGesture()` does not directly provide desktop mouse-button/scroll semantics.

Do not redesign the protocol during geometry calibration.

## 11. VIDEO ARCHITECTURE

```text
Android MediaProjection
        ↓
hardware H.264 encoder
        ↓
encrypted TCP
        ↓
macOS
        ↓
Annex-B parsing
        ↓
SPS/PPS
        ↓
CMVideoFormatDescription
        ↓
CMBlockBuffer
        ↓
CMSampleBuffer
        ↓
AVSampleBufferDisplayLayer
```

Treat the existing PoC/reference architecture as stable. Do not introduce a parallel VTDecompressionSession merely to investigate geometry unless explicitly requested.

## 12. VIDEO SOURCE DIMENSIONS

Previously observed:
- Portrait: `720 × 1544`
- Landscape: `1544 × 720`

`CMVideoFormatDescriptionGetDimensions` and `CMVideoFormatDescriptionGetPresentationDimensions` were confirmed to return the same dimensions in the tested case.

AVSampleBufferDisplayLayer internally decodes the stream. A hidden decoded CVPixelBuffer mismatch was never proven; it was only a hypothesis.

## 13. ORIGINAL GEOMETRY PROBLEM

Mouse position on Mac did not map exactly to the corresponding Android screen position.

Portrait showed error primarily along the vertical/letterboxed axis.

Approximate boundary measurements:

Top-left visible image:
`~(34.6, 31.5)`

Bottom-left visible image:
`~(31.35, 869.13)`

Expected content geometry:
`x ≈ 30.16, y ≈ 0`

Window:
`480 × 900`

Observed visible video region was approximately:
`y ≈ 31.5 ... 869.1`

Effective visible height was therefore around `838 px`, rather than the expected 900.

This suggested that the geometry calculation and AVSampleBufferDisplayLayer's `.resizeAspect` presentation were not necessarily describing the exact same visible raster.

## 14. IMPORTANT GEOMETRY FINDING

The original implementation effectively had:

```text
layer fills whole view
+
videoGravity = .resizeAspect
+
separate contentRect calculation for mouse mapping
```

Potentially two independent geometry systems:

```text
Mouse mapping geometry
        ≠
AVSampleBufferDisplayLayer rendering geometry
```

That is undesirable for precise KVM control.

## 15. CURRENT GEOMETRY APPROACH

New approach:

```text
contentRect = VideoContentGeometry.contentRect(...)

layer.frame = contentRect
layer.videoGravity = .resize
layer.masksToBounds = true
```

Goal:

```text
ONE geometry calculation
        ↓
video rendering
        +
mouse mapping
```

The temporary autoresizing mask that could fight the explicit frame was removed. Temporary diagnostic logging was removed.

## 16. LLDB VERIFICATION

Live LLDB verification confirmed:

```text
videoGravity = AVLayerVideoGravityResize
```

Approximate layer frame:

```text
x = 30.155
y ≈ 0
width ≈ 419.689
height = 900
```

Layer bounds:

```text
0, 0, 419.689, 900
```

Mouse capture view:

```text
0, 0, 480, 900
```

Verified:

```text
layer.frame == contentRect
```

Stable across breakpoint hits. Only one relevant AVSampleBufferDisplayLayer. Running binary contained the fresh implementation.

## 17. VISUAL VERIFICATION

After the geometry change:
- previous ~31pt top/bottom black margins disappeared
- video reached top and bottom edges
- side black bars remained

Side bars are expected because portrait video aspect ratio does not fill the 480×900 window without horizontal distortion/cropping.

The change was visually real, not a no-op.

## 18. CURRENT STATUS

```text
KVM foundation                VERIFIED
Pointer injection             VERIFIED
Keyboard/text injection       VERIFIED
Mouse capture                 IMPLEMENTED
Mac → Android mouse path      VERIFIED
Geometry fix                  IMPLEMENTED
LLDB geometry verification    VERIFIED
Video visual geometry         VERIFIED
Final mouse precision         NOT YET VERIFIED
```

The remaining question is whether the new single-geometry approach produces correct real-world pointer mapping across the Android screen. This must be tested empirically.

## 19. NEXT STEP — RASTER CALIBRATION

There is an existing project file:

`raster.png`

Use the existing raster. Do NOT generate a replacement.

Purpose: provide known numbered points on the Android display so raw mapping can be measured independently of subjective visual cursor alignment.

Concept:

```text
known raster point
        ↓
Android native pixel coordinate
        ↓
Bridgey normalized coordinate
        ↓
Mac mouse position
        ↓
captured pointer event
```

Start in portrait. Do not introduce landscape until portrait is understood.

## 20. RASTER CALIBRATION TEST PLAN

Before modifying code:
1. Inspect `raster.png`.
2. Determine exact dimensions.
3. Determine exact coordinates of all numbered points.
4. Determine whether it is already native Android resolution.
5. Do not resize or regenerate it unless explicitly required.

Display the existing raster on Android:

```text
PORTRAIT
NATIVE SIZE
NO SCALING
NO CROPPING
NO LETTERBOXING
```

Click numbered points sequentially.

Record for every point:

```text
Point ID
Expected raster X
Expected raster Y
Mac mouse X
Mac mouse Y
Bridgey normalized X
Bridgey normalized Y
Android mapped X
Android mapped Y
Error X
Error Y
```

At minimum test:
- top-left
- top-center
- top-right
- center-left
- center
- center-right
- bottom-left
- bottom-center
- bottom-right

If the raster contains more points, use all of them.

## 21. ANALYZE ERROR PATTERN

Do not immediately add calibration constants.

Check for:

### Constant translation
`errorX ≈ constant` and/or `errorY ≈ constant`

### Linear scale mismatch
Error increases proportionally toward an edge.

### Aspect-ratio mismatch
One axis progressively drifts while the other behaves correctly.

### Letterbox/content-rect mismatch
Error begins at a boundary and grows across visible content.

### Coordinate-origin mismatch
Check top-left vs bottom-left and view-space vs screen-space.

### Nonlinear distortion
Only consider if simple affine explanations fail.

Do not jump to nonlinear calibration.

## 22. TESTING PRINCIPLE

Do not use visual cursor alignment alone.

The raster is the ground truth.

Goal:

```text
known Android pixel
        ↕
measured Bridgey coordinate
```

## 23. DO NOT CHANGE DURING CALIBRATION

Do NOT:
- modify frozen KVM foundation
- redesign input protocol
- add calibration constants
- add arbitrary magic offsets
- change Android injection semantics
- redesign video decoder
- introduce VTDecompressionSession
- switch to UDP/QUIC
- redesign KVM state machine
- implement right/middle/scroll
- optimize prematurely
- commit experimental hacks

This phase is measurement.

## 24. TESTING DISCIPLINE

For each experiment:
1. State hypothesis.
2. Make smallest possible change.
3. Build.
4. Run tests.
5. Test on real S23 Ultra.
6. Record measurements.
7. Compare with hypothesis.
8. Decide next change only after measurement.

Do not stack multiple unverified changes.

## 25. CURRENT VALIDATION

Previously:
- Swift tests: `149 / 149 passing`
- build: clean
- no Android changes required for mouse geometry implementation

Always check current state:

```bash
git status
git diff
git log --oneline -10
```

Do not assume there are no uncommitted changes.

## 26. GIT RULE

Do not create a commit unless explicitly requested.

Experimental geometry work should remain visible in Git diff until validated.

If a commit is requested:
- keep it narrowly scoped
- describe exactly what changed
- exclude unrelated formatting/refactors

## 27. WORKING PRINCIPLE

Bridgey aims to reproduce useful Apple Continuity-style behavior for Android ↔ macOS.

The goal is not merely:

> "make the cursor approximately work."

The goal is:

> The Mac pointer should map deterministically and predictably to the exact Android screen coordinate represented by the streamed video.

Core requirement:

```text
VIDEO GEOMETRY
       =
INPUT GEOMETRY
```

## 28. FRESH AGENT CHECKLIST

```text
[ ] Read this file
[ ] Read CLAUDE.md / AGENTS.md
[ ] Inspect git status
[ ] Inspect recent commits
[ ] Locate raster.png
[ ] Inspect VideoContentGeometry
[ ] Inspect ScreenShareWindow mouse capture
[ ] Inspect KVM input path
[ ] Verify frozen foundation has not changed
[ ] Build/test before modifying anything
[ ] Continue from current experiment rather than restarting architecture
```

## 29. IMMEDIATE TASK

```text
RASTER CALIBRATION OF PORTRAIT MOUSE MAPPING
```

Use the existing `raster.png`.

Establish exact mapping and error characteristics.

Do not fix the problem until measurement shows what the problem actually is.

## 30. FINAL RULE

```text
MEASURE FIRST.
CHANGE SECOND.
```

Never hide uncertainty behind a magic number.
Never declare geometry solved without real-device measurement.
Never modify the frozen KVM foundation without explicit approval.
