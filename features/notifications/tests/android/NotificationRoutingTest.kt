package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRoutingTest {
    private val m4 = "mac-m4"
    private val work = "mac-work"

    // Target selection

    @Test fun preferredEligibleMacIsTheTarget() {
        assertEquals(m4, notificationTarget(preferredDeviceId = m4, routedDeviceId = work, eligible = listOf(work, m4)))
    }

    @Test fun preferredMacOfflineOrIneligibleMeansNoTargetEvenWithAnotherMacConnected() {
        assertNull(notificationTarget(preferredDeviceId = m4, routedDeviceId = work, eligible = listOf(work)))
        assertNull(notificationTarget(preferredDeviceId = m4, routedDeviceId = m4, eligible = emptyList()))
    }

    @Test fun withoutPreferredMacTheRoutedMacIsTheTargetOnlyWhenEligible() {
        assertEquals(work, notificationTarget(preferredDeviceId = null, routedDeviceId = work, eligible = listOf(m4, work)))
        assertNull(notificationTarget(preferredDeviceId = null, routedDeviceId = work, eligible = listOf(m4)))
        assertNull(notificationTarget(preferredDeviceId = null, routedDeviceId = null, eligible = listOf(m4)))
    }

    @Test fun commandsAreAcceptedOnlyFromTheTarget() {
        assertTrue(acceptsNotificationCommand(sender = m4, target = m4))
        assertFalse(acceptsNotificationCommand(sender = work, target = m4))
        assertFalse(acceptsNotificationCommand(sender = m4, target = null))
        assertFalse(acceptsNotificationCommand(sender = null, target = null))
    }

    // Reconciliation and cleanup decisions

    private val m4Session = Any()
    private val workSession = Any()

    @Test fun newTargetIsReconciledOnceAndOtherMacsAreClearedOnce() {
        val tracker = NotificationTargetTracker()
        val eligible = mapOf(m4 to m4Session, work to workSession)
        assertEquals(NotificationTargetTracker.Step(reconcile = m4, clear = listOf(work)), tracker.update(m4, eligible))
        assertEquals(NotificationTargetTracker.Step(reconcile = null, clear = emptyList()), tracker.update(m4, eligible))
    }

    @Test fun committedTargetFollowsTheLastEvaluation() {
        val tracker = NotificationTargetTracker()
        assertNull(tracker.target)
        tracker.update(m4, mapOf(m4 to m4Session, work to workSession))
        assertEquals(m4, tracker.target)
        tracker.update(null, mapOf(work to workSession))
        assertNull(tracker.target)
    }

    @Test fun switchingTargetReconcilesTheNewOneAndClearsThePreviousOne() {
        val tracker = NotificationTargetTracker()
        val eligible = mapOf(m4 to m4Session, work to workSession)
        tracker.update(m4, eligible)
        assertEquals(NotificationTargetTracker.Step(reconcile = work, clear = listOf(m4)), tracker.update(work, eligible))
    }

    @Test fun targetReconnectIsReconciledAgain() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session))
        assertEquals(NotificationTargetTracker.Step(null, emptyList()), tracker.update(null, emptyMap()))
        assertEquals(NotificationTargetTracker.Step(m4, emptyList()), tracker.update(m4, mapOf(m4 to Any())))
    }

    @Test fun targetSessionReplacedWithoutAnInterveningUpdateIsReconciledAgain() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session))
        assertEquals(m4, tracker.update(m4, mapOf(m4 to Any())).reconcile)
    }

    @Test fun preferredMacGoingOfflineReconcilesNobodyAndClearsTheOtherMac() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session, work to workSession))
        // Preferred M4 offline: no target, no fallback; the work Mac was already cleared.
        assertEquals(NotificationTargetTracker.Step(null, emptyList()), tracker.update(null, mapOf(work to workSession)))
    }

    @Test fun nonTargetMacIsClearedAgainAfterItReconnects() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session, work to workSession))
        tracker.update(m4, mapOf(m4 to m4Session))
        assertEquals(listOf(work), tracker.update(m4, mapOf(m4 to m4Session, work to Any())).clear)
    }

    @Test fun previousTargetPreferredWhileOfflineIsClearedWhenItReturns() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session, work to workSession))
        tracker.update(null, mapOf(work to workSession)) // M4 offline
        tracker.update(work, mapOf(work to workSession)) // user prefers the work Mac now
        assertEquals(NotificationTargetTracker.Step(null, listOf(m4)), tracker.update(work, mapOf(work to workSession, m4 to Any())))
    }

    @Test fun formerlyClearedMacThatBecomesTargetIsReconciled() {
        val tracker = NotificationTargetTracker()
        val eligible = mapOf(m4 to m4Session, work to workSession)
        tracker.update(m4, eligible)
        tracker.update(work, eligible)
        assertEquals(NotificationTargetTracker.Step(m4, listOf(work)), tracker.update(m4, eligible))
    }
}
