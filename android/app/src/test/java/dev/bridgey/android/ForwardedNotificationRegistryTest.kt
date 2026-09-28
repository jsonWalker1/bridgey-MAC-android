package dev.bridgey.android

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedNotificationRegistryTest {
    // BRIDGEY NOTIFICATION++ PHASE 2: conversation identity, derived from real WhatsApp device
    // evidence (dumpsys notification) - shortcutId stays stable across messages in the same
    // conversation and differs between conversations; apps without a shortcutId (eM Client,
    // systemui) fall back to the existing per-instance systemKey identity.

    @Test fun sameConversationShortcutIdProducesTheSameSeedAcrossDifferentMessages() {
        val first = notificationIdentitySeed("com.whatsapp", "112575488491769@lid", systemKey = "0|com.whatsapp|1|tagA|10365")
        val second = notificationIdentitySeed("com.whatsapp", "112575488491769@lid", systemKey = "0|com.whatsapp|1|tagB|10365")

        assertTrue(first == second)
    }

    @Test fun differentShortcutIdsRemainDistinctConversations() {
        val karolina = notificationIdentitySeed("com.whatsapp", "112575488491769@lid", systemKey = "irrelevant")
        val petr = notificationIdentitySeed("com.whatsapp", "998877665544332@lid", systemKey = "irrelevant")

        assertFalse(karolina == petr)
    }

    @Test fun sameShortcutIdInDifferentAppsRemainDistinct() {
        val whatsapp = notificationIdentitySeed("com.whatsapp", "12345@lid", systemKey = "irrelevant")
        val otherApp = notificationIdentitySeed("com.other.messenger", "12345@lid", systemKey = "irrelevant")

        assertFalse(whatsapp == otherApp)
    }

    @Test fun missingOrBlankShortcutIdFallsBackToTheSystemKey() {
        assertTrue(notificationIdentitySeed("com.emclient.mailclient", null, systemKey = "system-key-1") == "system-key-1")
        assertTrue(notificationIdentitySeed("com.emclient.mailclient", "", systemKey = "system-key-1") == "system-key-1")
        assertTrue(notificationIdentitySeed("com.emclient.mailclient", "   ", systemKey = "system-key-1") == "system-key-1")
    }

    // BRIDGEY NOTIFICATION++ SOUND POLISH

    @Test fun defaultChannelAudibilityIsAudibleWhenNoChannelInfoIsAvailable() {
        assertTrue(notificationIsAudible(channelImportance = null, channelHasSound = null))
    }

    @Test fun lowOrMinImportanceChannelsAreNeverAudibleEvenWithASoundSet() {
        assertFalse(notificationIsAudible(channelImportance = NotificationManager.IMPORTANCE_LOW, channelHasSound = true))
        assertFalse(notificationIsAudible(channelImportance = NotificationManager.IMPORTANCE_MIN, channelHasSound = true))
    }

    @Test fun defaultOrHigherImportanceChannelWithNoSoundUriIsSilent() {
        // Real-device evidence: WhatsApp's "silent_notifications_6" channel.
        assertFalse(notificationIsAudible(channelImportance = NotificationManager.IMPORTANCE_DEFAULT, channelHasSound = false))
    }

    @Test fun defaultOrHigherImportanceChannelWithASoundUriIsAudible() {
        assertTrue(notificationIsAudible(channelImportance = NotificationManager.IMPORTANCE_DEFAULT, channelHasSound = true))
        assertTrue(notificationIsAudible(channelImportance = NotificationManager.IMPORTANCE_HIGH, channelHasSound = true))
    }

    // BRIDGEY CALL CONTINUITY: audio route validation

    @Test fun nullRouteIsValidMeaningNoRouteChangeRequested() {
        assertTrue(isValidAudioRoute(null))
    }

    @Test fun knownRoutesAreValid() {
        assertTrue(isValidAudioRoute("EARPIECE"))
        assertTrue(isValidAudioRoute("SPEAKER"))
        assertTrue(isValidAudioRoute("BLUETOOTH"))
    }

    @Test fun unknownOrMalformedRouteIsRejected() {
        assertFalse(isValidAudioRoute("earpiece"))
        assertFalse(isValidAudioRoute("GALAXY_BUDS"))
        assertFalse(isValidAudioRoute(""))
        assertFalse(isValidAudioRoute("SPEAKER; DROP TABLE"))
    }

    @Test fun removesOnlyForwardedNotificationsOnce() {
        val registry = ForwardedNotificationRegistry()
        registry.record("one", "system-one", "one.app")

        assertTrue(registry.removeSystemKey("system-one") == "one")
        assertTrue(registry.removeSystemKey("system-one") == null)
        assertTrue(registry.systemKey("one") == null)
    }

    @Test fun evictsOldestNotificationAtLimit() {
        val registry = ForwardedNotificationRegistry(limit = 2)
        registry.record("one", "system-one", "one.app")
        registry.record("two", "system-two", "two.app")
        registry.record("three", "system-three", "three.app")

        assertTrue(registry.systemKey("one") == null)
        assertTrue(registry.systemKey("two") == "system-two")
        assertTrue(registry.systemKey("three") == "system-three")
    }

    @Test fun removesEveryMirroredNotificationForFilteredApplication() {
        val registry = ForwardedNotificationRegistry()
        registry.record("one", "system-one", "chat.app")
        registry.record("two", "system-two", "mail.app")
        registry.record("three", "system-three", "chat.app")

        assertTrue(registry.removePackage("chat.app") == listOf("one", "three"))
        assertTrue(registry.systemKey("one") == null)
        assertTrue(registry.systemKey("two") == "system-two")
    }

    @Test fun createsStableOpaqueNotificationToken() {
        val token = notificationToken("0|package|42|tag|uid")

        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(token == notificationToken("0|package|42|tag|uid"))
        assertFalse(token == notificationToken("different"))
        assertFalse(notificationActionToken(token, 0) == notificationActionToken(token, 1))
    }

    @Test fun ongoingCallsAreForwardedWhileOtherOngoingNotificationsAreIgnored() {
        assertFalse(shouldIgnoreOngoingNotification(Notification.FLAG_ONGOING_EVENT, Notification.CATEGORY_CALL))
        assertTrue(shouldIgnoreOngoingNotification(Notification.FLAG_ONGOING_EVENT, Notification.CATEGORY_SERVICE))
        assertFalse(shouldIgnoreOngoingNotification(0, Notification.CATEGORY_SERVICE))
    }

    @Test fun createsStableTokenAcrossUnicodeAndMultilineSystemKeys() {
        val unicodeKey = "0|com.whatsapp|17|Tomáš: Ahoj, kde jsi? 👋\nline two|uid"
        val token = notificationToken(unicodeKey)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(token == notificationToken(unicodeKey))
    }

    // BRIDGEY NOTIFICATION++ POC: dismiss-loop prevention (see RemoteDismissTracker's doc comment).

    @Test fun remoteDismissIsConsumedExactlyOnce() {
        val tracker = RemoteDismissTracker()
        tracker.markPending("one")

        assertTrue(tracker.consumeIfPending("one"))
        assertFalse(tracker.consumeIfPending("one"))
    }

    @Test fun unmarkedNotificationIsNotTreatedAsARemoteDismiss() {
        val tracker = RemoteDismissTracker()

        assertFalse(tracker.consumeIfPending("never-marked"))
    }

    @Test fun remoteDismissTrackingIsIndependentPerNotification() {
        val tracker = RemoteDismissTracker()
        tracker.markPending("one")

        assertFalse(tracker.consumeIfPending("two"))
        assertTrue(tracker.consumeIfPending("one"))
    }

    // BRIDGEY NOTIFICATION++ POC: notifications.action payload validation (malformed input, reply
    // payload vs. plain action, Unicode reply text).

    private val validToken = "a".repeat(64)

    @Test fun acceptsAWellFormedPlainActionPayload() {
        assertTrue(isValidNotificationActionPayload("notif-1", validToken, replyText = null))
    }

    @Test fun acceptsAWellFormedReplyPayloadIncludingUnicodeAndMultiline() {
        assertTrue(isValidNotificationActionPayload("notif-1", validToken, replyText = "Ahoj 👋\nkde jsi?"))
    }

    @Test fun acceptsAnEmptyReplyDistinctFromNoReplyAtAll() {
        // "" (user sent an empty reply) must still validate - it is not the same as replyText==null
        // (a plain, non-reply action like Open or Mark as read).
        assertTrue(isValidNotificationActionPayload("notif-1", validToken, replyText = ""))
    }

    @Test fun rejectsBlankOrOversizedNotificationId() {
        assertFalse(isValidNotificationActionPayload("", validToken, replyText = null))
        assertFalse(isValidNotificationActionPayload(" ".repeat(3), validToken, replyText = null))
        assertFalse(isValidNotificationActionPayload("x".repeat(513), validToken, replyText = null))
    }

    @Test fun rejectsAMalformedActionToken() {
        assertFalse(isValidNotificationActionPayload("notif-1", "", replyText = null))
        assertFalse(isValidNotificationActionPayload("notif-1", "not-hex", replyText = null))
        assertFalse(isValidNotificationActionPayload("notif-1", validToken.uppercase(), replyText = null))
        assertFalse(isValidNotificationActionPayload("notif-1", validToken.dropLast(1), replyText = null))
    }

    @Test fun rejectsAnOversizedReply() {
        assertFalse(isValidNotificationActionPayload("notif-1", validToken, replyText = "x".repeat(4_097)))
        assertTrue(isValidNotificationActionPayload("notif-1", validToken, replyText = "x".repeat(4_096)))
    }

    @Test fun aSecondIndependentRemovalAfterConsumptionIsNotSwallowed() {
        // Simulates: Mac dismiss consumed once, then later the SAME notificationId legitimately
        // reappears (e.g. a fresh message resurrects the same conversation) and is genuinely
        // dismissed locally on the phone - that second removal must reach Mac normally.
        val tracker = RemoteDismissTracker()
        tracker.markPending("one")
        assertTrue(tracker.consumeIfPending("one"))

        assertFalse(tracker.consumeIfPending("one"))
    }
}
