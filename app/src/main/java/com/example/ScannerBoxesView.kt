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
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
        setShadowLayer(10f, 0f, 0f, Color.parseColor("#FF00FFC8")) // Neon glow
        isAntiAlias = true
    }

    private val textOutlinePaint = Paint().apply {
        color = Color.BLACK
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 2f
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

    var isHidden: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (isHidden) return
        super.onDraw(canvas)

        try {
            val w = width.toFloat()
            val h = height.toFloat()

            // Draw the search zone (Horseshoe) - only if not specifically drawing profile boxes
            if (state.profileBoxes == null && PokerHudSharedState.showScannerBoxes.value) {
            val path = Path()
            path.fillType = Path.FillType.EVEN_ODD
            // Whole screen
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            // Exclude Top Header
            path.addRect(0f, 0f, w, h * 0.11f, Path.Direction.CW)
            // Exclude Community Cards
            path.addRect(w * 0.18f, h * 0.38f, w * 0.82f, h * 0.68f, Path.Direction.CW)
            // Exclude Hero Hole Cards
            path.addRect(w * 0.53f, h * 0.68f, w * 0.95f, h * 0.93f, Path.Direction.CW)

            canvas.drawPath(path, zoneFillPaint)
            canvas.drawPath(path, zoneOutlinePaint)

            textPaint.textSize = 34f
            textOutlinePaint.textSize = 34f

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
                        canvas.drawText(label, actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textOutlinePaint)
                        canvas.drawText(label, actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textPaint)
                    } else {
                        canvas.drawRect(actualBox, inactiveBoxPaint)
                        canvas.drawText("FOLDED", actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textOutlinePaint)
                        canvas.drawText("FOLDED", actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textPaint)
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
            
            // Skip false-positive boxes detected in the top-left area (e.g., background table HUD)
            val w = width.toFloat()
            val h = height.toFloat()
            if (actualBox.top < h * 0.15f && actualBox.left < w * 0.4f) {
                return@forEach
            }
            if (actualBox.top < h * 0.12f) { // Generally avoid top header
                return@forEach
            }

            canvas.drawRect(actualBox, profileFillPaint)
            canvas.drawRect(actualBox, profileBoxPaint)
            
            // Set text size to match the height of the bounding box
            val boxHeight = actualBox.height().toFloat()
            val textSz = java.lang.Math.max(1f, boxHeight * 0.95f)
            textPaint.textSize = textSz // Almost full height for precise fit
            textOutlinePaint.textSize = textSz
            
            // Draw text exactly starting from the left edge of the bounding box
            val xPos = actualBox.left.toFloat()
            
            // Baseline calculation: align the bottom of the text with the bottom of the box
            // The descent is the portion below the baseline.
            val yPos = actualBox.bottom.toFloat() - textPaint.descent()
            
            canvas.drawText(box.label, xPos, yPos, textOutlinePaint)
            canvas.drawText(box.label, xPos, yPos, textPaint)
        }
        } catch (e: Throwable) {
            android.util.Log.e("ScannerBoxesView", "Error drawing scanner boxes", e)
        }
    }
}
