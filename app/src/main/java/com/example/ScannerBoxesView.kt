package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class ScannerBoxesView(context: Context) : View(context) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E91E63") // Pinkish / bright color
        strokeWidth = 5f
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

        for (opp in state.opponents) {
            if (opp.isActive) {
                val box = opp.boundingBox
                if (box != null) {
                    val actualBox = Rect(
                        box.left + offsetX.toInt(),
                        box.top + offsetY.toInt(),
                        box.right + offsetX.toInt(),
                        box.bottom + offsetY.toInt()
                    )
                    
                    canvas.drawRect(actualBox, fillPaint)
                    canvas.drawRect(actualBox, boxPaint)
                    
                    val label = opp.nickname
                    canvas.drawText(label, actualBox.left.toFloat(), actualBox.top.toFloat() - 10f, textPaint)
                }
            }
        }
    }
}
