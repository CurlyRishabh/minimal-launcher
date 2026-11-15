package com.minimal.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Exists only to call performGlobalAction(). It is off until the user turns it on in
 * Accessibility settings, listens only to our own package, and cannot read window content.
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @SuppressLint("StaticFieldLeak") // Cleared in onUnbind/onDestroy.
        @Volatile
        var instance: LockService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** @return true if the screen was locked. */
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        fun openNotifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true

        /** Lets the user switch the feature off from our settings without a trip to Android's. */
        fun disable() {
            instance?.disableSelf()
        }
    }
}
