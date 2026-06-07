package com.example

import kotlinx.coroutines.flow.MutableStateFlow

object BotLogSharedState {
    val isBotLogWidgetRunning = MutableStateFlow(false)
    val widgetRect = MutableStateFlow<android.graphics.Rect?>(null)
    
    // Log content for different slots
    val logL1 = MutableStateFlow("")
    val logL2 = MutableStateFlow("")
    val logL3 = MutableStateFlow("")
    
    val logM1 = MutableStateFlow("")
    val logM2 = MutableStateFlow("")
    val logM3 = MutableStateFlow("")
    val logM4 = MutableStateFlow("")
    val logM5 = MutableStateFlow("")
    
    val logL4 = MutableStateFlow("")
    val logBot = MutableStateFlow("") // L5
    
    // Logs appending helper
    fun appendLogL(level: Int, msg: String) {
        when (level) {
            1 -> logL1.value = "${logL1.value}\n$msg"
            2 -> logL2.value = "${logL2.value}\n$msg"
            3 -> logL3.value = "${logL3.value}\n$msg"
        }
    }
    
    fun appendLogM(level: Int, msg: String) {
        when (level) {
            1 -> logM1.value = "${logM1.value}\n$msg"
            2 -> logM2.value = "${logM2.value}\n$msg"
            3 -> logM3.value = "${logM3.value}\n$msg"
            4 -> logM4.value = "${logM4.value}\n$msg"
            5 -> logM5.value = "${logM5.value}\n$msg"
        }
    }
    
    fun appendLogL4(msg: String) {
        logL4.value = "${logL4.value}\n$msg"
    }
    
    fun appendLogBot(msg: String) {
        logBot.value = "${logBot.value}\n$msg"
    }
}
