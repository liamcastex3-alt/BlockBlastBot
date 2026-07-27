package com.example.blockblastbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BlockBlastAccessibilityService : AccessibilityService() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Combien de temps entre chaque analyse d'écran / cycle de jeu.
    private val CYCLE_DELAY_MS = 700L
    // Temps laissé au jeu pour animer un placement / effacement de ligne.
    private val AFTER_MOVE_DELAY_MS = 450L
    // Seuil de saturation HSV au-dessus duquel un pixel est considéré "rempli"
    // (un bloc coloré) plutôt que fond de plateau (généralement gris/terne).
    private val SATURATION_THRESHOLD = 0.22f
    private val MAX_PIECE_GRID = 5

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BotState.ACTION_START -> startCapture()
                BotState.ACTION_STOP -> stopCapture()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(BotState.ACTION_START)
            addAction(BotState.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDensity = dm.densityDpi

        // Si le bot a déjà reçu l'autorisation avant que le service ne se
        // (re)connecte, on démarre directement.
        if (BotState.shouldRun && BotState.projectionData != null) {
            startCapture()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun startForegroundNotification() {
        val channelId = "blockblastbot_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "BlockBlast Bot", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BlockBlast Bot actif")
            .setContentText("Le bot joue à ta place...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        startForeground(1, notification)
    }

    private fun startCapture() {
        val resultCode = BotState.projectionResultCode ?: return
        val data = BotState.projectionData ?: return

        startForegroundNotification()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BlockBlastBotCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        loopJob?.cancel()
        loopJob = scope.launch { gameLoop() }
    }

    private fun stopCapture() {
        loopJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun gameLoop() {
        while (BotState.shouldRun) {
            try {
                val bitmap = captureBitmap()
                if (bitmap != null) {
                    val gridRect = Prefs.loadGridRect(applicationContext)
                    val slotRects = Prefs.loadSlotRects(applicationContext)

                    val board = detectBoard(bitmap, gridRect)
                    val pieces = slotRects.map { detectPiece(bitmap, it) }

                    // On ne garde que les pièces non vides (une pièce déjà
                    // utilisée dans le lot actuel apparaît comme un emplacement vide).
                    val activeIndexed = pieces.mapIndexedNotNull { i, p ->
                        if (p.cells.isNotEmpty()) i to p else null
                    }

                    if (activeIndexed.isNotEmpty()) {
                        val activePieces = activeIndexed.map { it.second }
                        val result = Solver.solve(board, activePieces)
                        if (result != null) {
                            for (placement in result.order) {
                                val originalSlotIndex = activeIndexed[placement.pieceIndex].first
                                executePlacement(placement, slotRects[originalSlotIndex], gridRect)
                                delay(AFTER_MOVE_DELAY_MS)
                            }
                        }
                    }
                    bitmap.recycle()
                }
            } catch (t: Throwable) {
                // On avale les erreurs ponctuelles (ex: image pas encore prête)
                // pour ne pas tuer toute la boucle du bot.
            }
            delay(CYCLE_DELAY_MS)
        }
    }

    private fun captureBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close()
        }
    }

    private fun isFilled(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        // Rempli si couleur saturée (bloc coloré) OU très claire/blanche
        // (certains skins utilisent des blocs blancs/pastel peu saturés).
        return hsv[1] > SATURATION_THRESHOLD || hsv[2] > 0.92f
    }

    private fun averageColor(bitmap: Bitmap, cx: Int, cy: Int, radius: Int = 4): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val x0 = (cx - radius).coerceAtLeast(0)
        val x1 = (cx + radius).coerceAtMost(bitmap.width - 1)
        val y0 = (cy - radius).coerceAtLeast(0)
        val y1 = (cy + radius).coerceAtMost(bitmap.height - 1)
        for (x in x0..x1) {
            for (y in y0..y1) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
        }
        if (n == 0L) return Color.BLACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun detectBoard(bitmap: Bitmap, gridRect: Rect): Long {
        var board = 0L
        val cellW = gridRect.width() / 8.0
        val cellH = gridRect.height() / 8.0
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cx = (gridRect.left + (col + 0.5) * cellW).toInt()
                val cy = (gridRect.top + (row + 0.5) * cellH).toInt()
                val color = averageColor(bitmap, cx, cy)
                if (isFilled(color)) board = board or Board.bit(row, col)
            }
        }
        return board
    }

    private fun detectPiece(bitmap: Bitmap, slotRect: Rect): Piece {
        val cellW = slotRect.width() / MAX_PIECE_GRID.toDouble()
        val cellH = slotRect.height() / MAX_PIECE_GRID.toDouble()
        val rawCells = mutableListOf<Pair<Int, Int>>()
        for (row in 0 until MAX_PIECE_GRID) {
            for (col in 0 until MAX_PIECE_GRID) {
                val cx = (slotRect.left + (col + 0.5) * cellW).toInt()
                val cy = (slotRect.top + (row + 0.5) * cellH).toInt()
                val color = averageColor(bitmap, cx, cy, radius = 3)
                if (isFilled(color)) rawCells.add(Pair(row, col))
            }
        }
        return Piece.normalize(rawCells)
    }

    private fun executePlacement(placement: Placement, slotRect: Rect, gridRect: Rect) {
        // Point de départ du glisser : centre de la pièce dans son bac.
        val startX = slotRect.centerX()
        val startY = slotRect.centerY()

        // Point d'arrivée : centre de la zone occupée par la pièce une fois
        // posée sur la grille, à la position choisie par le solveur.
        val cellW = gridRect.width() / 8.0
        val cellH = gridRect.height() / 8.0
        val targetRowCenter = placement.row + placement.piece.height / 2.0
        val targetColCenter = placement.col + placement.piece.width / 2.0
        val endX = (gridRect.left + targetColCenter * cellW).toInt()
        val endY = (gridRect.top + targetRowCenter * cellH).toInt()

        val offset = Prefs.dragLiftOffsetPx(applicationContext)
        dispatchDrag(
            Point(startX, startY),
            Point(endX + offset.x, endY + offset.y)
        )
    }

    private fun dispatchDrag(start: Point, end: Point) {
        val path = Path()
        path.moveTo(start.x.toFloat(), start.y.toFloat())
        path.lineTo(end.x.toFloat(), end.y.toFloat())

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }
}
