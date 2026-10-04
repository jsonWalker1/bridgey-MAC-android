package dev.bridgey.android

import android.telephony.TelephonyManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the call-state resolution/action logic extracted into CallsController.kt.
 * Moved verbatim from ForwardedNotificationRegistryTest (pre-extraction) plus new coverage
 * for previously untested pure functions (systemCallActionCandidates).
 */
class CallsControllerTest {
    @Test fun readsBoundedCallTypeWithoutPhonePermissions() {
        assertTrue(notificationCallType(1) == "incoming")
        assertTrue(notificationCallType(2) == "ongoing")
        assertTrue(notificationCallType(99) == "unknown")
    }

    @Test fun mapsPlatformPhoneStateWithoutReadingCallHistory() {
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_RINGING) == "incoming")
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_OFFHOOK) == "ongoing")
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_IDLE) == "idle")
    }

    @Test fun exposesRequiredCallStyleActionsWhenPhoneAppDoesNotPublishRegularActions() {
        assertTrue(callStyleFallbackActions("incoming").map { it.title } == listOf("Decline", "Answer"))
        assertTrue(callStyleFallbackActions("ongoing").map { it.title } == listOf("Hang Up"))
        assertTrue(callStyleFallbackActions("screening").map { it.title } == listOf("Hang Up", "Answer"))
        assertTrue(callStyleFallbackActions("unknown").isEmpty())
    }

    @Test fun requiredCallIntentsOverrideAnIncorrectReportedCallType() {
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = true) == "ongoing")
        assertTrue(resolvedNotificationCallType("unknown", hasAnswer = true, hasDecline = false, hasHangUp = true) == "screening")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = true) == "ongoing")
    }

    @Test fun samsungFullScreenCallIntentOverridesItsIncorrectOngoingType() {
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
            ) == "incoming",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = false,
            ) == "ongoing",
        )
    }

    @Test fun telephonyStateOverridesAmbiguousSamsungNotificationSignals() {
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                telephonyCallType = "incoming",
            ) == "incoming",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                telephonyCallType = "ongoing",
            ) == "ongoing",
        )
    }

    @Test fun systemCallControlsMatchTheResolvedCallState() {
        assertTrue(systemCallActionTitles("incoming", 36) == listOf("Decline", "Answer"))
        assertTrue(systemCallActionTitles("ongoing", 36) == listOf("Hang Up"))
        assertTrue(systemCallActionTitles("screening", 36) == listOf("Hang Up", "Answer"))
        assertTrue(systemCallActionTitles("incoming", 26) == listOf("Answer"))
        assertTrue(systemCallActionTitles("unknown", 36).isEmpty())
    }

    @Test fun ongoingCallPostsUseASettleDelayToSuppressTerminalSamsungUpdates() {
        assertTrue(shouldDelayCallPost("ongoing"))
        assertTrue(!shouldDelayCallPost("incoming"))
        assertTrue(!shouldDelayCallPost(null))
    }

    @Test fun systemCallActionCandidatesPairEachTitleWithItsTelecomAction() {
        val incoming = systemCallActionCandidates("incoming", 36)
        assertTrue(incoming.map { it.title } == listOf("Decline", "Answer"))
        assertTrue(incoming.first { it.title == "Decline" }.systemCallAction == SystemCallAction.END)
        assertTrue(incoming.first { it.title == "Answer" }.systemCallAction == SystemCallAction.ANSWER)

        val ongoing = systemCallActionCandidates("ongoing", 36)
        assertTrue(ongoing.single().title == "Hang Up")
        assertTrue(ongoing.single().systemCallAction == SystemCallAction.END)

        assertTrue(systemCallActionCandidates("unknown", 36).isEmpty())
    }

    @Test fun systemCallActionCandidatesCarryNoPendingIntentOrRemoteInput() {
        // System call actions are executed via TelecomManager, never by replaying a
        // phone-app PendingIntent, so candidates must not carry one.
        systemCallActionCandidates("screening", 36).forEach { candidate ->
            assertTrue(candidate.pendingIntent == null)
            assertTrue(candidate.remoteInputs.isEmpty())
        }
    }

}
