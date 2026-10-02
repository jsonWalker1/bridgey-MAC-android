package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTextTest {
    @Test fun noWifiReplacesNotConnectedWhenAMacIsPaired() {
        assertEquals(NO_WIFI_STATUS, connectionStatusText(PairingState.Idle, wifiAvailable = false, hasTrustedMac = true))
        assertEquals(NO_WIFI_STATUS, connectionStatusText(PairingState.Failed("x"), wifiAvailable = false, hasTrustedMac = true))
        assertEquals(NO_WIFI_STATUS, connectionStatusText(PairingState.Connecting("Mac"), wifiAvailable = false, hasTrustedMac = true))
    }

    @Test fun withoutAPairedMacNoWifiIsNotMentioned() {
        assertEquals("Not connected · waiting for paired device", connectionStatusText(PairingState.Idle, wifiAvailable = false, hasTrustedMac = false))
    }

    @Test fun liveSessionAndPairingPromptAreNeverHidden() {
        assertEquals("Connected to Mac", connectionStatusText(PairingState.Connected("id", "Mac"), wifiAvailable = false, hasTrustedMac = true))
        assertEquals("Pairing confirmation required", connectionStatusText(PairingState.Verification("Mac", "123"), wifiAvailable = false, hasTrustedMac = true))
    }

    @Test fun withWifiTheUsualStatusesStay() {
        assertEquals("Connecting to Mac…", connectionStatusText(PairingState.Connecting("Mac"), wifiAvailable = true, hasTrustedMac = true))
        assertEquals("Not connected", connectionStatusText(PairingState.Failed("x"), wifiAvailable = true, hasTrustedMac = true))
    }
}
