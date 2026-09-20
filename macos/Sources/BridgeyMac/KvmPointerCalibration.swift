import CoreGraphics

/// Empirical KVM pointer calibration nudge for the verified Bridgey KVM input path (Mac mouse ->
/// normalized coordinate -> Android pointer injection). Keyed by the phone video's orientation
/// (derived from `sourceSize`) because all calibration data collected so far - the original 63-sample
/// offline statistical fit in KVM_CALIBRATION_MODEL_ANALYSIS.md, and the live on-device visual
/// re-tuning that superseded it - was measured exclusively in portrait. There is no reason to assume
/// a portrait-derived offset also holds once the phone rotates to landscape (the content rect's
/// letterboxing axis itself flips), so landscape gets its own independent, currently-uncalibrated
/// slot rather than inheriting portrait's tuned value - tuning landscape later can't disturb the
/// confirmed portrait numbers, and vice versa.
///
/// Applied to the RAW captured window point, *before* VideoContentGeometry.normalizedPoint - not
/// added to the normalized (0...1) result afterwards. This matters: an offset added after
/// normalization is a fixed fraction of whatever contentRect happens to be current, so its effect in
/// Android pixels stays constant only as long as the Mac window size never changes. Live testing
/// showed resizing the KVM window visibly throws a post-normalization offset off, which means the
/// true error is constant in Mac window-point space (consistent with e.g. click/cursor-hotspot
/// precision), not Android-pixel space. Applying the offset pre-normalization instead means it runs
/// through the SAME contentRect division the real click does, at whatever size is current at click
/// time, so the correction scales correctly with window size instead of being frozen to the window
/// size (or orientation) it was tuned at.
enum KvmPointerCalibration {
    struct Offset {
        let xPt: CGFloat
        let yPt: CGFloat
    }

    /// Confirmed by live, on-device visual calibration (moving the KVM cursor to a recognizable
    /// on-screen target and iterating), in a portrait 480x900 window. Derivation history:
    ///   offsetYPt = (-123 / 3088) * 900 ~= -34.85 (from the original live-confirmed normalized offset)
    ///   then -6pt more (-34.85 -> -40.85) after fixing a layout bug where the video layer's on-screen
    ///   position could lag behind this view's own `bounds` during a fullscreen resize (see
    ///   DisplayLayerView/KvmMouseCaptureView.layout()) - that fix removed most, not all, of the
    ///   resize-related drift; this residual -6pt was confirmed by live visual re-tuning after it.
    ///   X needed no correction at all (confirmed at 0).
    static let portraitOffset = Offset(xPt: 0.0, yPt: -40.85)

    /// Live visual calibration probe, landscape session.
    /// History: X=0,Y=0 -> X=-400 -> X+320 => X=-80 -> (X-5, Y-30) => X=-85, Y=-30 -> Y-50 => Y=-80
    /// -> (X-30, Y-50) => X=-115, Y=-130 -> (X+5, Y+35) => X=-110, Y=-95 -> (X+5, Y+5) => X=-105, Y=-90.
    static let landscapeOffset = Offset(xPt: -105.0, yPt: -90.0)

    /// Landscape iff the source is wider than it is tall; a missing/degenerate size falls back to
    /// portrait (the only orientation this has ever actually been measured or run in).
    static func offset(for sourceSize: CGSize?) -> Offset {
        guard let sourceSize, sourceSize.width > 0, sourceSize.height > 0, sourceSize.width > sourceSize.height else {
            return portraitOffset
        }
        return landscapeOffset
    }

    /// Applies the calibration nudge to a raw captured window point (same coordinate space as the
    /// point passed into VideoContentGeometry.normalizedPoint - not yet normalized), selecting the
    /// offset for the phone's current orientation. No clamping here: VideoContentGeometry.normalizedPoint
    /// already returns nil for a point outside the content rect (e.g. a letterbox bar), which the
    /// caller already handles by dropping that pointer event - the same safety net a click near the
    /// content rect's edge already relies on.
    static func apply(_ point: CGPoint, sourceSize: CGSize?) -> CGPoint {
        let offset = offset(for: sourceSize)
        return CGPoint(x: point.x + offset.xPt, y: point.y + offset.yPt)
    }
}
