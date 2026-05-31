package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View

class ScannerBoxesView(context: Context) : View(context) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E91E63") // Pinkish / bright color
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val zoneOutlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#6600FF00") // Semi-transparent green
        strokeWidth = 6f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f)
        isAntiAlias = true
    }
    
    private val zoneFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A00FF00") // Very transparent green
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#44E91E63") // semi-transparent pink
    }

    private val inactiveBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#808080") // Grey for folded
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        isAntiAlias = true
    }

    var state: PokerUiState = PokerUiState()
        set(value) {
            field = value
            invalidate()
        }

    var offsetX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var offsetY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw the search zone (Horseshoe)
        val path = Path()
        path.moveTo(0f, h)
        path.lineTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, h)
        // Inner cutout (matching the 36% and 40% parameters in OpponentScanner)
        path.lineTo(w * 0.64f, h)
        path.lineTo(w * 0.64f, h * 0.40f)
        path.lineTo(w * 0.36f, h * 0.40f)
        path.lineTo(w * 0.36f, h)
        path.close()

        canvas.drawPath(path, zoneFillPaint)
        canvas.drawPath(path, zoneOutlinePaint)

        for (opp in state.opponents) {
            val box = opp.boundingBox
            if (box != null) {
                val actualBox = Rect(
                    box.left + offsetX.toInt(),
                    box.top + offsetY.toInt(),
                    box.right + offsetX.toInt(),
                    box.bottom + offsetY.toInt()
                )
                
                if (opp.isActive) {
                    canvas.drawRect(actualBox, fillPaint)
                    canvas.drawRect(actualBox, boxPaint)
                    
                    val label = opp.nickname
                    canvas.drawText(label, actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textPaint)
                } else {
                    // Draw dim grey box if folded/inactive so user knows scanner didn't lose track of the frame
                    canvas.drawRect(actualBox, inactiveBoxPaint)
                    canvas.drawText("FOLDED", actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textPaint)
                }
            }
        }
    }
}
