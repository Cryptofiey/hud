package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PokerHudReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        Log.d("PokerHudReceiver", "Received action: $action")

        when (action) {
            "com.example.UPDATE_CARDS" -> {
                val h1Str = intent.getStringExtra("hole1")
                val h2Str = intent.getStringExtra("hole2")
                val f1Str = intent.getStringExtra("flop1")
                val f2Str = intent.getStringExtra("flop2")
                val f3Str = intent.getStringExtra("flop3")
                val turnStr = intent.getStringExtra("turn")
                val riverStr = intent.getStringExtra("river")

                val h1 = parseCard(h1Str)
                val h2 = parseCard(h2Str)
                val f1 = parseCard(f1Str)
                val f2 = parseCard(f2Str)
                val f3 = parseCard(f3Str)
                val turn = parseCard(turnStr)
                val river = parseCard(riverStr)

                val boardList = listOf(f1, f2, f3, turn, river)
                Log.d("PokerHudReceiver", "Parsed cards - Hero: $h1, $h2 | Board: $boardList")

                PokerHudSharedState.externalActions.tryEmit(
                    ExternalAction.UpdateCards(h1, h2, boardList)
                )
            }
            "com.example.CONTROL_HUD" -> {
                val command = intent.getStringExtra("command")
                if (command != null) {
                    Log.d("PokerHudReceiver", "HUD control command: $command")
                    PokerHudSharedState.externalActions.tryEmit(
                        ExternalAction.ControlHud(command)
                    )
                }
            }
            "com.example.CLICK" -> {
                val x = intent.getFloatExtra("x", -1f)
                val y = intent.getFloatExtra("y", -1f)
                if (x >= 0f && y >= 0f) {
                    val ok = PokerAutomationService.clickAt(x, y)
                    Log.d("PokerHudReceiver", "Click action dispatched for ($x, $y) result: $ok")
                } else {
                    Log.w("PokerHudReceiver", "Invalid or missing coordinates (x, y) for click.")
                }
            }
            "com.example.SWIPE" -> {
                val startX = intent.getFloatExtra("startX", -1f)
                val startY = intent.getFloatExtra("startY", -1f)
                val endX = intent.getFloatExtra("endX", -1f)
                val endY = intent.getFloatExtra("endY", -1f)
                val duration = intent.getLongExtra("duration", 300L)
                if (startX >= 0f && startY >= 0f && endX >= 0f && endY >= 0f) {
                    val ok = PokerAutomationService.swipe(startX, startY, endX, endY, duration)
                    Log.d("PokerHudReceiver", "Swipe action dispatched result: $ok")
                } else {
                    Log.w("PokerHudReceiver", "Invalid coordinates for swipe action.")
                }
            }
        }
    }

    private fun parseCard(str: String?): Card? {
        if (str == null || str.trim().isEmpty() || str == "?" || str == "null" || str == "-") return null
        val clean = str.trim().uppercase()
        if (clean.length < 2) return null

        val suitChar = clean.last()
        val rankStr = clean.dropLast(1)

        val suit = when (suitChar) {
            'H' -> Suit.HEARTS
            'D' -> Suit.DIAMONDS
            'S' -> Suit.SPADES
            'C' -> Suit.CLUBS
            else -> return null
        }

        val rank = when (rankStr) {
            "2" -> Rank.TWO
            "3" -> Rank.THREE
            "4" -> Rank.FOUR
            "5" -> Rank.FIVE
            "6" -> Rank.SIX
            "7" -> Rank.SEVEN
            "8" -> Rank.EIGHT
            "9" -> Rank.NINE
            "10", "T" -> Rank.TEN
            "J" -> Rank.JACK
            "Q" -> Rank.QUEEN
            "K" -> Rank.KING
            "A" -> Rank.ACE
            else -> return null
        }

        return Card(rank, suit)
    }
}
