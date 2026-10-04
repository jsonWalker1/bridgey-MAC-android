import CryptoKit
import Foundation
import Security
import XCTest
@testable import BridgeyMac

/// R1: the device identity must never silently change because of a Keychain error. Only a
/// confirmed "no identity stored yet" (errSecItemNotFound) may create a new key.
final class MacIdentityTests: XCTestCase {
    /// Scripted Keychain: records every call so a test can prove nothing was deleted or replaced.
    private final class ScriptedKeychain {
        var copyResult: (OSStatus, Data?)
        var addStatus: OSStatus = errSecSuccess
        private(set) var copies = 0
        private(set) var added: [Data] = []

        init(copy: (OSStatus, Data?)) { copyResult = copy }

        // Strong captures on purpose: the scripted keychain must outlive the identity under test.
        var access: MacIdentity.KeychainAccess {
            MacIdentity.KeychainAccess(
                copy: {
                    self.copies += 1
                    return self.copyResult
                },
                add: { data in
                    self.added.append(data)
                    return self.addStatus
                }
            )
        }
    }

    func testExistingIdentityIsLoaded() {
        let stored = P256.Signing.PrivateKey()
        let keychain = ScriptedKeychain(copy: (errSecSuccess, stored.rawRepresentation))
        let identity = MacIdentity(keychain: keychain.access)
        XCTAssertEqual(identity.status, .available)
        XCTAssertEqual(identity.publicKey, stored.publicKey.x963Representation.base64EncodedString())
        XCTAssertTrue(keychain.added.isEmpty)
    }

    func testOnlyNotFoundCreatesTheFirstIdentity() {
        let keychain = ScriptedKeychain(copy: (errSecItemNotFound, nil))
        let identity = MacIdentity(keychain: keychain.access)
        XCTAssertEqual(identity.status, .available)
        XCTAssertEqual(keychain.added.count, 1)
        let created = try? P256.Signing.PrivateKey(rawRepresentation: keychain.added[0])
        XCTAssertEqual(identity.publicKey, created?.publicKey.x963Representation.base64EncodedString())
    }

    func testTransientKeychainErrorNeverReplacesTheIdentity() {
        for status in [errSecInteractionNotAllowed, errSecAuthFailed, errSecNotAvailable, errSecIO, errSecParam] {
            let keychain = ScriptedKeychain(copy: (status, nil))
            let identity = MacIdentity(keychain: keychain.access)
            XCTAssertEqual(identity.status, .unavailable(status), "status \(status)")
            XCTAssertTrue(keychain.added.isEmpty, "status \(status) must not create a key")
            XCTAssertNil(identity.publicKey, "no identity is presented while unavailable")
            XCTAssertNil(identity.sign(Data("transcript".utf8)), "nothing is signed while unavailable")
        }
    }

    func testUnreadableStoredKeyIsNotReplaced() {
        let keychain = ScriptedKeychain(copy: (errSecSuccess, Data("not a key".utf8)))
        let identity = MacIdentity(keychain: keychain.access)
        XCTAssertEqual(identity.status, .unreadable)
        XCTAssertTrue(keychain.added.isEmpty, "a present but unreadable key is never overwritten")
        XCTAssertNil(identity.publicKey)
    }

    func testFailedFirstSaveIsUnavailableNotAnUnsavedIdentity() {
        let keychain = ScriptedKeychain(copy: (errSecItemNotFound, nil))
        keychain.addStatus = errSecInteractionNotAllowed
        let identity = MacIdentity(keychain: keychain.access)
        XCTAssertEqual(identity.status, .unavailable(errSecInteractionNotAllowed))
        XCTAssertNil(identity.publicKey, "an identity that could not be persisted is never used")
    }

    func testReloadRecoversTheSameStoredIdentityAfterATransientError() {
        let stored = P256.Signing.PrivateKey()
        let keychain = ScriptedKeychain(copy: (errSecInteractionNotAllowed, nil))
        let identity = MacIdentity(keychain: keychain.access)
        XCTAssertFalse(identity.isAvailable)

        keychain.copyResult = (errSecSuccess, stored.rawRepresentation) // Keychain unlocked
        XCTAssertTrue(identity.reloadIfUnavailable())
        XCTAssertEqual(identity.publicKey, stored.publicKey.x963Representation.base64EncodedString())
        XCTAssertTrue(keychain.added.isEmpty)

        let copiesBefore = keychain.copies
        XCTAssertTrue(identity.reloadIfUnavailable(), "an available identity is not reloaded")
        XCTAssertEqual(keychain.copies, copiesBefore)
    }

    func testSignatureVerifiesWithThePublishedKey() throws {
        let identity = MacIdentity(keychain: ScriptedKeychain(copy: (errSecItemNotFound, nil)).access)
        let data = Data("bridgey-auth-v1".utf8)
        let signature = try XCTUnwrap(identity.sign(data))
        let publicKey = try P256.Signing.PublicKey(x963Representation: XCTUnwrap(Data(base64Encoded: XCTUnwrap(identity.publicKey))))
        let der = try P256.Signing.ECDSASignature(derRepresentation: XCTUnwrap(Data(base64Encoded: signature)))
        XCTAssertTrue(publicKey.isValidSignature(der, for: data))
    }

    /// Real Keychain (test-only service): the identity survives a reload unchanged.
    func testRealKeychainIdentityIsStableAcrossLoads() {
        let service = "dev.bridgey.tests.identity.\(UUID().uuidString)"
        let first = MacIdentity(service: service)
        defer { MacIdentity.deleteForTesting(service: service) }
        XCTAssertTrue(first.isAvailable)
        let second = MacIdentity(service: service)
        XCTAssertEqual(second.publicKey, first.publicKey)
    }
}
