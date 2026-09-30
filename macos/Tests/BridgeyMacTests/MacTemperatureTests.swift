import XCTest
@testable import BridgeyMac

final class MacTemperatureTests: XCTestCase {
    func testCurrentMacTemperatureStatusIsPlausible() {
        // Smoke test against the real host - ProcessInfo.thermalState always returns a value on a
        // running process, so this should always be .known in practice; still tolerate .unavailable
        // for the @unknown default case rather than asserting a specific state.
        switch currentMacTemperatureStatus() {
        case .known(let state):
            XCTAssertFalse(state.isEmpty)
        case .unavailable:
            break
        }
    }

    func testThermalDisplayLabelMapsAndroidAndMacVocabulariesToSharedTiers() {
        XCTAssertEqual(thermalDisplayLabel("none").label, "Normal")
        XCTAssertEqual(thermalDisplayLabel("none").tier, 0)
        XCTAssertEqual(thermalDisplayLabel("nominal").label, "Normal")
        XCTAssertEqual(thermalDisplayLabel("nominal").tier, 0)
        XCTAssertEqual(thermalDisplayLabel("light").tier, 1)
        XCTAssertEqual(thermalDisplayLabel("fair").tier, 1)
        XCTAssertEqual(thermalDisplayLabel("moderate").label, "Warm")
        XCTAssertEqual(thermalDisplayLabel("serious").tier, 2)
        XCTAssertEqual(thermalDisplayLabel("severe").tier, 2)
        XCTAssertEqual(thermalDisplayLabel("critical").tier, 3)
        XCTAssertEqual(thermalDisplayLabel("emergency").tier, 3)
        XCTAssertEqual(thermalDisplayLabel("shutdown").tier, 3)
    }

    func testThermalDisplayLabelFallsBackGracefullyForUnknownState() {
        let (label, tier) = thermalDisplayLabel("weird")
        XCTAssertEqual(label, "Weird")
        XCTAssertEqual(tier, 1)
    }
}
