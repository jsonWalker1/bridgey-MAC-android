package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-4 device list: presentation of the directory, selection as UI state, explicit targets. */
class DeviceListTest {
    private val phone = "10000000-0000-4000-8000-00000000000a"
    private val mac = "20000000-0000-4000-8000-00000000000b"
    private val air = "30000000-0000-4000-8000-00000000000c"

    private fun entry(id: String, name: String, platform: DevicePlatform, kind: DeviceKind, connected: Boolean = true, routed: Boolean = false) =
        DeviceDirectoryEntry(
            id, name, true, if (connected) PeerConnectionState.CONNECTED else PeerConnectionState.OFFLINE,
            if (connected) mapOf("ping" to true, "find_device" to true) else null, platform, kind, routed,
        )

    // As the directory returns them: sorted by name.
    private val three = listOf(
        entry(air, "MacBook Air", DevicePlatform.MACOS, DeviceKind.COMPUTER),
        entry(phone, "S23 Ultra", DevicePlatform.ANDROID, DeviceKind.PHONE),
        entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER),
    )

    @Test
    fun emptyDirectory() {
        assertEquals(emptyList<DeviceListItem>(), DeviceList.items(emptyList()))
        assertNull(DeviceList.reconcile(phone, emptyList()))
        assertEquals(DeviceTarget.None, DeviceList.target(null, emptyList()))
    }

    @Test
    fun oneDevice() {
        val items = DeviceList.items(listOf(entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER)))
        assertEquals(listOf("Tomášův MacBook"), items.map { it.name })
        assertEquals("macOS · Computer", items[0].detail)
        assertEquals("a single device needs no choice", mac, DeviceList.reconcile(null, items))
    }

    @Test
    fun twoAndThreeDevicesAreAllVisible() {
        assertEquals(2, DeviceList.items(three.take(2)).size)
        val items = DeviceList.items(three)
        assertEquals(listOf("MacBook Air", "S23 Ultra", "Tomášův MacBook"), items.map { it.name })
        assertEquals(listOf("macOS · Computer", "Android · Phone", "macOS · Computer"), items.map { it.detail })
        assertNull("several devices: nothing is chosen for the user", DeviceList.reconcile(null, items))
    }

    @Test
    fun orderingIsDeterministicConnectedFirst() {
        val entries = three.toMutableList().also { it[0] = entry(air, "MacBook Air", DevicePlatform.MACOS, DeviceKind.COMPUTER, connected = false) }
        val all = DeviceList.items(entries, connectedOnly = false)
        assertEquals(listOf(phone, mac, air), all.map { it.deviceId })
        assertEquals("same input, same order", all, DeviceList.items(entries, connectedOnly = false))
        assertEquals("the list shows connected devices", listOf(phone, mac), DeviceList.items(entries).map { it.deviceId })
    }

    @Test
    fun selectingAOrB() {
        val items = DeviceList.items(three)
        assertEquals(phone, DeviceList.reconcile(phone, items))
        assertEquals(mac, DeviceList.reconcile(mac, items))
    }

    @Test
    fun selectedDeviceDisappears() {
        assertNull("two remain: the selection is cleared, not reinterpreted",
            DeviceList.reconcile(phone, DeviceList.items(three.filter { it.deviceId != phone })))
        val lastOne = DeviceList.items(listOf(entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER)))
        assertEquals("one remains: it is the only possible target", mac, DeviceList.reconcile(phone, lastOne))
    }

    @Test
    fun anotherDeviceRemainsUsableWhenOneDisconnects() {
        val afterDisconnect = DeviceList.items(three.filter { it.deviceId != air })
        assertEquals(mac, DeviceList.reconcile(mac, afterDisconnect))
        assertEquals(DeviceTarget.Device(mac), DeviceList.target(mac, afterDisconnect.map { it.deviceId }))
    }

    @Test
    fun reconnectDoesNotDuplicateTheEntry() {
        val offline = three.map { if (it.deviceId == phone) entry(phone, "S23 Ultra", DevicePlatform.ANDROID, DeviceKind.PHONE, connected = false) else it }
        assertEquals(2, DeviceList.items(offline).size)
        val back = DeviceList.items(three)
        assertEquals(3, back.size)
        assertEquals(1, back.count { it.deviceId == phone })
    }

    @Test
    fun profileUpdateRefreshesThePresentation() {
        assertEquals("", DeviceList.items(listOf(entry(mac, "Mac", DevicePlatform.UNKNOWN, DeviceKind.UNKNOWN)))[0].detail)
        val after = DeviceList.items(listOf(entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER)))[0]
        assertEquals("Tomášův MacBook", after.name)
        assertEquals("macOS · Computer", after.detail)
    }

    @Test
    fun pingAndFindTargetsStayExplicit() {
        val eligible = listOf(mac, air)
        assertEquals(DeviceTarget.Device(air), DeviceList.target(air, eligible))
        assertEquals("no selection: ask, never guess", DeviceTarget.Choose(eligible), DeviceList.target(null, eligible))
        assertEquals("an ineligible selection is not silently replaced", DeviceTarget.Choose(eligible), DeviceList.target(phone, eligible))
        assertEquals(DeviceTarget.Device(mac), DeviceList.target(null, listOf(mac)))
    }

    @Test
    fun noActiveSessionFallback() {
        val entries = three.map { if (it.deviceId == mac) entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER, routed = true) else it }
        val eligible = DeviceList.items(entries).map { it.deviceId }
        assertEquals(DeviceTarget.Choose(eligible), DeviceList.target(null, eligible))
        assertNull(DeviceList.reconcile(null, DeviceList.items(entries)))
    }

    // region MD-4b selected-device context (peer-centric)

    @Test
    fun singleDeviceIsBothSelectedAndRoutedSoLegacyFeaturesApply() {
        val items = DeviceList.items(listOf(entry(mac, "Tomášův MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER, routed = true)))
        val context = SelectedDeviceContext.make(items, DeviceList.reconcile(null, items), mac)
        assertEquals(mac, context.selected?.deviceId)
        assertTrue("one peer keeps today's full card", context.legacyFeaturesApply)
        assertNull(context.legacyFeaturesUseOtherPeer)
    }

    @Test
    fun selectedAndRoutedCanDifferAndLegacyStateIsNotShownUnderTheSelectedPeer() {
        val context = SelectedDeviceContext.make(DeviceList.items(three), air, mac)
        assertEquals("MacBook Air", context.selected?.name)
        assertFalse("the routed Mac's legacy state never appears under MacBook Air", context.legacyFeaturesApply)
        assertEquals("Tomášův MacBook", context.legacyFeaturesUseOtherPeer?.name)
    }

    @Test
    fun noSelectionIsNotReplacedByTheRoutedPeer() {
        val context = SelectedDeviceContext.make(DeviceList.items(three), null, mac)
        assertNull("the routed peer is never shown as the selected one", context.selected)
        assertFalse(context.legacyFeaturesApply)
        assertEquals(mac, context.legacyFeaturesUseOtherPeer?.deviceId)
    }

    @Test
    fun aSelectedPeerThatDisconnectedIsNotShown() {
        assertNull(SelectedDeviceContext.make(DeviceList.items(three.filter { it.deviceId != air }), air, mac).selected)
    }

    @Test
    fun selectionNeverChangesRouting() {
        for (selected in listOf(phone, air, mac)) {
            val context = SelectedDeviceContext.make(DeviceList.items(three), selected, mac)
            assertEquals(mac, context.routed?.deviceId)
            assertEquals(selected, context.selected?.deviceId)
        }
    }

    // endregion
}
