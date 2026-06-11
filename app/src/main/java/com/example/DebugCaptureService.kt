package com.example

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DebugCaptureService : AccessibilityService() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DebugCapture", "DebugCapture Accessibility Service Connected")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                accessibilityButtonController.registerAccessibilityButtonCallback(
                    object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                        override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController?) {
                            scope.launch {
                                DebugLogManager.triggerDiagnosticCapture(this@DebugCaptureService)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("DebugCapture", "Error registering accessibility button", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No action needed for events
    }

    override fun onInterrupt() {
        Log.d("DebugCapture", "DebugCapture Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        job.cancel()
    }

    companion object {
        var instance: DebugCaptureService? = null
            private set
    }
}
