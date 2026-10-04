package dev.bridgey.android

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BRIDGEY NOTIFICATION++ RECONCILIATION: identity, multi-key registry, re-bind rebuild, sync parts. */
class NotificationReconciliationTest {
    private val own = "dev.bridgey.android"

    private fun input(
        key: String = "0|com.whatsapp|1|tag|10365",
        packageName: String = "com.whatsapp",
        flags: Int = 0,
        category: String? = Notification.CATEGORY_MESSAGE,
        visibility: Int = Notification.VISIBILITY_PRIVATE,
        importance: Int? = NotificationManager.IMPORTANCE_HIGH,
        title: String = "Karolína",
        text: String = "Ahoj",
        shortcutId: String? = "112575488491769@lid",
    ) = NotificationEligibilityInput(key, packageName, flags, category, visibility, importance, title, text, shortcutId)

    private fun id(input: NotificationEligibilityInput, idleCall: Boolean = false, enabled: Boolean = true) =
        eligibleNotificationId(input, own, isApplicationEnabled = { enabled }, isIdleCall = { idleCall })

    // Identity

    @Test fun shortcutIdIdentityIsTheUnchangedConversationFormula() {
        assertEquals(
            notificationToken(notificationIdentitySeed("com.whatsapp", "112575488491769@lid", "0|com.whatsapp|1|tag|10365")),
            id(input()),
        )
        assertEquals(notificationToken("com.whatsapp conversation 112575488491769@lid"), id(input()))
    }

    @Test fun missingShortcutIdFallsBackToTheSystemKey() {
        val key = "0|com.emclient.mailclient|7|null|10400"
        assertEquals(notificationToken(key), id(input(key = key, packageName = "com.emclient.mailclient", shortcutId = null)))
    }

    @Test fun anUpdateOfTheSameNotificationKeepsItsId() {
        assertEquals(id(input(text = "first")), id(input(text = "first, edited")))
    }

    @Test fun whatsAppMessagesOfOneConversationShareOneIdAcrossKeysAndConversationsStayApart() {
        val a = id(input(key = "0|com.whatsapp|1|tagA|10365", text = "A"))
        val b = id(input(key = "0|com.whatsapp|1|tagB|10365", text = "B"))
        val other = id(input(key = "0|com.whatsapp|2|tagC|10365", shortcutId = "998877665544332@lid"))
        assertEquals(a, b)
        assertTrue(a != other)
    }

    @Test fun eligibilityRulesMatchThePreviousPostFilters() {
        assertNotNull(id(input()))
        assertNull(id(input(packageName = own)))
        assertNull(id(input(flags = Notification.FLAG_GROUP_SUMMARY)))
        assertNull(id(input(flags = Notification.FLAG_ONGOING_EVENT, category = Notification.CATEGORY_SERVICE)))
        assertNotNull(id(input(flags = Notification.FLAG_ONGOING_EVENT, category = Notification.CATEGORY_CALL)))
        assertNull(id(input(visibility = Notification.VISIBILITY_SECRET)))
        assertNull(id(input(importance = NotificationManager.IMPORTANCE_MIN)))
        assertNotNull(id(input(importance = NotificationManager.IMPORTANCE_LOW)))
        assertNotNull(id(input(importance = null)))
        assertNull(id(input(title = "", text = "")))
        assertNotNull(id(input(title = "", text = "only text")))
        assertNull(id(input(), enabled = false))
        assertNull(id(input(category = Notification.CATEGORY_CALL), idleCall = true))
        assertNotNull(id(input(category = Notification.CATEGORY_MESSAGE), idleCall = true))
    }

    // Multi-key registry

    @Test fun twoKeysBackOneLogicalNotificationUntilTheLastIsRemoved() {
        val registry = ForwardedNotificationRegistry()
        assertNull(registry.record("conv", "key-1", "com.whatsapp"))
        assertNull(registry.record("conv", "key-2", "com.whatsapp"))

        assertNull(registry.removeSystemKey("key-1"))
        assertEquals(setOf("key-2"), registry.systemKeys("conv"))
        assertEquals("conv", registry.removeSystemKey("key-2"))
        assertTrue(registry.systemKeys("conv").isEmpty())
    }

    @Test fun removingTheNewerKeyFirstAlsoKeepsTheNotificationUntilTheLastKey() {
        val registry = ForwardedNotificationRegistry()
        registry.record("conv", "key-1", "com.whatsapp")
        registry.record("conv", "key-2", "com.whatsapp")

        assertNull(registry.removeSystemKey("key-2"))
        assertEquals("conv", registry.removeSystemKey("key-1"))
    }

    @Test fun dismissTargetsEveryKeyOfTheLogicalNotification() {
        val registry = ForwardedNotificationRegistry()
        registry.record("conv", "key-1", "com.whatsapp")
        registry.record("conv", "key-2", "com.whatsapp")
        registry.record("other", "key-3", "com.whatsapp")

        assertEquals(setOf("key-1", "key-2"), registry.systemKeys("conv"))
    }

    @Test fun duplicateRecordAndDuplicateRemoveAreIdempotent() {
        val registry = ForwardedNotificationRegistry()
        registry.record("conv", "key-1", "com.whatsapp")
        registry.record("conv", "key-1", "com.whatsapp")
        assertEquals(setOf("key-1"), registry.systemKeys("conv"))

        assertEquals("conv", registry.removeSystemKey("key-1"))
        assertNull(registry.removeSystemKey("key-1"))
        assertNull(registry.removeSystemKey("never-recorded"))
    }

