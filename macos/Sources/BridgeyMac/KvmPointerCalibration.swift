import CoreGraphics

/// Empirical KVM pointer calibration nudge for the verified Bridgey KVM input path (Mac mouse ->
/// normalized coordinate -> Android pointer injection). Derived from cross-validated analysis of 63
/// labelled raster-click measurements across three independent calibration sessions on the verified
/// Samsung Galaxy S23 Ultra test device (1440x3088 display). See KVM_CALIBRATION_MODEL_ANALYSIS.md
/// for the full model comparison.
///
/// A plain constant translation - not a scale or full affine correction - is applied here because
/// leave-one-session-out cross-validation showed a translation-only fit improved held-out accuracy
/// in all three independent folds, while a fitted affine scale stayed within ~1% of 1.0 (no real
/// scale defect) and its fitted offset term was unstable across folds (sign-flipping, and actively
/// worse than no calibration in one fold) - i.e. not a stable, generalizing correction. All three
/// independent sessions also showed a consistently-signed bias of comparable magnitude, the
/// signature of a real reproducible offset rather than session-specific noise.
///
/// Expressed as a fraction of the normalized (0...1) coordinate space - rather than a fixed pixel
/// count - because normalized space is exactly what gets scaled to the Android display's own
/// resolution downstream (frozen KvmCoordinateMapper.toPixels), so a fixed fraction stays
/// proportionally correct if a differently-sized Android display connects, rather than hard-coding
/// a pixel offset for one specific device.
///
/// The Y value below (-123px / 3088) was subsequently confirmed by live, on-device visual
/// calibration (moving the KVM cursor to a recognizable on-screen target and iterating), superseding
/// the -3.8140px statistically-fit value from KVM_CALIBRATION_MODEL_ANALYSIS.md - the live result and
/// the offline fit agree on sign and rough scale but not on magnitude. X is left at 0 (no correction)
/// pending its own confirmation pass.
///
/// KNOWN LIMITATION: this value was derived/tuned at one specific Mac window size. Because the
/// correction is applied in already-normalized space (after VideoContentGeometry's contentRect
/// division), its effect in Android pixels stays constant regardless of window size - but live
/// testing showed resizing the KVM window visibly throws the calibration off, which means the true
/// error is likely constant in Mac window-point space instead, not Android-pixel space. Fixing that
/// properly means applying the correction to the raw captured window point, before normalization, so
/// it scales through whatever contentRect is current at click time. Deferred - not implemented here.
enum KvmPointerCalibration {
    static let offsetX: CGFloat = 0.0 / 1440.0
    static let offsetY: CGFloat = -123.0 / 3088.0

    /// Applies the calibration nudge to an already-normalized (0...1) pointer coordinate, clamping
    /// back into range so a point already at an edge doesn't get pushed out of the valid 0...1 span.
    static func apply(_ point: CGPoint) -> CGPoint {
        CGPoint(
            x: min(max(point.x + offsetX, 0), 1),
            y: min(max(point.y + offsetY, 0), 1)
        )
    }
}
