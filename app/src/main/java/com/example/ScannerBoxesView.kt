package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View

class ScannerBoxesView(context: Context) : View(context) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E91E63") // Pinkish / bright color
        strokeWidth = 5f
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
        color = Color.parseColor("#00FFC8") // Bright Neon Teal/Cyan
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
        setShadowLayer(15f, 0f, 0f, Color.parseColor("#CC00FFC8")) // Strong neon glow
        isAntiAlias = true
    }

    private val textOutlinePaint = Paint().apply {
        color = Color.BLACK
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val profileBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFEB3B") // Bright Yellow
        strokeWidth = 3f
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#88FFEB3B")) // Glow
        isAntiAlias = true
    }

    private val profileFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#15FFEB3B") // Very transparent yellow
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

        // Draw the search zone (Horseshoe) - only if not specifically drawing profile boxes
        if (state.profileBoxes == null && PokerHudSharedState.showScannerBoxes.value) {
            val path = Path()
            path.moveTo(0f, h)
            path.lineTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w, h)
            // Inner cutout (matching the parameters in OpponentScanner)
            path.lineTo(w * 0.65f, h)
            path.lineTo(w * 0.65f, h * 0.35f)
            path.lineTo(w * 0.35f, h * 0.35f)
            path.lineTo(w * 0.35f, h)
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
                        // Align text baseline to the bottom of the bounding box for perfect overlay
                        canvas.drawText(label, actualBox.left.toFloat(), actualBox.bottom.toFloat(), textOutlinePaint)
                        canvas.drawText(label, actualBox.left.toFloat(), actualBox.bottom.toFloat(), textPaint)
                    } else {
                        canvas.drawRect(actualBox, inactiveBoxPaint)
                        canvas.drawText("FOLDED", actualBox.left.toFloat(), actualBox.bottom.toFloat(), textOutlinePaint)
                        canvas.drawText("FOLDED", actualBox.left.toFloat(), actualBox.bottom.toFloat(), textPaint)
                    }
                }
            }
        }

        // Draw profile stat highlights if any
        state.profileBoxes?.forEach { box ->
            val actualBox = Rect(
                box.rect.left + offsetX.toInt(),
                box.rect.top + offsetY.toInt(),
                box.rect.right + offsetX.toInt(),
                box.rect.bottom + offsetY.toInt()
            )
            canvas.drawRect(actualBox, profileFillPaint)
            canvas.drawRect(actualBox, profileBoxPaint)
            
            // Draw parsed value overlaying the box for precision
            canvas.drawText(box.label, actualBox.left.toFloat(), actualBox.bottom.toFloat(), textOutlinePaint)
            canvas.drawText(box.label, actualBox.left.toFloat(), actualBox.bottom.toFloat(), textPaint)
        }
    }
}
