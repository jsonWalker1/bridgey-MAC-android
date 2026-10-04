package dev.bridgey.android

import dev.bridgey.android.FeatureApplicability.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD-2 device roles and applicability: classification, direction, the audit's matrix, separation
 * of capability / platform / role / authorization, and per-device isolation.
 */
class DeviceApplicabilityTest {
    private val androidPhone = DeviceProfile(DevicePlatform.ANDROID, DeviceKind.PHONE)
    private val androidTablet = DeviceProfile(DevicePlatform.ANDROID, DeviceKind.TABLET)
    private val mac = DeviceProfile(DevicePlatform.MACOS, DeviceKind.COMPUTER)
    private val unknown = DeviceProfile(DevicePlatform.UNKNOWN, DeviceKind.UNKNOWN)

    private val allCapabilities = listOf(
        "clipboard", "files", "notifications", "battery", "storage", "memory", "cpu", "temperature", "find_device",
        "ping", "links", "media", "calls", "photo_sync", "remote_screen_share", "kvm_input",
    ).associateWith { true }

    private fun peer(
        profile: DeviceProfile,
        id: String = "20000000-0000-4000-8000-00000000000b",
        capabilities: Map<String, Boolean>? = allCapabilities,
    ) = DeviceDirectoryEntry(id, "peer", true, PeerConnectionState.CONNECTED, capabilities, profile.platform, profile.kind, false)

    private fun applicable(feature: Feature, source: DeviceProfile, target: DeviceProfile) =
        FeatureApplicability.isApplicable(feature, source, target)

    private fun evaluate(feature: Feature, peer: DeviceDirectoryEntry, localIsSource: Boolean, local: DeviceProfile = androidPhone, authorized: (String) -> Boolean = { true }) =
        FeatureApplicability.evaluate(feature, local, peer, localIsSource, authorized)

    // region A. Device classification

    @Test
    fun androidPhoneOwnsTheCellularLine() {
        val profile = DeviceProfile(DevicePlatform.fromHint("android"), DeviceKind.fromHint("phone"))
        assertEquals(androidPhone, profile)
        assertTrue(profile.ownsCellularLine)
    }

    @Test
    fun macIsAComputerWithoutACellularLine() {
        val profile = DeviceProfile(DevicePlatform.fromHint("macos"), DeviceKind.fromHint("computer"))
        assertEquals(mac, profile)
        assertFalse(profile.ownsCellularLine)
    }

