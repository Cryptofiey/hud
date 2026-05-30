package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class PokerAutomationService : AccessibilityService() {

    companion object {
        private var instance: PokerAutomationService? = null
        
        fun isRunning(): Boolean = instance != null

        fun clickAt(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            Log.d("PokerAutomationService", "Click requested at X: $x, Y: $y")
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build()
            
            return service.dispatchGesture(gesture, null, null)
        }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
            val service = instance ?: return false
            Log.d("PokerAutomationService", "Swipe requested from ($startX, $startY) to ($endX, $endY)")
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            
            return service.dispatchGesture(gesture, null, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("PokerAutomationService", "Accessibility service connected successfully!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
