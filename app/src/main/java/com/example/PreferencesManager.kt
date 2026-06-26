package com.example

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("poker_hud_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveAdvisorSettings(settings: AdvisorSettings) {
        prefs.edit().apply {
            putBoolean("advisor_use_pip", settings.usePip)
            putBoolean("advisor_use_advanced_stats", settings.useAdvancedStats)
            putBoolean("advisor_show_recommendation", settings.showRecommendation)
            putFloat("advisor_font_scale", settings.fontScale)
            putInt("advisor_window_width_pct", settings.windowWidthPct)
            putInt("advisor_window_height_pct", settings.windowHeightPct)
            apply()
        }
    }

    fun loadAdvisorSettings(): AdvisorSettings {
        return AdvisorSettings(
            usePip = prefs.getBoolean("advisor_use_pip", true),
            useAdvancedStats = prefs.getBoolean("advisor_use_advanced_stats", true),
            showRecommendation = prefs.getBoolean("advisor_show_recommendation", true),
            fontScale = prefs.getFloat("advisor_font_scale", 1.0f),
            windowWidthPct = prefs.getInt("advisor_window_width_pct", 100),
            windowHeightPct = prefs.getInt("advisor_window_height_pct", 100)
        )
    }

    fun savePlayerStats(stats: PlayerStats) {
        val json = gson.toJson(stats)
        prefs.edit().putString("player_stats_${stats.nickname}", json).apply()
    }

    fun loadPlayerStats(nickname: String): PlayerStats {
        val json = prefs.getString("player_stats_$nickname", null)
        return if (json != null) {
            gson.fromJson(json, PlayerStats::class.java)
        } else {
            PlayerStats(nickname = nickname)
        }
    }

    fun getPlayerProfilesList(): List<String> {
        val set = mutableSetOf<String>()
        prefs.all.keys.forEach { key ->
            if (key.startsWith("player_stats_")) {
                val extracted = key.removePrefix("player_stats_")
                if (extracted.isNotEmpty()) {
                    set.add(extracted)
                }
            }
        }
        return set.toList().sorted()
    }

    fun saveOcrThreshold(threshold: Int) {
        prefs.edit().putInt("ocr_threshold", threshold).apply()
    }

    fun loadOcrThreshold(): Int {
        return prefs.getInt("ocr_threshold", 195)
    }
}
