package dev.bridgey.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestPermissionsTest {
    @Test
    fun manifestDeclaresOnlyRequiredPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.CHANGE_WIFI_MULTICAST_STATE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
                "android.permission.CALL_PHONE",
                "android.permission.READ_PHONE_STATE",
                "android.permission.ANSWER_PHONE_CALLS",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.ACCESS_NOTIFICATION_POLICY",
            ),
            declared,
        )
    }
}
