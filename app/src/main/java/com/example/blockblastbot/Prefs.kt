package com.example.blockblastbot

import android.content.Context
import android.graphics.Point
import android.graphics.Rect

/**
 * Sauvegarde la calibration faite une fois par l'utilisateur :
 * - le rectangle de la grille 8x8
 * - les rectangles des 3 emplacements de pièces en bas de l'écran
 *
 * Ces valeurs dépendent du modèle de téléphone et de la mise en page
 * du jeu, donc elles doivent être calibrées manuellement une seule fois
 * via CalibrationActivity.
 */
object Prefs {
    private const val FILE = "blockblastbot_prefs"

    fun save(context: Context, gridRect: Rect, slotRects: List<Rect>) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        p.putInt("grid_l", gridRect.left)
        p.putInt("grid_t", gridRect.top)
        p.putInt("grid_r", gridRect.right)
        p.putInt("grid_b", gridRect.bottom)
        for (i in slotRects.indices) {
            p.putInt("slot_${i}_l", slotRects[i].left)
            p.putInt("slot_${i}_t", slotRects[i].top)
            p.putInt("slot_${i}_r", slotRects[i].right)
            p.putInt("slot_${i}_b", slotRects[i].bottom)
        }
        p.putBoolean("calibrated", true)
        p.apply()
    }

    fun isCalibrated(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("calibrated", false)

    fun loadGridRect(context: Context): Rect {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Rect(
            sp.getInt("grid_l", 0),
            sp.getInt("grid_t", 0),
            sp.getInt("grid_r", 100),
            sp.getInt("grid_b", 100)
        )
    }

    fun loadSlotRects(context: Context): List<Rect> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return (0..2).map { i ->
            Rect(
                sp.getInt("slot_${i}_l", 0),
                sp.getInt("slot_${i}_t", 0),
                sp.getInt("slot_${i}_r", 100),
                sp.getInt("slot_${i}_b", 100)
            )
        }
    }

    /** Décalage tactile (drag offset) entre le point où on "attrape" la
     * pièce et le point où elle atterrit réellement dans la grille.
     * Beaucoup de jeux de puzzle décalent la pièce vers le haut du doigt
     * pour qu'elle reste visible pendant le glisser. A ajuster par essai/erreur. */
    fun dragLiftOffsetPx(context: Context): Point {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Point(sp.getInt("drag_off_x", 0), sp.getInt("drag_off_y", -150))
    }

    fun saveDragOffset(context: Context, dx: Int, dy: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("drag_off_x", dx).putInt("drag_off_y", dy).apply()
    }
}
