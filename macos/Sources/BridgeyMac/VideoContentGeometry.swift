import AVFoundation
import CoreGraphics

/// Presentation mode for scaling the decoded phone-screen video within its container view. FIT (the
/// default) preserves the full source image with letterboxing where needed; FILL crops to cover the
/// container without empty bars.
enum VideoPresentationMode {
    case fit
    case fill
}

/// Pure geometry: window bounds -> video content viewport -> this rectangle. Kept explicit and
/// queryable (rather than left implicit in AVSampleBufferDisplayLayer's videoGravity alone) because
/// a future KVM input handler needs the actual displayed content rectangle - not the full view
/// bounds - to map a click/drag position back to normalized phone-screen coordinates; letterboxing
/// (FIT) or cropping (FILL) both change that mapping relative to the raw view bounds.
enum VideoContentGeometry {
    /// Returns `bounds` unchanged if there's no source size yet (nothing decoded).
    static func contentRect(sourceSize: CGSize?, mode: VideoPresentationMode, in bounds: CGRect) -> CGRect {
        guard let sourceSize, sourceSize.width > 0, sourceSize.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds
        }
        switch mode {
        case .fit:
            return AVMakeRect(aspectRatio: sourceSize, insideRect: bounds)
        case .fill:
            let scale = max(bounds.width / sourceSize.width, bounds.height / sourceSize.height)
            let size = CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)
            let origin = CGPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2)
            return CGRect(origin: origin, size: size)
        }
    }
}
