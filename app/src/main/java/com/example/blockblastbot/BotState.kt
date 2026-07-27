package com.example.blockblastbot

import android.content.Intent

/**
 * Petit objet global (singleton) qui sert de "boîte aux lettres" entre
 * MainActivity (qui obtient la permission de capture d'écran, laquelle
 * ne peut être demandée que depuis une Activity) et le
 * BlockBlastAccessibilityService (qui fait tourner la boucle de jeu).
 */
object BotState {
    var projectionResultCode: Int? = null
    var projectionData: Intent? = null

    @Volatile
    var shouldRun: Boolean = false

    const val ACTION_START = "com.example.blockblastbot.ACTION_START"
    const val ACTION_STOP = "com.example.blockblastbot.ACTION_STOP"
}
