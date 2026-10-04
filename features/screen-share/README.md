# Screen Share

**Answers:** can I see (and, with KVM, control) the phone's screen on the Mac?

**Owns:** capture on Android (MediaProjection + H.264 encoder, `ScreenCaptureManager`,
`ScreenCaptureService`), Pocket Mode ([PocketGuard](android/PocketGuard.md)), decoding and display on
the Mac ([ScreenStreamDecoder](macos/ScreenStreamDecoder.md), VideoToolbox), the geometry of the
displayed video (`VideoContentGeometry`, also used by KVM for click mapping), Remote Start (with the
system consent dialog), and the remote sharing state.
**Does not own:** the video/input channels and their security (core/connection auxiliary channels),
input injection (kvm).

## Code
| | |
|---|---|
| `android/` | `ScreenCaptureManager`, `ScreenCaptureService`, `PocketGuard` |
| `macos/` | `ScreenStreamDecoder`, `VideoContentGeometry` |
| still in `macos/Sources/BridgeyMac` | `ScreenShareWindow.swift` — also contains the frozen KVM capture view (`KvmMouseCaptureView`); split pending a KVM decision |
| still in the platform folders | `VideoFrameFraming.*` — framing used by the channel transport (`TCPVideoTransport`), ownership pending |

## Rules
Remote Start never bypasses Android's MediaProjection consent. Pocket Mode never renders into the
captured image and never outlives the stream.

## Tests
`tests/macos/ScreenStreamDecoderTests.swift`, `VideoContentGeometryTests.swift`.
