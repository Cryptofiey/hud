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
        textSize = context.resources.displayMetrics.density * 10f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    var isVertical: Boolean = false
        set(value) {
            field = value
            invalidate()
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

        if (!isVertical) {
            val gap = w * 0.02f
<<<<<<< HEAD
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
        } else {
            val gap = h * 0.02f
            val blockHeight = (h - gap * 3) / 4f

            drawBlockVertical(canvas, 0f, w, blockHeight, state.l1Fill, state.l1Color, "L1")
            drawBlockVertical(canvas, blockHeight + gap, w, blockHeight, state.l2Fill, state.l2Color, "L2")
            val l3Top = (blockHeight + gap) * 2
            drawL3BlockVertical(canvas, l3Top, w, blockHeight, state.l3Segments)
            val l4Top = (blockHeight + gap) * 3
            drawBlockVertical(canvas, l4Top, w, blockHeight, state.l4Fill, state.l4Color, "L4")
=======
            val totalBlockW = w - gap * 3
            val unitW = totalBlockW / 6f

            // Draw L1 (Math)
            val l1W = unitW
            drawBlock(canvas, 0f, l1W, h, state.l1Fill, state.l1Color, "L1")

            // Draw L2 (Table Data)
            val l2Left = l1W + gap
            val l2W = unitW * 2f
            drawBlock(canvas, l2Left, l2W, h, state.l2Fill, state.l2Color, "L2")

            // Draw L3 (Profiles - custom segments)
            val l3Left = l2Left + l2W + gap
            val l3W = unitW * 2f
            drawL3Block(canvas, l3Left, l3W, h, state.l3Segments)

            // Draw L4 (Robot)
            val l4Left = l3Left + l3W + gap
            val l4W = unitW
            drawBlock(canvas, l4Left, l4W, h, state.l4Fill, state.l4Color, "L4")
        } else {
            val gap = h * 0.02f
            val totalBlockH = h - gap * 3
            val unitH = totalBlockH / 6f

            val l1H = unitH
            drawBlockVertical(canvas, 0f, w, l1H, state.l1Fill, state.l1Color, "L1")
            
            val l2Top = l1H + gap
            val l2H = unitH * 2f
            drawBlockVertical(canvas, l2Top, w, l2H, state.l2Fill, state.l2Color, "L2")
            
            val l3Top = l2Top + l2H + gap
            val l3H = unitH * 2f
            drawL3BlockVertical(canvas, l3Top, w, l3H, state.l3Segments)
            
            val l4Top = l3Top + l3H + gap
            val l4H = unitH
            drawBlockVertical(canvas, l4Top, w, l4H, state.l4Fill, state.l4Color, "L4")
>>>>>>> origin/main
        }
    }

    private fun drawBlockVertical(canvas: Canvas, top: Float, width: Float, height: Float, fillPct: Float, fillColor: Int, label: String) {
        val bottom = top + height
        
        // Background
        val bgRect = RectF(0f, top, width, bottom)
        canvas.drawRoundRect(bgRect, 3f, 3f, bgPaint)

        // Fill
        val fillHeight = height * fillPct.coerceIn(0f, 1f)
        if (fillHeight > 0) {
            val fillRect = RectF(0f, bottom - fillHeight, width, bottom)
            fillPaint.color = fillColor
            canvas.drawRoundRect(fillRect, 3f, 3f, fillPaint)
        }
        
        // Label shadow
        textPaint.color = Color.BLACK
        canvas.drawText(label, width / 2f + 1f, top + height / 2f + textPaint.textSize / 3f + 1f, textPaint)
        // Label
        textPaint.color = Color.WHITE
        canvas.drawText(label, width / 2f, top + height / 2f + textPaint.textSize / 3f, textPaint)
    }

    private fun drawL3BlockVertical(canvas: Canvas, top: Float, width: Float, height: Float, segments: List<Int>) {
        val bottom = top + height
        val segmentCount = segments.size
        if (segmentCount > 0) {
            val segGap = 4f
            val segWidth = (width - segGap * (segmentCount - 1)) / segmentCount
            
            for (i in 0 until segmentCount) {
                val segLeft = i * (segWidth + segGap)
                val segRight = segLeft + segWidth
                val dominoBgRect = RectF(segLeft, top, segRight, bottom)
                canvas.drawRoundRect(dominoBgRect, 3f, 3f, bgPaint)
                
                val gapY = 2f
                val halfHeight = (height - 2f - gapY) / 2f
                val segRectTop = RectF(segLeft + 1f, top + 1f, segRight - 1f, top + 1f + halfHeight)
                val segRectBottom = RectF(segLeft + 1f, top + 1f + halfHeight + gapY, segRight - 1f, bottom - 1f)
                
                fillPaint.color = segments[i]
                canvas.drawRoundRect(segRectTop, 2f, 2f, fillPaint)
                canvas.drawRoundRect(segRectBottom, 2f, 2f, fillPaint)
            }
        } else {
            val bgRect = RectF(0f, top, width, bottom)
            canvas.drawRoundRect(bgRect, 3f, 3f, bgPaint)
        }
        
        val label = "L3"
        textPaint.color = Color.BLACK
        canvas.drawText(label, width / 2f + 1f, top + height / 2f + textPaint.textSize / 3f + 1f, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(label, width / 2f, top + height / 2f + textPaint.textSize / 3f, textPaint)
    }

    private fun drawBlock(canvas: Canvas, left: Float, width: Float, height: Float, fillPct: Float, fillColor: Int, label: String) {
        val right = left + width
        
        // Background
        val bgRect = RectF(left, 0f, right, height)
        canvas.drawRoundRect(bgRect, 3f, 3f, bgPaint)

        // Fill
        val fillHeight = height * fillPct.coerceIn(0f, 1f)
        if (fillHeight > 0) {
            val fillRect = RectF(left, height - fillHeight, right, height)
            fillPaint.color = fillColor
            canvas.drawRoundRect(fillRect, 3f, 3f, fillPaint)
        }
        
        // Label shadow
        textPaint.color = Color.BLACK
        canvas.drawText(label, left + width / 2f + 1f, height / 2f + textPaint.textSize / 3f + 1f, textPaint)
        // Label
        textPaint.color = Color.WHITE
        canvas.drawText(label, left + width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
    }

    private fun drawL3Block(canvas: Canvas, left: Float, width: Float, height: Float, segments: List<Int>) {
        val right = left + width
        val segmentCount = segments.size
        if (segmentCount > 0) {
            val segGap = 4f
            val segWidth = (width - segGap * (segmentCount - 1)) / segmentCount
            
            for (i in 0 until segmentCount) {
                val segLeft = left + i * (segWidth + segGap)
                val segRight = segLeft + segWidth
                val dominoBgRect = RectF(segLeft, 0f, segRight, height)
                canvas.drawRoundRect(dominoBgRect, 3f, 3f, bgPaint)
                
                val gapY = 2f
                val halfHeight = (height - 2f - gapY) / 2f
                val segRectTop = RectF(segLeft + 1f, 1f, segRight - 1f, 1f + halfHeight)
                val segRectBottom = RectF(segLeft + 1f, 1f + halfHeight + gapY, segRight - 1f, height - 1f)
                
                fillPaint.color = segments[i]
                canvas.drawRoundRect(segRectTop, 2f, 2f, fillPaint)
                canvas.drawRoundRect(segRectBottom, 2f, 2f, fillPaint)
            }
        } else {
            val bgRect = RectF(left, 0f, right, height)
            canvas.drawRoundRect(bgRect, 3f, 3f, bgPaint)
        }
        
        val label = "L3"
        textPaint.color = Color.BLACK
        canvas.drawText(label, left + width / 2f + 1f, height / 2f + textPaint.textSize / 3f + 1f, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(label, left + width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
    }
}
