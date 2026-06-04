package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoPlayerService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoPlayer", "AutoPlayer Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need to process window events, our trigger comes from PokerHudService OCR.
    }

    override fun onInterrupt() {
        Log.d("AutoPlayer", "AutoPlayer Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    fun dispatchClick(x: Float, y: Float, duration: Long) {
        val path = Path()
        path.moveTo(x, y)
        // Some systems ignore 0-length gestures, so we provide an epsilon line
        path.lineTo(x + 1f, y + 1f)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("AutoPlayer", "Click dispatch completed at $x, $y")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d("AutoPlayer", "Click dispatch cancelled")
            }
        }, null)
    }
    
    fun dispatchSwipeCurve(points: List<Pair<Float, Float>>, duration: Long) {
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points.first().first, points.first().second)
        
        // Simple smoothing for human-like swipe instead of harsh lines
        for (i in 1 until points.size) {
            val pX = points[i].first
            val pY = points[i].second
            val prevX = points[i - 1].first
            val prevY = points[i - 1].second
            
            val ctrlX = (prevX + pX) / 2
            val ctrlY = (prevY + pY) / 2
            
            // Bezier curve through points
            path.quadTo(prevX, prevY, ctrlX, ctrlY)
        }
        path.lineTo(points.last().first, points.last().second)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    companion object {
        var instance: AutoPlayerService? = null
            private set
    }
}
