import Foundation

/// BRIDGEY KVM KEYBOARD V1. Maps macOS key-event semantics onto the Android `KeyEvent.KEYCODE_*`
/// space the paired phone's `BridgeyInputMethodService.injectKey` actually understands. Deliberately
/// takes only primitive values (a `UInt16` Carbon/HIToolbox virtual keyCode, plain `Bool` modifier
/// flags, a `Character`) rather than `NSEvent`/`NSEvent.ModifierFlags` directly, so this file has no
/// AppKit dependency and is fully unit-testable - the same rationale `KvmGestureRecognizer` documents
/// for itself.
///
/// Two separate, deliberately non-overlapping tables: `MacKeyCode` is what ScreenShareWindow reads off
/// an incoming `NSEvent.keyCode` to decide "is this a special key at all"; `AndroidKeyCode` is what
/// gets put on the wire once that decision is made. Values are the well-known stable integer constants
/// from each platform's own headers (Carbon's `Events.h` / `android.view.KeyEvent`), not guessed.
enum MacKeyCode {
    static let returnKey: UInt16 = 36
    static let tab: UInt16 = 48
    static let delete: UInt16 = 51 // Backspace
    static let escape: UInt16 = 53
    static let forwardDelete: UInt16 = 117 // Delete (fn+Backspace on a Mac laptop keyboard)
    static let leftArrow: UInt16 = 123
    static let rightArrow: UInt16 = 124
    static let downArrow: UInt16 = 125
    static let upArrow: UInt16 = 126
    static let home: UInt16 = 115
    static let end: UInt16 = 119
    static let pageUp: UInt16 = 116
    static let pageDown: UInt16 = 121
}

enum AndroidKeyCode {
    static let enter: Int32 = 66
    static let del: Int32 = 67 // Backspace
    static let forwardDel: Int32 = 112 // Delete
    static let tab: Int32 = 61
    static let escape: Int32 = 111
    static let dpadUp: Int32 = 19
    static let dpadDown: Int32 = 20
    static let dpadLeft: Int32 = 21
    static let dpadRight: Int32 = 22
    static let moveHome: Int32 = 122
    static let moveEnd: Int32 = 123
    static let pageUp: Int32 = 92
    static let pageDown: Int32 = 93
    /// BRIDGEY KVM KEYBOARD V1: standalone modifier press/release (ScreenShareWindow's
    /// `.flagsChanged` handling) always uses the LEFT-variant keycode - see that call site's doc
    /// comment for why no left/right distinction is preserved.
    static let shiftLeft: Int32 = 59
    static let ctrlLeft: Int32 = 113
    static let altLeft: Int32 = 57
    static let metaLeft: Int32 = 117
}

enum KvmKeyMapping {
    /// The complete BRIDGEY KVM KEYBOARD V1 special-key table: keys with real Android `KeyEvent`
    /// semantics that must NOT be sent as text (see this feature's design directive - "do not blindly
    /// convert every macOS key into text"). Returns `nil` for any keyCode outside this table, which
    /// callers treat as "not a special key" and route down the text/Ctrl-chord path instead.
    static func androidKeyCode(forSpecialMacKeyCode macKeyCode: UInt16) -> Int32? {
        switch macKeyCode {
        case MacKeyCode.returnKey: return AndroidKeyCode.enter
        case MacKeyCode.tab: return AndroidKeyCode.tab
        case MacKeyCode.delete: return AndroidKeyCode.del
        case MacKeyCode.forwardDelete: return AndroidKeyCode.forwardDel
        case MacKeyCode.escape: return AndroidKeyCode.escape
        case MacKeyCode.leftArrow: return AndroidKeyCode.dpadLeft
        case MacKeyCode.rightArrow: return AndroidKeyCode.dpadRight
        case MacKeyCode.upArrow: return AndroidKeyCode.dpadUp
        case MacKeyCode.downArrow: return AndroidKeyCode.dpadDown
        case MacKeyCode.home: return AndroidKeyCode.moveHome
        case MacKeyCode.end: return AndroidKeyCode.moveEnd
        case MacKeyCode.pageUp: return AndroidKeyCode.pageUp
        case MacKeyCode.pageDown: return AndroidKeyCode.pageDown
        default: return nil
        }
    }

    /// For a Control-chord (Ctrl+A, Ctrl+C, ...): maps a base letter/digit character to its Android
    /// `KEYCODE_A`..`KEYCODE_Z` (29...54) / `KEYCODE_0`..`KEYCODE_9` (7...16) constant. Callers pass
    /// `NSEvent.charactersIgnoringModifiers` (NOT `.characters` - Control transforms that into ASCII
    /// control codes, e.g. Ctrl+C -> "\u{3}", which cannot be mapped back to a letter) already
    /// lowercased, so case does not need to be handled here. Returns `nil` for anything that isn't a
    /// single ASCII letter or digit (e.g. Ctrl+Space, Ctrl+[) - explicitly unsupported rather than
    /// guessed, per this feature's own "no meaningful Android equivalent -> document it, don't invent
    /// behavior" directive.
    static func androidKeyCode(forLetterOrDigit character: Character) -> Int32? {
        if let ascii = character.asciiValue, ascii >= UInt8(ascii: "a"), ascii <= UInt8(ascii: "z") {
            return Int32(29 + (ascii - UInt8(ascii: "a")))
        }
        if let ascii = character.asciiValue, ascii >= UInt8(ascii: "0"), ascii <= UInt8(ascii: "9") {
            return Int32(7 + (ascii - UInt8(ascii: "0")))
        }
        return nil
    }

    /// Builds the `KeyModifier` wire bitmask (InputTransport.swift) from plain booleans rather than
    /// `NSEvent.ModifierFlags` directly, keeping this whole file AppKit-free.
    static func modifierBits(shift: Bool, control: Bool, option: Bool, command: Bool) -> UInt8 {
        var bits: UInt8 = 0
        if shift { bits |= KeyModifier.shift }
        if control { bits |= KeyModifier.control }
        if option { bits |= KeyModifier.alt }
        if command { bits |= KeyModifier.meta }
        return bits
    }
}
