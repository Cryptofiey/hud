package com.example

import android.graphics.Bitmap
import android.graphics.Rect

object CardSegmenter {
    
    data class CardSlot(val index: Int, val rect: Rect, val rankZone: Rect)

    fun findCardSlots(bmp: Bitmap, searchRegion: Rect, maxCards: Int): List<CardSlot> {
        val slots = mutableListOf<CardSlot>()
        val w = searchRegion.width()
        val h = searchRegion.height()
        if (w <= 0 || h <= 0) return slots
        
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, searchRegion.left, searchRegion.top, w, h)
        
        // Luminance projection horizontally across the middle 50% (Y from 25% to 75%)
        val colLumaAvg = IntArray(w)
        val startY = (h * 0.25f).toInt()
        val endY = (h * 0.75f).toInt()
        val scanLines = endY - startY
        
        for (x in 0 until w) {
            var sum = 0
            for (y in startY until endY) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
            colLumaAvg[x] = sum / scanLines
        }
        
        var firstCardLeft = -1
        for (x in 3 until w - 3) {
            val prevLuma = colLumaAvg[x - 3]
            val currLuma = colLumaAvg[x]
            val diff = currLuma - prevLuma
            
            // A sharp increase in brightness means we hit the left edge of a card
            if (diff > 15) { 
                firstCardLeft = x - 1
                break
            }
        }
        
        // If we failed to find an edge, fallback to uniform splitting
        if (firstCardLeft == -1) {
             val slotW = w / maxCards.toFloat()
             for (i in 0 until maxCards) {
                 val cLeft = searchRegion.left + (i * slotW).toInt()
                 val cRight = searchRegion.left + ((i+1) * slotW).toInt()
                 val rect = Rect(cLeft, searchRegion.top, cRight, searchRegion.bottom)
                 
                 // Rank zone: left 45%, top 35%
                 val rankRect = Rect(rect.left, rect.top, rect.left + (rect.width() * 0.45).toInt(), rect.top + (rect.height() * 0.35).toInt())
                 slots.add(CardSlot(i, rect, rankRect))
             }
             return slots
        }
        
        // Find right edge of the first card
        var firstCardRight = -1
        val minWidth = (w / maxCards) * 0.7f
        for (x in (firstCardLeft + minWidth.toInt()) until w - 3) {
            val currLuma = colLumaAvg[x]
            val nextLuma = colLumaAvg[x + 3]
            val diff = currLuma - nextLuma // Dropout in brightness
            
            if (diff > 15) {
                firstCardRight = x + 1
                break
            }
        }
        
        if (firstCardRight == -1 || firstCardRight <= firstCardLeft) {
            firstCardRight = firstCardLeft + (w / maxCards.toFloat()).toInt()
        }
        
        val actualCardWidth = firstCardRight - firstCardLeft
        
        var currentX = firstCardLeft
        for (i in 0 until maxCards) {
             val cRight = Math.min((currentX + actualCardWidth), w)
             val rect = Rect(searchRegion.left + currentX, searchRegion.top, searchRegion.left + cRight, searchRegion.bottom)
             
             // Rank zone: left 40%, top 32% (tight constraints to avoid suit completely)
             val rankRect = Rect(rect.left, rect.top, rect.left + (rect.width() * 0.40).toInt(), rect.top + (rect.height() * 0.32).toInt())
             slots.add(CardSlot(i, rect, rankRect))
             
             var nextLeft = currentX + actualCardWidth
             for (x in nextLeft until Math.min(nextLeft + (actualCardWidth * 0.5f).toInt(), w - 3)) {
                 val diff = colLumaAvg[x] - colLumaAvg[Math.max(0, x - 3)]
                 if (diff > 15) {
                     nextLeft = x
                     break
                 }
             }
             // If we didn't find the next valid left edge, assume standard gap (like 3-5% of width)
             if (nextLeft == currentX + actualCardWidth) {
                 nextLeft += (actualCardWidth * 0.05).toInt()
             }
             currentX = nextLeft
             if (currentX >= w) break
        }
        
        return slots
    }
}