    @Test fun aKeyMovingToANewIdentityOrphansItsOldIdOnlyWhenItWasTheLastKey() {
        val registry = ForwardedNotificationRegistry()
        registry.record("old", "key-1", "com.whatsapp")
        assertEquals("old", registry.record("new", "key-1", "com.whatsapp"))
        assertTrue(registry.systemKeys("old").isEmpty())

        registry.record("shared", "key-2", "com.whatsapp")
        registry.record("shared", "key-3", "com.whatsapp")
        assertNull(registry.record("new", "key-2", "com.whatsapp"))
        assertEquals(setOf("key-3"), registry.systemKeys("shared"))
    }

    @Test fun evictionForgetsEveryKeyOfTheEvictedNotification() {
        val registry = ForwardedNotificationRegistry(limit = 1)
        registry.record("one", "key-1", "a")
        registry.record("two", "key-2", "b")

        assertNull(registry.removeSystemKey("key-1"))
        assertEquals("two", registry.removeSystemKey("key-2"))
    }

    // Listener re-bind rebuild (audit L3: the old bind path recorded token(sbn.key), posts used token(seed))

    @Test fun rebuildFromActiveNotificationsUsesTheSeedIdentitySoRemovalReachesTheMacId() {
        val key = "0|com.whatsapp|1|tag|10365"
        val active = input(key = key)
        val macId = id(active)!!
        val registry = ForwardedNotificationRegistry()
        registry.record(notificationToken(key), key, "com.whatsapp") // what the pre-fix bind path did

        registry.clear()
        listOf(active).forEach { sbn -> id(sbn)?.let { registry.record(it, sbn.systemKey, sbn.packageName) } }

        assertEquals(macId, registry.removeSystemKey(key))
    }

    @Test fun rebuiltSnapshotIsExactlyTheEligibleIdsOfTheActiveSet() {
        val active = listOf(
            input(key = "k1", shortcutId = "chat-1"),
            input(key = "k2", shortcutId = "chat-1"),
            input(key = "k3", shortcutId = "chat-2"),
            input(key = "k4", flags = Notification.FLAG_GROUP_SUMMARY),
            input(key = "k5", packageName = own),
        )
        val registry = ForwardedNotificationRegistry()
        val snapshot = linkedSetOf<String>()
        active.forEach { sbn -> id(sbn)?.let { registry.record(it, sbn.systemKey, sbn.packageName); snapshot += it } }

        assertEquals(setOf(id(input(key = "k1", shortcutId = "chat-1")), id(input(key = "k3", shortcutId = "chat-2"))), snapshot)
        assertEquals(setOf("k1", "k2"), registry.systemKeys(id(input(shortcutId = "chat-1"))!!))
    }

    // Sync parts

    private fun ids(count: Int) = (0 until count).map { notificationToken("n$it") }

    @Test fun emptySnapshotIsOneEmptyPart() {
        assertEquals(listOf(emptyList<String>()), notificationSyncParts(emptyList()))
    }

    @Test fun snapshotsAreSplitIntoPartsOfAtMost256() {
        assertEquals(listOf(1), notificationSyncParts(ids(1)).map { it.size })
        assertEquals(listOf(256), notificationSyncParts(ids(256)).map { it.size })
        assertEquals(listOf(256, 1), notificationSyncParts(ids(257)).map { it.size })
        assertEquals(listOf(256, 256), notificationSyncParts(ids(512)).map { it.size })
        assertEquals(ids(512), notificationSyncParts(ids(512)).flatten())
    }

    @Test fun aFullPartStaysWellUnderTheFrameLimit() {
        val payload = org.json.JSONObject()
            .put("version", 1)
            .put("syncId", java.util.UUID.randomUUID().toString())
            .put("part", 1)
            .put("parts", 2)
            .put("notificationIds", org.json.JSONArray(ids(256)))
            .toString()
        // AES-GCM + base64 inflate by ~4/3; the whole envelope must stay under 65,536 bytes.
        assertTrue(payload.toByteArray().size * 4 / 3 + 1_024 < 65_536)
    }

    // macOS Clear All (`notifications.dismissMany`)

    private fun dismissManyJson(ids: List<String>, version: Int = 1, reason: String = "mac_clear_all") =
        org.json.JSONObject().put("version", version).put("reason", reason).put("notificationIds", org.json.JSONArray(ids)).toString()

    @Test fun dismissManyAcceptsOneTo256ValidIds() {
        assertEquals(ids(1), parseNotificationDismissManyPayload(dismissManyJson(ids(1))))
        assertEquals(ids(256), parseNotificationDismissManyPayload(dismissManyJson(ids(256))))
    }

    @Test fun dismissManyRejectsMalformedPayloads() {
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(emptyList())))
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(ids(257))))
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(ids(1), version = 2)))
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(ids(1), reason = "other")))
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(listOf("not-hex"))))
        assertNull(parseNotificationDismissManyPayload(dismissManyJson(listOf(ids(1).single().uppercase()))))
        assertNull(parseNotificationDismissManyPayload("{not json"))
    }

    @Test fun contentForwardedWithinTheLastMinuteSurvivesAMacClearAll() {
        assertTrue(isTooRecentForMacClearAll(lastForwardedAtMillis = 100_000, nowMillis = 159_999))
        assertTrue(!isTooRecentForMacClearAll(lastForwardedAtMillis = 100_000, nowMillis = 160_000))
        assertTrue(!isTooRecentForMacClearAll(lastForwardedAtMillis = null, nowMillis = 160_000))
    }
}
