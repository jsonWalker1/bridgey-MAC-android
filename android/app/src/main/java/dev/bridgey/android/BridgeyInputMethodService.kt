package dev.bridgey.android

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "BridgeyIme"

/**
 * KVM PART 3 POC #2. The Android-side keyboard-injection layer - the counterpart to
 * BridgeyAccessibilityService for pointer/touch. dispatchGesture has no keyboard equivalent (it is
 * touch-only), so KEY/TEXT events need a different, equally standard mechanism: becoming the active
 * Input Method (the same contract every third-party keyboard app - Gboard, SwiftKey - uses).
 * Requires the user to explicitly enable "Bridgey KVM Keyboard" under Settings > System > Languages &
 * input > On-screen keyboard > Manage keyboards, AND separately switch to it as the active keyboard -
 * a real, revocable, two-step consent, not a bypass of anything.
 *
 * This IME does not offer normal typing UI - its only affordance is a small status view (label +
 * "Switch keyboard back" button via switchToPreviousInputMethod()) so a user who ends up on this IME
 * outside of an active KVM test is never stuck without a way to type normally again.
 *
 * TEXT events call InputConnection.commitText(...); KEY events call InputConnection.sendKeyEvent(...)
 * with a matching ACTION_DOWN/ACTION_UP KeyEvent - both only work while this service is the currently
 * focused input method with a live InputConnection (i.e. a text field somewhere has focus), exactly
 * like normal keyboard input.
 */
class BridgeyInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layout.addView(TextView(this).apply { text = "Bridgey KVM Keyboard active - typing is driven by the paired Mac." })
        layout.addView(
            Button(this).apply {
                text = "Switch keyboard back"
                setOnClickListener { switchToPreviousInputMethod() }
            },
        )
        return layout
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "KVM input method service created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    internal fun injectText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    internal fun injectKey(keyCode: Int, action: KeyAction) {
        val motionAction = if (action == KeyAction.DOWN) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        currentInputConnection?.sendKeyEvent(KeyEvent(motionAction, keyCode))
    }

    companion object {
        @Volatile
        var instance: BridgeyInputMethodService? = null
            private set
    }
}
