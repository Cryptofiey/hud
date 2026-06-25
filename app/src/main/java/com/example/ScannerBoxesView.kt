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
        color = Color.parseColor("#8000FFC8") // Semi-transparent cyan
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textOutlinePaint = Paint().apply {
        color = Color.parseColor("#40000000") // Very light dark stroke
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val profileBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
<<<<<<< HEAD
        color = Color.parseColor("#80FFFFFF") // Semi-transparent white
        strokeWidth = 2f // Thin walls
=======
        color = Color.parseColor("#00FF00") // Neon green
        strokeWidth = 4f
>>>>>>> origin/main
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)
        isAntiAlias = true
    }

    private val profileFillPaint = Paint().apply {
        style = Paint.Style.FILL
<<<<<<< HEAD
        color = Color.parseColor("#10FFFFFF") // Very transparent filler
=======
        color = Color.parseColor("#3300FF00") // Semi-transparent neon green
>>>>>>> origin/main
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AAFF0000") // Semi-transparent Red for grid
        strokeWidth = 4f
        isAntiAlias = true
    }

    var verticalGridRatios = listOf(0.25f, 0.78f)
    var horizontalGridRatios = listOf(0.15f, 0.35f, 0.60f, 0.85f)
    var showGrid: Boolean = true // Will sync with state

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
        if (!PokerHudSharedState.showScannerBoxes.value) return
        super.onDraw(canvas)

        try {
            val location = IntArray(2)
            this.getLocationOnScreen(location)
            val viewOffsetX = location[0]
            val viewOffsetY = location[1]

            val w = width.toFloat()
            val h = height.toFloat()
            if (state.rawScannerBoxes?.isNotEmpty() == true) {
                // Log dimensions
                android.util.Log.d("ScannerBoxesView", "onDraw size: w=$w, h=$h, rawBoxes=${state.rawScannerBoxes?.size}")
            }

            // Draw grid if enabled
            if (showGrid) {
                for (xRatio in verticalGridRatios) {
                    canvas.drawLine(w * xRatio, 0f, w * xRatio, h, gridPaint)
                }
                for (yRatio in horizontalGridRatios) {
                    canvas.drawLine(0f, h * yRatio, w, h * yRatio, gridPaint)
                }
            }

            // Draw the search zone (Horseshoe)
            if (PokerHudSharedState.showScannerBoxes.value) {
                val path = Path()
                path.fillType = Path.FillType.EVEN_ODD
                // Whole screen
                path.addRect(0f, 0f, w, h, Path.Direction.CW)
                // Exclude Top Header
                path.addRect(0f, 0f, w, h * 0.11f, Path.Direction.CW)
                // Exclude Community Cards
                path.addRect(w * 0.25f, h * 0.38f, w * 0.75f, h * 0.68f, Path.Direction.CW)
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
                            box.left + offsetX.toInt() - viewOffsetX,
                            box.top + offsetY.toInt() - viewOffsetY,
                            box.right + offsetX.toInt() - viewOffsetX,
                            box.bottom + offsetY.toInt() - viewOffsetY
                        )
                        
                        if (opp.isActive) {
                            canvas.drawRect(actualBox, fillPaint)
                            canvas.drawRect(actualBox, boxPaint)
                        } else {
                            canvas.drawRect(actualBox, inactiveBoxPaint)
                        }
<<<<<<< HEAD
=======

                        // Draw VPIP Box overlay
                        val vpipBox = opp.sessionVpipBox
                        if (vpipBox != null) {
                            val vpipActualBox = Rect(
                                vpipBox.left + offsetX.toInt() - viewOffsetX,
                                vpipBox.top + offsetY.toInt() - viewOffsetY,
                                vpipBox.right + offsetX.toInt() - viewOffsetX,
                                vpipBox.bottom + offsetY.toInt() - viewOffsetY
                            )
                            val vpipPaint = Paint().apply {
                                color = android.graphics.Color.YELLOW
                                style = Paint.Style.STROKE
                                strokeWidth = 3f
                            }
                            canvas.drawRect(vpipActualBox, vpipPaint)
                            val vpipFill = Paint().apply {
                                color = android.graphics.Color.parseColor("#44FFFF00") // yellow semi transparent
                                style = Paint.Style.FILL
                            }
                            canvas.drawRect(vpipActualBox, vpipFill)
                        }
>>>>>>> origin/main
                    }
                }
            }

        // Draw generic scanned text boxes continuously
        state.rawScannerBoxes?.forEach { box ->
            val actualBox = Rect(
                box.rect.left + offsetX.toInt() - viewOffsetX,
                box.rect.top + offsetY.toInt() - viewOffsetY,
                box.rect.right + offsetX.toInt() - viewOffsetX,
                box.rect.bottom + offsetY.toInt() - viewOffsetY
            )

            // Skip false-positive boxes detected in the top-left area HUD
            val w = width.toFloat()
            val h = height.toFloat()
            if (actualBox.top < h * 0.15f && actualBox.left < w * 0.4f) {
                return@forEach
            }

            canvas.drawRect(actualBox, zoneFillPaint)
            canvas.drawRect(actualBox, profileBoxPaint)
            
            // We intentionally DO NOT draw the text here anymore to prevent 
            // OCR feedback loops where the next frame scans our drawn overlay text.
        }

        // Draw profile stat highlights if any
        state.profileBoxes?.forEach { box ->
            val actualBox = Rect(
                box.rect.left + offsetX.toInt() - viewOffsetX,
                box.rect.top + offsetY.toInt() - viewOffsetY,
                box.rect.right + offsetX.toInt() - viewOffsetX,
                box.rect.bottom + offsetY.toInt() - viewOffsetY
            )
            
            // Skip false-positive boxes detected in the top-left area
            val w = width.toFloat()
            val h = height.toFloat()
            if (actualBox.top < h * 0.15f && actualBox.left < w * 0.4f) {
                return@forEach
            }
            if (actualBox.top < h * 0.12f) { 
                return@forEach
            }

            canvas.drawRect(actualBox, profileFillPaint)
            canvas.drawRect(actualBox, profileBoxPaint)
        }
        } catch (e: Throwable) {
            android.util.Log.e("ScannerBoxesView", "Error drawing scanner boxes", e)
        }
    }
}