    @Test
    fun unknownOrUnavailablePlatformHint() {
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint(null))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint(""))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint("windows"))
    }

    @Test
    fun missingRoleIsUnknownAndOwnsNothing() {
        assertEquals(DeviceKind.UNKNOWN, DeviceKind.fromHint(null))
        assertEquals(DeviceKind.UNKNOWN, DeviceKind.fromHint("watch"))
        assertEquals(DeviceKind.UNKNOWN, DeviceKind.fromHint("unknown"))
        assertFalse(DeviceProfile(DevicePlatform.ANDROID, DeviceKind.UNKNOWN).ownsCellularLine)
        assertFalse(androidTablet.ownsCellularLine)
    }

    @Test
    fun localKindFollowsTheExistingPhoneTabletSplit() {
        assertEquals(DeviceKind.PHONE, DeviceKind.fromHint(LocalDevice.deviceTypeFor(411)))
        assertEquals(DeviceKind.TABLET, DeviceKind.fromHint(LocalDevice.deviceTypeFor(800)))
    }

    @Test
    fun theDirectoryCarriesKindFromHints() {
        val entries = DeviceDirectory.entries(
            trusted = listOf(DeviceDirectory.TrustedDevice("a", "A", "android", "phone")),
            presence = emptyMap(), connectedNames = emptyMap(), state = { PeerConnectionState.CONNECTED },
            capabilities = { null }, routedDeviceId = null,
        )
        assertEquals(androidPhone, entries[0].profile)
    }

    // endregion

    // region B. Direction

    @Test
    fun directionIsSourceToTargetNotSymmetric() {
        assertTrue("Android → macOS", applicable(Feature.NOTIFICATION_MIRROR, androidPhone, mac))
        assertFalse("macOS → Android", applicable(Feature.NOTIFICATION_MIRROR, mac, androidPhone))
        assertTrue("macOS → Android", applicable(Feature.NOTIFICATION_ACTIONS, mac, androidPhone))
        assertFalse("Android → macOS", applicable(Feature.NOTIFICATION_ACTIONS, androidPhone, mac))
        assertFalse("macOS → macOS", applicable(Feature.NOTIFICATION_MIRROR, mac, mac))
        assertFalse("Android → Android", applicable(Feature.NOTIFICATION_MIRROR, androidPhone, androidPhone))
        assertTrue("Android → Android", applicable(Feature.CLIPBOARD, androidPhone, androidPhone))
        assertFalse("macOS → macOS (Universal Clipboard)", applicable(Feature.CLIPBOARD, mac, mac))
    }

    // endregion

    // region C. The audit's matrix

    @Test
    fun auditExamples() {
        assertTrue(applicable(Feature.WEB_HANDOFF, androidPhone, mac))
        assertFalse("macOS Continuity Handoff covers Mac → Mac", applicable(Feature.WEB_HANDOFF, mac, mac))
        assertTrue(applicable(Feature.BOOKS_HANDOFF, androidPhone, mac))
        assertFalse(applicable(Feature.BOOKS_HANDOFF, mac, mac))
        assertFalse(applicable(Feature.CALL_CONTROL, mac, mac))
        assertFalse(applicable(Feature.CALL_STATE, mac, mac))
        assertTrue(applicable(Feature.SCREEN_SHARE, androidPhone, mac))
        assertFalse(applicable(Feature.SCREEN_SHARE, mac, mac))
        assertTrue(applicable(Feature.KVM, mac, androidPhone))
        assertFalse(applicable(Feature.KVM, androidPhone, androidPhone))
        assertFalse(applicable(Feature.KVM, mac, mac))
        assertTrue(applicable(Feature.NOTIFICATION_MIRROR, androidPhone, mac))
        assertFalse(applicable(Feature.NOTIFICATION_MIRROR, mac, mac))
        assertFalse("no Android media remote between Macs", applicable(Feature.MEDIA_REMOTE, mac, mac))
        assertTrue(applicable(Feature.MEDIA_REMOTE, androidPhone, mac))
        assertTrue(applicable(Feature.MAC_PLAYER_CONTROL, androidPhone, mac))
        assertTrue(applicable(Feature.REMOTE_START, mac, androidPhone))
        assertFalse(applicable(Feature.REMOTE_START, androidPhone, mac))
        assertTrue(applicable(Feature.LINK_TO_PHONE, mac, androidPhone))
        assertFalse(applicable(Feature.LINK_TO_PHONE, mac, mac))
        assertTrue(applicable(Feature.PHOTO_SYNC, androidPhone, mac))
        assertFalse(applicable(Feature.PHOTO_SYNC, mac, androidPhone))
    }

    @Test
    fun androidToMacOffersEveryFeatureTheAuditLists() {
        listOf(
            Feature.WEB_HANDOFF, Feature.BOOKS_HANDOFF, Feature.SCREEN_SHARE, Feature.NOTIFICATION_MIRROR,
            Feature.MEDIA_REMOTE, Feature.MAC_PLAYER_CONTROL, Feature.CALL_STATE, Feature.TELEMETRY,
            Feature.CLIPBOARD, Feature.FILES, Feature.PHOTO_SYNC,
        ).forEach { assertTrue("$it", applicable(it, androidPhone, mac)) }
        assertTrue("KVM is Mac → Android (frozen; metadata only)", applicable(Feature.KVM, mac, androidPhone))
    }

    @Test
    fun callsFollowTheCellularLineNotThePlatform() {
        assertTrue(applicable(Feature.CALL_CONTROL, mac, androidPhone))
        assertFalse("a tablet has no cellular line", applicable(Feature.CALL_CONTROL, mac, androidTablet))
        assertFalse("unknown kind is not a phone",
            applicable(Feature.CALL_CONTROL, mac, DeviceProfile(DevicePlatform.ANDROID, DeviceKind.UNKNOWN)))
        assertTrue(applicable(Feature.CALL_STATE, androidPhone, mac))
        assertFalse(applicable(Feature.CALL_STATE, androidTablet, mac))
    }

    @Test
    fun theTablesOfBothPlatformsListTheSameFeatures() {
        assertEquals(18, Feature.entries.size) // must match FeatureApplicability.Feature on macOS
    }

    // endregion

    // region D. Separation of concerns

    @Test
    fun capabilityAloneDoesNotImplyApplicability() {
        // An Android peer advertising `notifications` still cannot mirror notifications to this phone.
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, evaluate(Feature.NOTIFICATION_MIRROR, peer(androidPhone), localIsSource = false))
    }

    @Test
    fun platformAloneDoesNotImplyAuthorization() {
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED, evaluate(Feature.CLIPBOARD, peer(mac), localIsSource = true) { false })
    }

    @Test
    fun authorizationDeniesAnOtherwiseApplicableOperation() {
        // This phone is the target of Remote Start from a Mac: its own opt-in grant decides.
        assertEquals(FeatureApplicabilityResult.OFFERED, evaluate(Feature.REMOTE_START, peer(mac), localIsSource = false))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED,
            evaluate(Feature.REMOTE_START, peer(mac), localIsSource = false) { it != "remote_screen_share" })
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED,
            evaluate(Feature.KVM, peer(mac), localIsSource = false) { it != "kvm_input" })
        assertTrue(FeatureApplicability.rule(Feature.REMOTE_START).explicitAuthorization)
        assertTrue(FeatureApplicability.rule(Feature.KVM).explicitAuthorization)
    }

    @Test
    fun unknownPlatformOrRoleDoesNotMakeAFeatureAvailable() {
        Feature.entries.filter { FeatureApplicability.rule(it).directions != null }.forEach {
            assertFalse("$it to an unknown device", applicable(it, androidPhone, unknown))
            assertFalse("$it from an unknown device", applicable(it, unknown, androidPhone))
        }
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, evaluate(Feature.WEB_HANDOFF, peer(unknown), localIsSource = true))
        // Deliberately platform-independent features stay usable, still behind capability + grant.
        assertTrue(applicable(Feature.FILES, unknown, androidPhone))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED, evaluate(Feature.FILES, peer(unknown), localIsSource = true) { false })
    }

    @Test
    fun missingCapabilityIsNotTreatedAsGranted() {
        assertEquals(FeatureApplicabilityResult.PEER_LACKS_CAPABILITY,
            evaluate(Feature.CLIPBOARD, peer(mac, capabilities = emptyMap()), localIsSource = true))
        assertEquals(FeatureApplicabilityResult.PEER_LACKS_CAPABILITY,
            evaluate(Feature.CLIPBOARD, peer(mac, capabilities = null), localIsSource = true))
    }

    @Test
    fun telemetryNeedsOneMetricThatIsBothGrantedByThePeerAndLocally() {
        val onlyBattery = peer(mac, capabilities = mapOf("battery" to true, "cpu" to false))
        assertEquals(FeatureApplicabilityResult.OFFERED, evaluate(Feature.TELEMETRY, onlyBattery, localIsSource = true))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED, evaluate(Feature.TELEMETRY, onlyBattery, localIsSource = true) { it == "cpu" })
    }

    // endregion

    // region E. Multi-device isolation

    @Test
    fun twoConnectedDevicesGetIndependentResults() {
        val workMac = peer(mac, id = "10000000-0000-4000-8000-00000000000a")
        val otherPhone = peer(androidPhone, id = "20000000-0000-4000-8000-00000000000b")
        assertEquals(FeatureApplicabilityResult.OFFERED, evaluate(Feature.WEB_HANDOFF, workMac, localIsSource = true))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, evaluate(Feature.WEB_HANDOFF, otherPhone, localIsSource = true))

        // Authorization is evaluated for the device asked about, not for a routed/active one.
        val grants = mapOf(workMac.deviceId to setOf("files"), otherPhone.deviceId to emptySet())
        fun files(entry: DeviceDirectoryEntry) = evaluate(Feature.FILES, entry, localIsSource = true) { grants[entry.deviceId]?.contains(it) == true }
        assertEquals(FeatureApplicabilityResult.OFFERED, files(workMac))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED, files(otherPhone))
    }

    // endregion
}
