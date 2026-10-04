package dev.bridgey.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (HW1, 2026-10-04): the Web Handoff accessibility service started the connection
 * foreground service while Bridgey was turned off; the service stopped itself without calling
 * startForeground() and Android crashed the app. Starting and running share one rule.
 */
class ConnectionServiceStartTest {
    @Test fun startsOnlyWhenBridgeyIsOnForThePrimaryUser() {
        assertTrue(shouldStartConnectionService(isPrimaryUser = true, isBridgeyEnabled = true))
        assertFalse(shouldStartConnectionService(isPrimaryUser = true, isBridgeyEnabled = false))
        assertFalse(shouldStartConnectionService(isPrimaryUser = false, isBridgeyEnabled = true))
        assertFalse(shouldStartConnectionService(isPrimaryUser = false, isBridgeyEnabled = false))
    }
}
