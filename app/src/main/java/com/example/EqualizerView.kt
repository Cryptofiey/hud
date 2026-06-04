package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

data class EqualizerState(
    val l1Fill: Float = 0f,
    val l1Color: Int = Color.GRAY,
    val l2Fill: Float = 0f,
    val l2Color: Int = Color.GRAY,
    val l3Segments: List<Int> = List(6) { Color.WHITE }, // Colors for 6 opponent segments
    val l4Fill: Float = 0f,
    val l4Color: Int = Color.GRAY
)

class EqualizerView(context: Context) : View(context) {

    private var state = EqualizerState()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun updateState(newState: EqualizerState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val gap = w * 0.02f
        val blockWidth = (w - gap * 3) / 4f

        // Draw L1 (Math)
        drawBlock(canvas, 0f, blockWidth, h, state.l1Fill, state.l1Color, "L1")

        // Draw L2 (Table Data)
        drawBlock(canvas, blockWidth + gap, blockWidth, h, state.l2Fill, state.l2Color, "L2")

        // Draw L3 (Profiles - custom segments)
        val l3Left = (blockWidth + gap) * 2
        drawL3Block(canvas, l3Left, blockWidth, h, state.l3Segments)

        // Draw L4 (Robot)
        val l4Left = (blockWidth + gap) * 3
        drawBlock(canvas, l4Left, blockWidth, h, state.l4Fill, state.l4Color, "L4")
    }

    private fun drawBlock(canvas: Canvas, left: Float, width: Float, height: Float, fillPct: Float, fillColor: Int, label: String) {
        val right = left + width
        
        // Background
        val bgRect = RectF(left, 0f, right, height)
        canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)

        // Fill
        val fillHeight = height * fillPct.coerceIn(0f, 1f)
        if (fillHeight > 0) {
            val fillRect = RectF(left, height - fillHeight, right, height)
            fillPaint.color = fillColor
            canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
        }
        
        // Label shadow
        textPaint.color = Color.BLACK
        canvas.drawText(label, left + width / 2f + 2f, height / 2f + textPaint.textSize / 3f + 2f, textPaint)
        // Label
        textPaint.color = Color.WHITE
        canvas.drawText(label, left + width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
    }

    private fun drawL3Block(canvas: Canvas, left: Float, width: Float, height: Float, segments: List<Int>) {
        val right = left + width
        // Background
        val bgRect = RectF(left, 0f, right, height)
        canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)

        val segmentCount = segments.size
        if (segmentCount > 0) {
            val segGap = 2f
            val segWidth = (width - segGap * (segmentCount - 1)) / segmentCount
            
            for (i in 0 until segmentCount) {
                val segLeft = left + i * (segWidth + segGap)
                val segRight = segLeft + segWidth
                val segRect = RectF(segLeft, 2f, segRight, height - 2f)
                
                fillPaint.color = segments[i]
                canvas.drawRoundRect(segRect, 2f, 2f, fillPaint)
            }
        }
        
        val label = "L3"
        textPaint.color = Color.BLACK
        canvas.drawText(label, left + width / 2f + 2f, height / 2f + textPaint.textSize / 3f + 2f, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(label, left + width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
    }
}
