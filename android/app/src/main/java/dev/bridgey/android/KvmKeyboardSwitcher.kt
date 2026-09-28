package dev.bridgey.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

private const val TAG = "KvmKeyboardSwitcher"
private const val PREFS_NAME = "kvm_keyboard_switcher"
private const val KEY_PREVIOUS_IME = "previous_ime"
private const val BRIDGEY_IME_ID = "dev.bridgey.android/.BridgeyInputMethodService"

/**
 * BRIDGEY KVM KEYBOARD V1 (switch shortcut). Toggles the phone's active input method to/from
 * Bridgey's KVM Keyboard, triggered remotely by the Mac's Command+K shortcut (see
 * PairingCoordinator.receiveSwitchKeyboard / ScreenShareWindow.swift's Command+K handling) - avoids
 * the manual Settings > Manage keyboards trip every time Screen Share starts.
 *
 * Directly writing [Settings.Secure.DEFAULT_INPUT_METHOD] (rather than any [InputMethodManager] API)
 * is the same mechanism `adb shell ime set` uses - InputMethodManagerService watches this key via a
 * ContentObserver and switches immediately, no reboot/relaunch needed. It requires
 * WRITE_SECURE_SETTINGS, a signature/system permission normal app installs never get automatically -
 * the user grants it once via `adb shell pm grant dev.bridgey.android
 * android.permission.WRITE_SECURE_SETTINGS` (same one-time ADB step this project's KVM Accessibility
 * Service setup already asks for, just via Settings instead of ADB). Without it granted, this falls
 * back to [InputMethodManager.showInputMethodPicker] - the standard system picker dialog, one manual
 * tap instead of a full Settings trip.
 */
internal object KvmKeyboardSwitcher {
    fun toggle(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted - falling back to the system keyboard picker")
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

        if (current == BRIDGEY_IME_ID) {
            val previous = prefs.getString(KEY_PREVIOUS_IME, null)
            if (previous == null) {
                Log.w(TAG, "already on Bridgey's keyboard but no previous keyboard was recorded - nothing to switch back to")
                return
            }
            Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, previous)
            Log.i(TAG, "switched back to previous keyboard $previous")
        } else {
            prefs.edit().putString(KEY_PREVIOUS_IME, current).apply()
            Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, BRIDGEY_IME_ID)
            Log.i(TAG, "switched to Bridgey's KVM Keyboard (was $current)")
        }
    }
}
