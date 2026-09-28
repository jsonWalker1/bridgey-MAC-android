import XCTest
@testable import BridgeyMac

/// BRIDGEY KVM KEYBOARD V1: unit tests for KvmKeyMapping's pure lookup tables/helpers - no
/// NSEvent/AppKit dependency needed, mirroring the pattern KvmGestureRecognizerTests already uses.
final class KvmKeyMappingTests: XCTestCase {
    // MARK: - Special keys

    func testEnterMapsToAndroidEnter() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.returnKey), AndroidKeyCode.enter)
    }

    func testBackspaceMapsToAndroidDel() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.delete), AndroidKeyCode.del)
    }

    func testForwardDeleteMapsToAndroidForwardDel() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.forwardDelete), AndroidKeyCode.forwardDel)
    }

    func testTabMapsToAndroidTab() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.tab), AndroidKeyCode.tab)
    }

    func testEscapeMapsToAndroidEscape() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.escape), AndroidKeyCode.escape)
    }

    func testAllFourArrowsMapToTheirDpadEquivalents() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.leftArrow), AndroidKeyCode.dpadLeft)
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.rightArrow), AndroidKeyCode.dpadRight)
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.upArrow), AndroidKeyCode.dpadUp)
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.downArrow), AndroidKeyCode.dpadDown)
    }

    func testHomeAndEndMapToMoveHomeAndMoveEnd() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.home), AndroidKeyCode.moveHome)
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.end), AndroidKeyCode.moveEnd)
    }

    func testPageUpAndPageDownMapCorrectly() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.pageUp), AndroidKeyCode.pageUp)
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: MacKeyCode.pageDown), AndroidKeyCode.pageDown)
    }

    func testAnOrdinaryLetterKeyCodeIsNotInTheSpecialKeyTable() {
        // 'A' key's Mac virtual keyCode is 0 - not part of the special-key table, so plain letters
        // route through TEXT/Ctrl-chord instead, never KEY.
        XCTAssertNil(KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: 0))
    }

    // MARK: - Ctrl-chord letter/digit mapping

    func testCtrlAMapsToKeycodeA() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "a"), 29)
    }

    func testCtrlCMapsToKeycodeC() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "c"), 31)
    }

    func testCtrlVMapsToKeycodeV() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "v"), 50)
    }

    func testCtrlXMapsToKeycodeX() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "x"), 52)
    }

    func testCtrlZMapsToKeycodeZ() {
        XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "z"), 54)
    }

    func testDigitZeroThroughNineMapSequentially() {
        for (index, digit) in "0123456789".enumerated() {
            XCTAssertEqual(KvmKeyMapping.androidKeyCode(forLetterOrDigit: digit), Int32(7 + index))
        }
    }

    func testNonLetterNonDigitCharacterHasNoMapping() {
        XCTAssertNil(KvmKeyMapping.androidKeyCode(forLetterOrDigit: " "))
        XCTAssertNil(KvmKeyMapping.androidKeyCode(forLetterOrDigit: "["))
    }

    // MARK: - Modifier bitmask

    func testNoModifiersProducesZeroBits() {
        XCTAssertEqual(KvmKeyMapping.modifierBits(shift: false, control: false, option: false, command: false), 0)
    }

    func testEachModifierSetsItsOwnDistinctBit() {
        XCTAssertEqual(KvmKeyMapping.modifierBits(shift: true, control: false, option: false, command: false), KeyModifier.shift)
        XCTAssertEqual(KvmKeyMapping.modifierBits(shift: false, control: true, option: false, command: false), KeyModifier.control)
        XCTAssertEqual(KvmKeyMapping.modifierBits(shift: false, control: false, option: true, command: false), KeyModifier.alt)
        XCTAssertEqual(KvmKeyMapping.modifierBits(shift: false, control: false, option: false, command: true), KeyModifier.meta)
    }

    func testCombinedModifiersOrTheirBitsTogether() {
        let combined = KvmKeyMapping.modifierBits(shift: true, control: true, option: true, command: true)
        XCTAssertEqual(combined, KeyModifier.shift | KeyModifier.control | KeyModifier.alt | KeyModifier.meta)
    }
}
