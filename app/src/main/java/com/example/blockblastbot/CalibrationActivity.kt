package com.example.blockblastbot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Étapes de calibration : l'utilisateur choisit une capture d'écran du
 * jeu (prise manuellement avant, avec les boutons Volume-bas + Power),
 * puis tape successivement :
 *   1) coin haut-gauche de la grille 8x8
 *   2) coin bas-droit de la grille 8x8
 *   3-4) coin haut-gauche / bas-droit de la pièce n°1 (en bas de l'écran)
 *   5-6) idem pièce n°2
 *   7-8) idem pièce n°3
 *
 * Les coordonnées sont ensuite converties en pixels réels de l'écran
 * (proportionnellement à la résolution de l'image choisie) et
 * sauvegardées via Prefs.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var instructionText: TextView
    private var bitmap: Bitmap? = null

    private val steps = listOf(
        "Tape le coin HAUT-GAUCHE de la grille 8x8",
        "Tape le coin BAS-DROIT de la grille 8x8",
        "Tape le coin HAUT-GAUCHE de la pièce n°1 (bac du bas)",
        "Tape le coin BAS-DROIT de la pièce n°1",
        "Tape le coin HAUT-GAUCHE de la pièce n°2",
        "Tape le coin BAS-DROIT de la pièce n°2",
        "Tape le coin HAUT-GAUCHE de la pièce n°3",
        "Tape le coin BAS-DROIT de la pièce n°3"
    )
    private var stepIndex = 0
    private val points = mutableListOf<Point>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            contentResolver.openInputStream(uri)?.use {
                bitmap = BitmapFactory.decodeStream(it)
            }
            imageView.setImageBitmap(bitmap)
            stepIndex = 0
            points.clear()
            instructionText.text = steps[0]
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        imageView = findViewById(R.id.imageView)
        instructionText = findViewById(R.id.instructionText)

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImage.launch("image/*")
        }

        setupTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val bmp = bitmap ?: return@setOnTouchListener true
                val realPoint = viewTouchToBitmapCoords(event.x, event.y, bmp) ?: return@setOnTouchListener true

                if (stepIndex < steps.size) {
                    points.add(realPoint)
                    stepIndex++
                    if (stepIndex < steps.size) {
                        instructionText.text = steps[stepIndex]
                    } else {
                        finishCalibration(bmp)
                    }
                }
            }
            true
        }
    }

    /** Convertit une coordonnée tapée dans la Vue en coordonnée pixel réelle du bitmap affiché. */
    private fun viewTouchToBitmapCoords(viewX: Float, viewY: Float, bmp: Bitmap): Point? {
        val matrix = imageView.imageMatrix
        val inverse = android.graphics.Matrix()
        if (!matrix.invert(inverse)) return null
        val pts = floatArrayOf(viewX, viewY)
        inverse.mapPoints(pts)
        val x = pts[0].toInt().coerceIn(0, bmp.width - 1)
        val y = pts[1].toInt().coerceIn(0, bmp.height - 1)
        return Point(x, y)
    }

    private fun finishCalibration(bmp: Bitmap) {
        val gridRect = Rect(
            minOf(points[0].x, points[1].x), minOf(points[0].y, points[1].y),
            maxOf(points[0].x, points[1].x), maxOf(points[0].y, points[1].y)
        )
        val slotRects = (0..2).map { i ->
            val p1 = points[2 + i * 2]
            val p2 = points[3 + i * 2]
            Rect(
                minOf(p1.x, p2.x), minOf(p1.y, p2.y),
                maxOf(p1.x, p2.x), maxOf(p1.y, p2.y)
            )
        }
        // Note : ces coordonnées sont en pixels de l'IMAGE choisie. Si tu as
        // pris la capture d'écran sur CE téléphone (résolution identique à
        // l'écran), elles correspondent directement aux pixels réels de
        // l'écran, ce qui est le cas normal d'utilisation.
        Prefs.save(this, gridRect, slotRects)
        Toast.makeText(this, "Calibration enregistrée !", Toast.LENGTH_LONG).show()
        finish()
    }
}
