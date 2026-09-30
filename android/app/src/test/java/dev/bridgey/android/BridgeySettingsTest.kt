package dev.bridgey.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeySettingsTest {
    @Test
    fun globalSwitchOverridesPerDeviceSwitch() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = true))
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = null))
    }

    @Test
    fun deviceSwitchOverridesEnabledGlobalDefault() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = false))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = true))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = null))
    }

    @Test
    fun featureRequiresBothDevicesToOfferIt() {
        assertTrue(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = true))
        assertFalse(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = false))
        assertFalse(effectiveFeatureAvailable(localEnabled = false, remoteEnabled = true))
    }

    @Test
    fun newerFeaturesAreOffForLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.CALLS))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.PING))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.REMOTE_SCREEN_SHARE))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.STORAGE))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.MEMORY))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.CPU))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.TEMPERATURE))
        assertTrue(featureEnabledByLegacyPeer(BridgeyFeature.BATTERY))
    }

    @Test
    fun telemetryMetricsAreIndependentEntriesNotOneSharedSwitch() {
        // Each telemetry metric is its own BridgeyFeature key, so toggling one in the settings map
        // structurally cannot affect the others - there is no shared "telemetry" flag left to entangle them.
        val allOn = BridgeyFeature.entries.associateWith { true }
        val storageOffOnly = allOn + (BridgeyFeature.STORAGE to false)
        assertFalse(effectiveFeatureEnabled(globalEnabled = storageOffOnly.getValue(BridgeyFeature.STORAGE), deviceEnabled = null))
        assertTrue(effectiveFeatureEnabled(globalEnabled = storageOffOnly.getValue(BridgeyFeature.MEMORY), deviceEnabled = null))
        assertTrue(effectiveFeatureEnabled(globalEnabled = storageOffOnly.getValue(BridgeyFeature.CPU), deviceEnabled = null))
        assertTrue(effectiveFeatureEnabled(globalEnabled = storageOffOnly.getValue(BridgeyFeature.TEMPERATURE), deviceEnabled = null))
    }
}
