import CoreGraphics

/// Empirical KVM pointer calibration nudge for the verified Bridgey KVM input path (Mac mouse ->
/// normalized coordinate -> Android pointer injection). Both offsets below were confirmed by live,
/// on-device visual calibration (moving the KVM cursor to a recognizable on-screen target and
/// iterating). See KVM_CALIBRATION_MODEL_ANALYSIS.md for the original offline statistical fit
/// (-3.5335px/1440, -3.8140px/3088) that this live result superseded - same sign and rough scale on
/// Y, not exact magnitude; X needed no correction at all (confirmed at 0) where the offline fit
/// suggested a small one.
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
/// size it was tuned at.
///
/// The constants are still expressed in window points (not a fraction of contentRect) because that
/// is the physical unit of the hypothesized error source (a few points of Mac-side click
/// imprecision) - they were derived from the live-confirmed normalized offsets at the tuning
/// session's window geometry (480x900 window, contentRect height 900, width 419.6891):
///   offsetYPt = (-123 / 3088) * 900 ~= -34.85
///   offsetXPt = (0 / 1440) * 419.6891 = 0
/// Y was further nudged by -6pt (-34.85 -> -40.85) after fixing a separate layout bug where the
/// video layer's on-screen position could lag behind this view's own `bounds` during a fullscreen
/// resize (see DisplayLayerView/KvmMouseCaptureView.layout()) - that fix removed most, not all, of
/// the resize-related drift; this residual -6pt was confirmed by live visual re-tuning after it.
enum KvmPointerCalibration {
    static let offsetXPt: CGFloat = 0.0
    static let offsetYPt: CGFloat = -40.85

    /// Applies the calibration nudge to a raw captured window point (same coordinate space as the
    /// point passed into VideoContentGeometry.normalizedPoint - not yet normalized). No clamping here:
    /// VideoContentGeometry.normalizedPoint already returns nil for a point outside the content rect
    /// (e.g. a letterbox bar), which the caller already handles by dropping that pointer event - the
    /// same safety net a click near the content rect's edge already relies on.
    static func apply(_ point: CGPoint) -> CGPoint {
        CGPoint(x: point.x + offsetXPt, y: point.y + offsetYPt)
    }
}
