import Foundation

enum TemperatureStatus: Equatable {
    case known(thermalState: String)
    case unavailable
}

/// ProcessInfo.thermalState is the only real CPU-thermal signal a normal macOS app can get through
/// a public, documented API. Actual CPU temperature in Celsius requires the private/undocumented SMC
/// (System Management Controller) via IOKit - explicitly out of scope here (no private sensor access
/// without explicit approval), so macOS reports thermal state only, never a fabricated Celsius value.
func currentMacTemperatureStatus() -> TemperatureStatus {
    let state: String
    switch ProcessInfo.processInfo.thermalState {
    case .nominal: state = "nominal"
    case .fair: state = "fair"
    case .serious: state = "serious"
    case .critical: state = "critical"
    @unknown default: return .unavailable
    }
    return .known(thermalState: state)
}

/// Shared cross-platform display bucket for ANY thermal state string this app might show (its own
/// platform's values, or a peer's - Android uses a different, larger vocabulary). Purely a display
/// concern: the real platform-native state string is still what's stored/sent; this only picks a
/// label and a severity tier (0=fine .. 3=critical) for consistent, understandable styling.
func thermalDisplayLabel(_ state: String) -> (label: String, tier: Int) {
    switch state {
    case "none", "nominal": return ("Normal", 0)
    case "light", "fair": return ("Fair", 1)
    case "moderate": return ("Warm", 1)
    case "serious", "severe": return ("Serious", 2)
    case "critical", "emergency", "shutdown": return ("Critical", 3)
    default: return (state.prefix(1).uppercased() + state.dropFirst(), 1)
    }
}
