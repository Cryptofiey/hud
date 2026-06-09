package com.example

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// Shared object to communicate with MainActivity and PokerViewModel safely
sealed class ExternalAction {
    data class UpdateCards(
        val hero1: Card?,
        val hero2: Card?,
        val board: List<Card?>,
        val opponents: List<OpponentState> = emptyList(),
        val profileBoxes: List<ScannedBox>? = null,
        val updateProfileBoxes: Boolean = false,
        val rawScannerBoxes: List<ScannedBox>? = null,
        val potSize: Float? = null,
        val heroActionOptions: List<String> = emptyList(),
        val heroTurn: Boolean = false,
        val heroStack: Float? = null,
        val heroBet: Float? = null,
        val tablePosition: TablePosition? = null,
        val smallBlind: Float? = null,
        val bigBlind: Float? = null,
        val tournamentStage: TournamentStage? = null,
        val isBbDisplay: Boolean = false
    ) : ExternalAction()
    data class ControlHud(val command: String) : ExternalAction()
}

enum class AppScreenState {
    APP_UNKNOWN,
    COINPOKER_TABLE,
    COINPOKER_LOBBY,
    COINPOKER_PROFILE,
    COINPOKER_KNOWN, // Kept for backwards compatibility if needed
    COINPOKER_UNKNOWN
}

object PokerHudSharedState {
    val isHudOverlayRunning = MutableStateFlow(false)
    val appScreenContext = MutableStateFlow(AppScreenState.APP_UNKNOWN)
    val uiState = MutableStateFlow<PokerUiState>(PokerUiState())
    val triggerPreset = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val externalActions = MutableSharedFlow<ExternalAction>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // Scanner tuning
    val showScannerBoxes = MutableStateFlow(false)
    val triggerProfileScan = MutableStateFlow(false)
    val isAutoProfileScanningEnabled = MutableStateFlow(false)
    val scannerOffsetX = MutableStateFlow(0f)
    val scannerOffsetY = MutableStateFlow(0f)
    
    // Advisor Filters
    val useVpipPfrFilter = MutableStateFlow(true)
    val useWsdWtsdFilter = MutableStateFlow(true)

    // Toggles for overlay layout customization
    val winProbToggle = MutableStateFlow(true)
    val handStrengthToggle = MutableStateFlow(true)
    val sklanskyToggle = MutableStateFlow(true)
    val advStatsToggle = MutableStateFlow(true)
    val showActionAdvisor = MutableStateFlow(true)
    val multiDataScannerToggle = MutableStateFlow(true)
    val isGameMode = MutableStateFlow(false)
    val hudScale = MutableStateFlow(1.0f)
    val hudOpacity = MutableStateFlow(0.9f)

    // Crop box overlays toggles and scanning state
    val showCommBox = MutableStateFlow(true)
    val showHoleBox = MutableStateFlow(true)
    val showProbsBox = MutableStateFlow(true)
    val isProbsHudVertical = MutableStateFlow(false)
    val isProbsHudMinimized = MutableStateFlow(false)
    val isControllerHudVertical = MutableStateFlow(false)
    val isControllerHudMinimized = MutableStateFlow(false)
    val isScanning = MutableStateFlow(false)
    val isUserInteracting = MutableStateFlow(false)
    // Modules basic settings
    val winProbScale = MutableStateFlow(1f)
    val winProbMaxHands = MutableStateFlow(4)
    
    val handStrengthScale = MutableStateFlow(1f)
    
    val sklanskyScale = MutableStateFlow(1f)
    
    val advStatsScale = MutableStateFlow(1f)
    val advMinHands = MutableStateFlow(0)
    
    val actionAdvisorScale = MutableStateFlow(1f)
    val actionAdvisorAggression = MutableStateFlow(0) // 0 = normal, 1 = aggressive, 2 = safe

    val isPasswordHidingActive = MutableStateFlow(false)
    private var passwordHidingJob: kotlinx.coroutines.Job? = null
    private val passwordHidingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    fun triggerPasswordHiding() {
        if (isPasswordHidingActive.value) {
            // Extend the hide timer if already hiding
            passwordHidingJob?.cancel()
        }
        isPasswordHidingActive.value = true
        passwordHidingJob = passwordHidingScope.launch {
            kotlinx.coroutines.delay(7000)
            isPasswordHidingActive.value = false
        }
    }
}

class PokerHudService : Service() {

    companion object {
        var instance: PokerHudService? = null
            private set
    }

    fun getLatestScannerBitmap(): Bitmap? {
        return screenScanner?.getLatestBitmapCopy()
    }

    private var windowManager: WindowManager? = null
    private var floatingOverlayView: FrameLayout? = null
    private var isOverlayShowing = false
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Separate overlays for Calibrations Bounding boxes and other independent widgets
    private var floatingCommOverlay: FrameLayout? = null
    private var commJob: Job? = null
    private var commLaserAnim: ValueAnimator? = null
    private var floatingHoleOverlay: FrameLayout? = null
    private var holeJob: Job? = null
    private var holeLaserAnim: ValueAnimator? = null
    private var floatingProbsOverlay: FrameLayout? = null
    private var probsJob: Job? = null
    private var floatingAdvisorOverlay: FrameLayout? = null
    private var floatingScannerOverlay: ScannerBoxesView? = null
    private var scannerOverlayJob: Job? = null
    private var advisorJob: Job? = null

    private var commHeader: View? = null
    private var commTxt: View? = null
    private var holeHeader: View? = null
    private var holeTxt: View? = null
    private var floatingOpponentsOverlay: FrameLayout? = null
    private var oppJob: Job? = null
    private var screenScanner: ScreenScanner? = null
    private var interactionJob: Job? = null

    private fun notifyInteraction() {
        PokerHudSharedState.isUserInteracting.value = true
        interactionJob?.cancel()
        interactionJob = serviceScope.launch {
            kotlinx.coroutines.delay(5000)
            PokerHudSharedState.isUserInteracting.value = false
        }
    }

    // Automated VPIP/PFR hand tracking state definitions
    private var lastHandKey: String? = null
    private val countedHandPlayers = mutableSetOf<String>()
    private val countedVpipPlayers = mutableSetOf<String>()
    private val countedPfrPlayers = mutableSetOf<String>()
    private val countedShowdownPlayers = mutableSetOf<String>()

    // Layout components
    private var expandedLayout: LinearLayout? = null
    private var minimizedLayout: LinearLayout? = null

    // Multi-Data Scanner fields
    private var txtScannerStatus: TextView? = null
    private var scannerStatusBox: LinearLayout? = null
    private var txtPreText: TextView? = null
    private var togglesRow1: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        PokerHudSharedState.isHudOverlayRunning.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_HUD") {
            stopFloatingOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        showFloatingOverlay()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "poker_hud_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Poker HUD Active Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("Poker HUD Overlay Active")
            .setContentText("Displaying live equity calculations and stats overlay.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = 0
                if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                
                if (PokerHudSharedState.multiDataScannerToggle.value && ScannerConfig.pendingProjectionData != null) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                
                if (type == 0) {
                    startForeground(717, notification)
                } else {
                    startForeground(717, notification, type)
                }

                if (PokerHudSharedState.multiDataScannerToggle.value && ScannerConfig.pendingProjectionData != null) {
                    try {
                        if (ScannerConfig.activeProjection == null) {
                            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            ScannerConfig.activeProjection = projectionManager.getMediaProjection(ScannerConfig.pendingProjectionResultCode, ScannerConfig.pendingProjectionData!!)
                        }
                    } catch(e: Exception) {
                        android.util.Log.e("PokerHudService", "Failed to getMediaProjection", e)
                    }
                }
            } else {
                startForeground(717, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("PokerHudService", "Failed to startForeground", e)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    enum class CutoutCorner { BOTTOM_RIGHT, BOTTOM_LEFT }

    private class CutoutBackgroundDrawable(
        private val bgColor: Int,
        private val cornerRadiusPx: Float,
        private val strokeWidthPx: Float,
        private val strokeColor: Int,
        private val cutoutRadiusPx: Float,
        private val cutoutCorner: CutoutCorner = CutoutCorner.BOTTOM_RIGHT
    ) : android.graphics.drawable.Drawable() {

        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = bgColor
        }
        
        private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = strokeColor
            strokeWidth = strokeWidthPx
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val sw = strokeWidthPx / 2f
            val w = bounds.width().toFloat() - sw
            val h = bounds.height().toFloat() - sw
            val x0 = sw
            val y0 = sw
            val R = cutoutRadiusPx

            val path = android.graphics.Path()

            if (cutoutCorner == CutoutCorner.BOTTOM_RIGHT) {
                path.moveTo(x0, y0 + cornerRadiusPx)
                path.quadTo(x0, y0, x0 + cornerRadiusPx, y0)
                path.lineTo(w - cornerRadiusPx, y0)
                path.quadTo(w, y0, w, y0 + cornerRadiusPx)
                
                path.lineTo(w, h - R)
                val rectInfo = android.graphics.RectF(w - R, h - R, w + R, h + R)
                path.arcTo(rectInfo, 270f, -90f, false)
                
                path.lineTo(x0 + cornerRadiusPx, h)
                path.quadTo(x0, h, x0, h - cornerRadiusPx)
            } else {
                path.moveTo(w, h - cornerRadiusPx)
                path.quadTo(w, h, w - cornerRadiusPx, h)
                path.lineTo(x0 + R, h)
                
                val rectInfo = android.graphics.RectF(x0 - R, h - R, x0 + R, h + R)
                path.arcTo(rectInfo, 0f, -90f, false)
                
                path.lineTo(x0, y0 + cornerRadiusPx)
                path.quadTo(x0, y0, x0 + cornerRadiusPx, y0)
                path.lineTo(w - cornerRadiusPx, y0)
                path.quadTo(w, y0, w, y0 + cornerRadiusPx)
            }
            path.close()

            canvas.drawPath(path, fillPaint)
            if (strokeWidthPx > 0f) {
                canvas.drawPath(path, strokePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun createBackgroundDrawable(bgColor: Int, cornerRadiusDp: Float, strokeWidthPx: Int = 0, strokeColor: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * resources.displayMetrics.density
            setColor(bgColor)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun createDynamicGlow(active: Boolean, strokeColorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                val cleanedColor = strokeColorHex.removePrefix("#").removePrefix("FF")
                val coloredBackHex = "#3F$cleanedColor"
                setColor(AndroidColor.parseColor(coloredBackHex))
                setStroke(dpToPx(1.5f), AndroidColor.parseColor(strokeColorHex))
            } else {
                setColor(AndroidColor.parseColor("#22666666"))
                setStroke(dpToPx(1f), AndroidColor.parseColor("#44888888"))
            }
        }
    }

    private val buttonAnimators = java.util.WeakHashMap<android.view.View, android.animation.ValueAnimator>()

    private fun animateButtonPulse(view: android.view.View, active: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            buttonAnimators[view]?.cancel()
            buttonAnimators.remove(view)

            if (active) {
                // High-fidelity alpha breathing pulsation that is 100% clip-proof and looks extremely high-tech
                val animator = android.animation.ValueAnimator.ofFloat(0.55f, 1.0f).apply {
                    duration = 1100
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { animation ->
                        val alphaVal = animation.animatedValue as Float
                        view.alpha = alphaVal
                        // Subtle safe scale that never exceeds boundaries
                        view.scaleX = 1.02f
                        view.scaleY = 1.02f
                    }
                }
                buttonAnimators[view] = animator
                animator.start()
            } else {
                // Dimmed when inactive so the state change is instantly clear physically
                view.alpha = 0.45f
                view.scaleX = 0.95f
                view.scaleY = 0.95f
            }
        }
    }

    private fun formatCardColoredText(card: Card?): String {
        if (card == null) return "?"
        val suitChar = when (card.suit) {
            Suit.HEARTS -> "♥"
            Suit.DIAMONDS -> "♦"
            Suit.CLUBS -> "♣"
            Suit.SPADES -> "♠"
        }
        val rankStr = when (card.rank) {
            Rank.ACE -> "A"
            Rank.KING -> "K"
            Rank.QUEEN -> "Q"
            Rank.JACK -> "J"
            Rank.TEN -> "10"
            else -> card.rank.value.toString()
        }
        return "$rankStr$suitChar"
    }

    private fun formatCardRaw(card: Card?): String {
        if (card == null) return "?"
        val suitChar = when (card.suit) {
            Suit.HEARTS -> "h"
            Suit.DIAMONDS -> "d"
            Suit.CLUBS -> "c"
            Suit.SPADES -> "s"
        }
        val rankStr = when (card.rank) {
            Rank.ACE -> "A"
            Rank.KING -> "K"
            Rank.QUEEN -> "Q"
            Rank.JACK -> "J"
            Rank.TEN -> "T"
            else -> card.rank.value.toString()
        }
        return "$rankStr$suitChar"
    }

    private fun showFloatingOverlay() {
        if (isOverlayShowing) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dpToPx(2f)
            y = dpToPx(120f)
        }

        // Parent FrameLayout containing both expanded panel & minimized handle
        val parentFrame = FrameLayout(this)
        floatingOverlayView = parentFrame

        // 1. MINIMIZED BADGE LAYOUT (Pill)
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#EE121A24"), // Slate blue semitransparent
                16f,
                dpToPx(1.5f),
                AndroidColor.parseColor("#FF00FFCC") // Neon Cyan
            )
            visibility = View.GONE // Hidden by default
        }
        minimizedLayout = mini

        val txtMiniIcon = TextView(this).apply {
            text = "📊"
            textSize = 14f
        }
        val txtMiniLabel = TextView(this).apply {
            text = " POKER HUD"
            setTextColor(AndroidColor.WHITE)
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(2f), 0, 0, 0)
        }
        mini.addView(txtMiniIcon)
        mini.addView(txtMiniLabel)
        parentFrame.addView(mini)

        // 2. EXPANDED HUD LAYOUT
        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            background = CutoutBackgroundDrawable(
                AndroidColor.parseColor("#F50D151D"), // Dark blue/grey high contrast
                dpToPx(10f).toFloat(),
                dpToPx(2f).toFloat(),
                AndroidColor.parseColor("#FFD500F9"), // Neon Purple Outline
                dpToPx(28f).toFloat() // Reduced Cutout radius for player avatar to avoid overflow
            )
            val shadowParams = FrameLayout.LayoutParams(dpToPx(200f), WindowManager.LayoutParams.WRAP_CONTENT)
            layoutParams = shadowParams
        }
        expandedLayout = expanded

        // HEADER ROW (Title | Loop orient btn | Exit btn)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(2f))
            }
        }

        val txtTitle = TextView(this).apply {
            text = "HUD"
            setTextColor(AndroidColor.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dpToPx(2f), 0)
            }
        }

        val btnSwitchToVert = TextView(this).apply {
            text = "🔁"
            textSize = 8f
            setTextColor(AndroidColor.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(14f), dpToPx(14f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(0, 0, dpToPx(2f), 0)
            }
            setOnClickListener {
                val isVert = PokerHudSharedState.isControllerHudVertical.value
                val isMin = PokerHudSharedState.isControllerHudMinimized.value
                if (!isVert && !isMin) {
                    PokerHudSharedState.isControllerHudVertical.value = true
                    PokerHudSharedState.isControllerHudMinimized.value = false
                } else if (isVert && !isMin) {
                    PokerHudSharedState.isControllerHudVertical.value = false
                    PokerHudSharedState.isControllerHudMinimized.value = true
                } else {
                    PokerHudSharedState.isControllerHudVertical.value = false
                    PokerHudSharedState.isControllerHudMinimized.value = false
                }
            }
        }

        val readProfileBtn = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "ПРОФИЛЬ"
            textSize = 6f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF1976D2"), 4f)
            setPadding(dpToPx(3f), dpToPx(1f), dpToPx(3f), dpToPx(1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(18f)).apply {
                setMargins(0, 0, dpToPx(1f), 0)
            }
            setOnClickListener {
                if (ScannerConfig.isProjectionGranted.value && ScannerConfig.pendingProjectionData != null) {
                    val originalVisibility = floatingOverlayView?.visibility ?: View.VISIBLE
                    floatingOverlayView?.visibility = View.INVISIBLE
                    floatingCommOverlay?.visibility = View.INVISIBLE
                    floatingHoleOverlay?.visibility = View.INVISIBLE
                    floatingProbsOverlay?.visibility = View.INVISIBLE
                    floatingScannerOverlay?.visibility = View.INVISIBLE
                    
                    serviceScope.launch {
                        kotlinx.coroutines.delay(2000)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            floatingOverlayView?.visibility = originalVisibility
                            updateBoxOverlays()
                        }
                    }

                    if (screenScanner != null) {
                        screenScanner?.requestProfileScan = true
                    } else {
                        startForegroundServiceNotification()
                        val tempScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode, stopAfterProfileScan = true)
                        tempScanner.start()
                    }
                } else {
                    android.widget.Toast.makeText(this@PokerHudService, "Enable Screen Projection first", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnFrames = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "РАМКИ"
            textSize = 6f
            setTextColor(AndroidColor.WHITE)
            setPadding(dpToPx(3f), dpToPx(1f), dpToPx(3f), dpToPx(1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(18f)).apply {
                setMargins(0, 0, dpToPx(1f), 0)
            }
            setOnClickListener {
                PokerHudSharedState.showScannerBoxes.value = !PokerHudSharedState.showScannerBoxes.value
            }
        }
        serviceScope.launch {
            PokerHudSharedState.showScannerBoxes.collect { active ->
                btnFrames.background = createBackgroundDrawable(
                    if (active) AndroidColor.parseColor("#FFD500F9") else AndroidColor.parseColor("#FF37474F"), 4f
                )
            }
        }

        val btnMinimize = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "СКРЫТЬ"
            textSize = 6f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF37474F"), 4f)
            setPadding(dpToPx(3f), dpToPx(1f), dpToPx(3f), dpToPx(1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(18f)).apply {
                setMargins(0, 0, dpToPx(1f), 0)
            }
            setOnClickListener {
                PokerHudSharedState.isControllerHudMinimized.value = true
            }
        }

        val btnExit = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "ВЫХОД"
            textSize = 6f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF1E88E5"), 4f)
            setPadding(dpToPx(3f), dpToPx(1f), dpToPx(3f), dpToPx(1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(18f)).apply {
                setMargins(0, 0, 0, 0)
            }
            setOnClickListener {
                stopFloatingOverlay()
                stopSelf()
            }
        }

        headerRow.addView(txtTitle)
        headerRow.addView(btnSwitchToVert)
        headerRow.addView(readProfileBtn)
        headerRow.addView(btnFrames)
        headerRow.addView(btnMinimize)
        headerRow.addView(btnExit)
        expanded.addView(headerRow)

        // Divider
        val divider1 = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                setMargins(0, dpToPx(2f), 0, dpToPx(2f))
            }
        }
        expanded.addView(divider1)

        // MULTI-DATA SCANNER STATUS BOX
        val scannerBoxLocal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            background = createBackgroundDrawable(AndroidColor.parseColor("#1500FFCC"), 4f, dpToPx(1f), AndroidColor.parseColor("#3300FFCC"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dpToPx(2f))
            }
        }
        scannerStatusBox = scannerBoxLocal

        val scannerTxt = TextView(this).apply {
            text = "🔍 🔴 Стадия/Борд: Ждем..."
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        txtScannerStatus = scannerTxt
        scannerBoxLocal.addView(scannerTxt)
        
        // Hide scanner logs from HUD: expanded.addView(scannerBoxLocal)

        val toggles1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dpToPx(4f), 0, dpToPx(4f))
            }
        }
        this.togglesRow1 = toggles1

        fun createToggleButton(icon: String, flow: StateFlow<Boolean>): TextView {
            val tv = TextView(this).apply {
                text = icon
                textSize = 7.0f
                gravity = Gravity.CENTER
                setTextColor(AndroidColor.WHITE)
                background = createBackgroundDrawable(if (flow.value) AndroidColor.parseColor("#FFD500F9") else AndroidColor.parseColor("#FF37474F"), 6f)
                setPadding(0, dpToPx(2f), 0, dpToPx(2f))
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(18f), 1f).apply {
                    setMargins(dpToPx(1f), 0, dpToPx(1f), 0)
                }
                setOnClickListener {
                    if (flow is MutableStateFlow<Boolean>) {
                        flow.value = !flow.value
                    }
                }
            }
            serviceScope.launch {
                flow.collect { active ->
                    tv.background = createBackgroundDrawable(if (active) AndroidColor.parseColor("#FFD500F9") else AndroidColor.parseColor("#FF37474F"), 6f)
                }
            }
            return tv
        }

        val commCheckBox = createToggleButton("Борд", PokerHudSharedState.showCommBox)
        val holeCheckBox = createToggleButton("Карты", PokerHudSharedState.showHoleBox)
        val probsCheckBox = createToggleButton("Статы", PokerHudSharedState.showProbsBox)
        
        val debugSnapBtn = TextView(this).apply {
            text = "📷 ДЕБАГ"
            textSize = 6.5f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FFE57373"), 6f) // Modern Soft Neon Red
            setPadding(0, dpToPx(2f), 0, dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(18f), 1f).apply {
                setMargins(dpToPx(1f), 0, dpToPx(1f), 0)
            }
            setOnClickListener {
                serviceScope.launch {
                    DebugLogManager.triggerDiagnosticCapture(this@PokerHudService)
                }
            }
        }
        
        toggles1.addView(commCheckBox)
        toggles1.addView(holeCheckBox)
        toggles1.addView(probsCheckBox)
        toggles1.addView(debugSnapBtn)
        expanded.addView(toggles1)

        parentFrame.addView(expanded)

        // 2.5 VERTICAL SPECIFIC LAYOUTS FOR CONTROLLER
        val vertToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(2f)
            }
            visibility = View.GONE
        }
        
        val btnSwitchToHoriz = TextView(this).apply {
            text = "🔁"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                PokerHudSharedState.isControllerHudVertical.value = false
                PokerHudSharedState.isControllerHudMinimized.value = true
            }
        }
        
        val btnCloseVert = TextView(this).apply {
            text = "❌"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                stopFloatingOverlay()
                stopSelf()
            }
        }
        vertToolbar.addView(btnSwitchToHoriz)
        vertToolbar.addView(btnCloseVert)
        
        expanded.addView(vertToolbar, 0) // Prepend inside expanded container!

        val emojiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(4f)
            }
            visibility = View.GONE
        }

        fun createControllerEmojiButton(emoji: String, flow: StateFlow<Boolean>, strokeColorHex: String): TextView {
            val sizePx = dpToPx(26f)
            val btn = TextView(this).apply {
                text = emoji
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(0, dpToPx(3f), 0, dpToPx(3f))
                }
                setOnClickListener {
                    if (flow is MutableStateFlow<Boolean>) {
                        flow.value = !flow.value
                    }
                }
            }
            serviceScope.launch {
                flow.collect { active ->
                    btn.background = createDynamicGlow(active, strokeColorHex)
                    animateButtonPulse(btn, active)
                }
            }
            return btn
        }

        val btnProfile = TextView(this).apply {
            text = "👤"
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(26f), dpToPx(26f)).apply {
                setMargins(0, dpToPx(3f), 0, dpToPx(3f))
            }
            background = createDynamicGlow(true, "#FFD500F9")
            setOnClickListener {
                if (ScannerConfig.isProjectionGranted.value && ScannerConfig.pendingProjectionData != null) {
                    val originalVisibility = floatingOverlayView?.visibility ?: View.VISIBLE
                    floatingOverlayView?.visibility = View.INVISIBLE
                    floatingCommOverlay?.visibility = View.INVISIBLE
                    floatingHoleOverlay?.visibility = View.INVISIBLE
                    floatingProbsOverlay?.visibility = View.INVISIBLE
                    floatingScannerOverlay?.visibility = View.INVISIBLE
                    
                    serviceScope.launch {
                        kotlinx.coroutines.delay(2000)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            floatingOverlayView?.visibility = originalVisibility
                            updateBoxOverlays()
                        }
                    }

                    if (screenScanner != null) {
                        screenScanner?.requestProfileScan = true
                    } else {
                        startForegroundServiceNotification()
                        val tempScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode, stopAfterProfileScan = true)
                        tempScanner.start()
                    }
                } else {
                    android.widget.Toast.makeText(this@PokerHudService, "Enable Screen Projection first", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnBoard = createControllerEmojiButton("📋", PokerHudSharedState.showCommBox, "#FF2ECC71")
        val btnCards = createControllerEmojiButton("🎴", PokerHudSharedState.showHoleBox, "#FFF39C12")
        val btnProbs = createControllerEmojiButton("📊", PokerHudSharedState.showProbsBox, "#FF3498DB")
        val btnScan = createControllerEmojiButton("🔍", PokerHudSharedState.showScannerBoxes, "#FF9B59B6")

        emojiContainer.addView(btnProfile)
        emojiContainer.addView(btnBoard)
        emojiContainer.addView(btnCards)
        emojiContainer.addView(btnProbs)
        emojiContainer.addView(btnScan)
        
        expanded.addView(emojiContainer)

        val miniHandleView = TextView(this).apply {
            text = "◀️🕹️"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.parseColor("#FFD500F9"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        
        parentFrame.addView(miniHandleView)

        setupControllerDragListener(parentFrame, params)

        fun applyControllerHudLayoutState() {
            val isVertical = PokerHudSharedState.isControllerHudVertical.value
            val isMinimized = PokerHudSharedState.isControllerHudMinimized.value
            val gameMode = PokerHudSharedState.isGameMode.value

            // 1. Update touchability flags based on gameMode
            if (gameMode) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }

            // 2. Hide/Show scanner log box based on gameMode and user settings
            scannerStatusBox?.visibility = if (gameMode) View.GONE else (if (PokerHudSharedState.multiDataScannerToggle.value) View.VISIBLE else View.GONE)

            if (isMinimized) {
                params.width = dpToPx(55f)
                params.height = dpToPx(55f)
                params.x = -dpToPx(38f) // SNAP to left edge, half-hidden!
                
                parentFrame.background = createBackgroundDrawable(AndroidColor.parseColor("#CC111C24"), 8f, dpToPx(1.5f), AndroidColor.parseColor("#FFD500F9"))
                expanded.background = null
                
                mini.visibility = View.GONE
                expanded.visibility = View.GONE
                miniHandleView.visibility = View.VISIBLE
            } else if (gameMode) {
                // Game mode - completely invisible and non-interactive
                params.width = dpToPx(1f)
                params.height = dpToPx(1f)
                
                parentFrame.background = null
                expanded.background = null
                
                mini.visibility = View.GONE
                expanded.visibility = View.GONE
                miniHandleView.visibility = View.GONE
            } else if (isVertical) {
                params.width = dpToPx(55f)
                params.height = dpToPx(240f) // Tall vertical shape
                if (params.x < 0) {
                    params.x = dpToPx(10f)
                }
                
                // Match expanded layout dimensions to vertical parent size
                expanded.layoutParams = (expanded.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dpToPx(55f)
                    height = dpToPx(240f)
                }
                
                parentFrame.background = createBackgroundDrawable(AndroidColor.parseColor("#E6111C24"), 8f, dpToPx(1.5f), AndroidColor.parseColor("#FFD500F9"))
                expanded.background = null
                
                mini.visibility = View.GONE
                expanded.visibility = View.VISIBLE
                miniHandleView.visibility = View.GONE
                
                headerRow.visibility = View.GONE
                divider1.visibility = View.GONE
                toggles1.visibility = View.GONE
                
                vertToolbar.visibility = View.VISIBLE
                emojiContainer.visibility = View.VISIBLE
            } else {
                // Horizontal state
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (params.x < 0) {
                    params.x = dpToPx(2f)
                }
                
                expanded.layoutParams = (expanded.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dpToPx(200f)
                    height = FrameLayout.LayoutParams.WRAP_CONTENT
                }
                
                parentFrame.background = null // Let expanded cutout background drawable render
                expanded.background = CutoutBackgroundDrawable(
                    AndroidColor.parseColor("#F50D151D"), // Dark blue/grey high contrast
                    dpToPx(10f).toFloat(),
                    dpToPx(2f).toFloat(),
                    AndroidColor.parseColor("#FFD500F9"), // Neon Purple Outline
                    dpToPx(40f).toFloat() // Cutout radius for player avatar
                )
                
                mini.visibility = View.GONE
                expanded.visibility = View.VISIBLE
                miniHandleView.visibility = View.GONE
                
                headerRow.visibility = View.VISIBLE
                divider1.visibility = View.VISIBLE
                toggles1.visibility = View.VISIBLE
                
                vertToolbar.visibility = View.GONE
                emojiContainer.visibility = View.GONE
            }
            try {
                expanded.requestLayout()
                windowManager?.updateViewLayout(parentFrame, params)
            } catch (ignored: Exception) {}
        }

        serviceScope.launch {
            combine(
                PokerHudSharedState.isControllerHudVertical,
                PokerHudSharedState.isControllerHudMinimized,
                PokerHudSharedState.isGameMode
            ) { isVertical, isMinimized, gameMode ->
                Triple(isVertical, isMinimized, gameMode)
            }.collect {
                applyControllerHudLayoutState()
            }
        }

        // Add parent custom frame to window with try-catch safe blocks
        try {
            windowManager?.addView(parentFrame, params)
            isOverlayShowing = true
        } catch (e: Exception) {
            Log.e("PokerHudService", "Failed to add floating overlay view: ${e.message}", e)
            stopSelf()
        }

        // 5. OBSERVE LIVE RECALCULATED STATE COUPLING FROM MAIN APP VIEWMODEL
        serviceScope.launch {
            var lastProfileBoxes: List<ScannedBox>? = null
            PokerHudSharedState.uiState.collect { state ->
                // Emit calculations to LOGCAT under tag POKER_HUD_LOG and send local broadcast
                val h1Raw = state.heroCard1?.let { formatCardRaw(it) } ?: "?"
                val h2Raw = state.heroCard2?.let { formatCardRaw(it) } ?: "?"
                val bRaw = state.board.filterNotNull().joinToString(" ") { formatCardRaw(it) }
                val res = state.simulationResult
                
                if (state.profileBoxes != lastProfileBoxes) {
                    updateBoxOverlays()
                }
                
                val rec = state.recommendation
                val winPctRaw = if (res != null) String.format(Locale.US, "%.1f", res.heroWinPct + res.heroTiePct) else "0.0"
                val recActionRaw = rec?.action ?: "UNKNOWN"
                val recConfidenceRaw = rec?.confidence ?: 0f

                android.util.Log.i("POKER_HUD_LOG", "JSON_STATE={" +
                        "\"hero\":\"$h1Raw $h2Raw\"," +
                        "\"board\":\"$bRaw\"," +
                        "\"win_pct\":$winPctRaw," +
                        "\"recommendation\":\"$recActionRaw\"," +
                        "\"confidence\":$recConfidenceRaw" +
                        "}")

                val bIntent = Intent("com.example.HUD_CALCULATION_RESULT").apply {
                    putExtra("win_pct", winPctRaw.toFloatOrNull() ?: 0.0f)
                    putExtra("recommended_action", recActionRaw)
                    putExtra("confidence", recConfidenceRaw)
                    putExtra("hero_cards", "$h1Raw $h2Raw")
                    putExtra("board", bRaw)
                }
                sendBroadcast(bIntent)

                val oppsCount = state.opponents.count { it.isActive }
                val activeScannerStr = if (PokerHudSharedState.multiDataScannerToggle.value) "🟢" else "🔴"
                val boardStr = if (state.board.all { it == null }) "Pre-flop" else bRaw
                txtScannerStatus?.text = "🔍 $activeScannerStr Stage/Board: $boardStr\n" +
                        "Opponents: $oppsCount tracked\n" +
                        "Hero: $h1Raw $h2Raw"
                
                lastProfileBoxes = state.profileBoxes
            }
        }

        // Separate observers for UI toggles to update overlays ONLY when toggles change
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                PokerHudSharedState.isGameMode,
                PokerHudSharedState.showCommBox,
                PokerHudSharedState.showHoleBox,
                PokerHudSharedState.showProbsBox,
                PokerHudSharedState.showScannerBoxes
            ) { _, _, _, _, _ -> }.collect {
                updateBoxOverlays()
            }
        }

        // 5b. LISTEN TO COUPLING SETTINGS CHECKBOXES DYNAMIC FLOWS
        serviceScope.launch {
            PokerHudSharedState.multiDataScannerToggle.collect { checked ->
                scannerStatusBox?.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked && ScannerConfig.isProjectionGranted.value && ScannerConfig.pendingProjectionData != null && screenScanner == null) {
                    startForegroundServiceNotification()
                    screenScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode)
                    screenScanner?.start()
                } else if (!checked) {
                    screenScanner?.stop()
                    screenScanner = null
                }
            }
        }
        
        serviceScope.launch {
            ScannerConfig.isProjectionGranted.collect { granted ->
                if (granted && PokerHudSharedState.multiDataScannerToggle.value && ScannerConfig.pendingProjectionData != null && screenScanner == null) {
                    startForegroundServiceNotification()
                    screenScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode)
                    screenScanner?.start()
                }
            }
        }

        serviceScope.launch {
            PokerHudSharedState.hudScale.collect { scale ->
                expanded.pivotX = 0f
                expanded.pivotY = 0f
                expanded.scaleX = scale
                expanded.scaleY = scale

                mini.pivotX = 0f
                mini.pivotY = 0f
                mini.scaleX = scale
                mini.scaleY = scale
            }
        }

        serviceScope.launch {
            PokerHudSharedState.hudOpacity.collect { opacity ->
                expanded.alpha = opacity
                mini.alpha = opacity
            }
        }

        serviceScope.launch {
            PokerHudSharedState.isGameMode.collect { gameMode ->
                updateBoxOverlays()
            }
        }

        serviceScope.launch {
            PokerHudSharedState.isPasswordHidingActive.collect { isHiding ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isHiding) {
                        hideCommOverlay()
                        hideHoleOverlay()
                        hideProbsOverlay()
                        hideScannerOutlinesOverlay()
                        floatingOverlayView?.visibility = View.GONE
                    } else {
                        val gameMode = PokerHudSharedState.isGameMode.value
                        if (!gameMode) {
                            floatingOverlayView?.visibility = View.VISIBLE
                        }
                        updateBoxOverlays()
                    }
                }
            }
        }

        // 5c. LISTEN TO CALIBRATION BOX TOGGLES FOR OVERLAYS
        // (Removed redundant separate collectors since they are handled via combine operator above)

        // 6. OBSERVE PROGRAMMATIC CONTROL COMMANDS VIA BROADCASTS (e.g. from Termux scripts)
        var backgroundSimulationJob: kotlinx.coroutines.Job? = null
        var lastLaunchedHero1: Card? = null
        var lastLaunchedHero2: Card? = null
        var lastLaunchedBoard: List<Card?> = emptyList()
        var lastLaunchedOpponentsActive: List<Boolean> = emptyList()
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            PokerHudSharedState.externalActions.collect { action ->
                if (action is ExternalAction.UpdateCards) {
                    val currentState = PokerHudSharedState.uiState.value
                    val newBoard = action.board.take(5) + List(maxOf(0, 5 - action.board.size)) { null }
                    
                    // 1. Check for changes before doing intensive work
                    val isPreflop = newBoard.all { it == null }
                    val heroCardsString = "${action.hero1?.toString() ?: "Empty"}_${action.hero2?.toString() ?: "Empty"}"
                    
                    val opponentsChanged = if (action.opponents.size != currentState.opponents.size) true else {
                        action.opponents.zip(currentState.opponents).any { (a, b) ->
                            a.nickname != b.nickname || a.isActive != b.isActive || kotlin.math.abs(a.stackSize - b.stackSize) > 20
                        }
                    }

                    val rawBoxesChanged = PokerHudSharedState.showScannerBoxes.value && currentState.rawScannerBoxes != action.rawScannerBoxes

                    if (!opponentsChanged && 
                        !rawBoxesChanged &&
                        currentState.heroCard1 == action.hero1 && 
                        currentState.heroCard2 == action.hero2 && 
                        currentState.board == newBoard &&
                        !action.updateProfileBoxes &&
                        (action.potSize == null || action.potSize == currentState.potSize)) {
                        return@collect
                    }

                    val prefs = PreferencesManager(this@PokerHudService)

                    // Automated tracking of hands, VPIP and PFR
                    if (heroCardsString != "Empty_Empty" && heroCardsString != lastHandKey) {
                        lastHandKey = heroCardsString
                        countedHandPlayers.clear()
                        countedVpipPlayers.clear()
                        countedPfrPlayers.clear()
                        countedShowdownPlayers.clear()
                    }

                    if (action.opponents.isNotEmpty()) {
                        for (opponent in action.opponents) {
                            val name = opponent.nickname
                            if (name.isNotEmpty() && name != "Unknown") {
                                if (!countedHandPlayers.contains(name)) {
                                    countedHandPlayers.add(name)
                                    val stats = prefs.loadPlayerStats(name)
                                    prefs.savePlayerStats(stats.copy(handsPlayed = stats.handsPlayed + 1))
                                }
                                if (isPreflop) {
                                    val act = opponent.currentAction
                                    if (act == "RAISE" || act == "ALL_IN") {
                                        if (!countedPfrPlayers.contains(name)) {
                                            countedPfrPlayers.add(name)
                                            val stats = prefs.loadPlayerStats(name)
                                            prefs.savePlayerStats(stats.copy(pfrCount = stats.pfrCount + 1))
                                        }
                                    }
                                    if (act == "CALL" || act == "RAISE" || act == "ALL_IN") {
                                        if (!countedVpipPlayers.contains(name)) {
                                            countedVpipPlayers.add(name)
                                            val stats = prefs.loadPlayerStats(name)
                                            prefs.savePlayerStats(stats.copy(vpipCount = stats.vpipCount + 1))
                                        }
                                    }
                                }
                                
                                // Showdown stats sequence tracking
                                val numBoardCards = newBoard.filterNotNull().size
                                if (numBoardCards == 5 && (opponent.card1 != null || opponent.card2 != null)) {
                                    val showdownKey = "${heroCardsString}_showdown_${name}"
                                    if (!countedShowdownPlayers.contains(showdownKey)) {
                                        countedShowdownPlayers.add(showdownKey)
                                        val stats = prefs.loadPlayerStats(name)
                                        var updatedStats = stats.copy(showdownTotal = stats.showdownTotal + 1)
                                        
                                        // Evaluate hands and award win if opponent hand beats hero hand
                                        val oppCards = listOf(opponent.card1, opponent.card2) + newBoard
                                        val oppNotNull = oppCards.filterNotNull()
                                        if (oppNotNull.size >= 5) {
                                            val oppHand = HandEvaluator.findBest5CardHand(oppNotNull)
                                            val heroCards = listOf(action.hero1, action.hero2) + newBoard
                                            val heroNotNull = heroCards.filterNotNull()
                                            if (heroNotNull.size >= 5) {
                                                val heroHand = HandEvaluator.findBest5CardHand(heroNotNull)
                                                if (oppHand.compareTo(heroHand) > 0) {
                                                    updatedStats = updatedStats.copy(showdownWins = updatedStats.showdownWins + 1)
                                                }
                                            } else {
                                                if (oppHand.category.value >= HandCategory.TWO_PAIR.value) {
                                                    updatedStats = updatedStats.copy(showdownWins = updatedStats.showdownWins + 1)
                                                }
                                            }
                                        }
                                        prefs.savePlayerStats(updatedStats)
                                    }
                                }
                            }
                        }
                    }

                    // Dynamically map opponents with real-time up-to-date stats
                    val finalOpponentsList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val sourceList = if (action.opponents.isNotEmpty()) action.opponents else currentState.opponents
                        sourceList.map { opp ->
                            // Simple cache for stats during this single update pass? 
                            // Actually loadPlayerStats uses SharedPreferences which is mostly cached in memory by Android anyway,
                            // but we still want to avoid unnecessary copies.
                            val dbStats = prefs.loadPlayerStats(opp.nickname)
                            if (opp.stats == dbStats) opp else opp.copy(stats = dbStats)
                        }
                    }

                    val updatedState = currentState.copy(
                        heroCard1 = action.hero1,
                        heroCard2 = action.hero2,
                        board = newBoard,
                        opponents = finalOpponentsList,
                        profileBoxes = if (action.updateProfileBoxes) action.profileBoxes else currentState.profileBoxes,
                        rawScannerBoxes = action.rawScannerBoxes,
                        potSize = action.potSize ?: currentState.potSize,
                        heroActionOptions = action.heroActionOptions,
                        heroTurn = action.heroTurn,
                        heroStack = action.heroStack ?: currentState.heroStack,
                        heroBet = action.heroBet ?: currentState.heroBet,
                        position = action.tablePosition ?: currentState.position,
                        smallBlind = action.smallBlind ?: currentState.smallBlind,
                        bigBlind = action.bigBlind ?: currentState.bigBlind,
                        stage = action.tournamentStage ?: currentState.stage,
                        isBbDisplay = action.isBbDisplay
                    )
                    PokerHudSharedState.uiState.update { updatedState }
                    
                    if (action.updateProfileBoxes && action.profileBoxes != null) {
                        serviceScope.launch {
                            kotlinx.coroutines.delay(5000)
                            PokerHudSharedState.uiState.update { 
                                if (it.profileBoxes == action.profileBoxes) it.copy(profileBoxes = null) else it
                            }
                        }
                    }
                    
                    val simulationKeyChanged = lastLaunchedHero1 != updatedState.heroCard1 ||
                            lastLaunchedHero2 != updatedState.heroCard2 ||
                            lastLaunchedBoard != updatedState.board ||
                            lastLaunchedOpponentsActive != updatedState.opponents.map { it.isActive }

                    if (simulationKeyChanged || backgroundSimulationJob == null || backgroundSimulationJob?.isActive != true) {
                        backgroundSimulationJob?.cancel()
                        lastLaunchedHero1 = updatedState.heroCard1
                        lastLaunchedHero2 = updatedState.heroCard2
                        lastLaunchedBoard = updatedState.board
                        lastLaunchedOpponentsActive = updatedState.opponents.map { it.isActive }

                        backgroundSimulationJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                            try {
                                kotlinx.coroutines.delay(200)
                            // Reduced sim size for persistent HUD for battery/thermal/memory safety
                            val simSize = 1200 
                            
                            // 1. Original Branch
                            val result = com.example.SimulationEngine.runHoldemSimulation(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                opponents = updatedState.opponents,
                                board = updatedState.board,
                                simulations = simSize
                            )
                            val recommendation = com.example.AdvisorEngine.computeRecommendation(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                board = updatedState.board,
                                potSize = updatedState.potSize,
                                heroBet = updatedState.heroBet,
                                opponents = updatedState.opponents,
                                activeOpponentsCount = updatedState.opponents.count { it.isActive },
                                simResult = result,
                                settings = updatedState.settings,
                                position = updatedState.position,
                                stage = updatedState.stage,
                                smallBlind = updatedState.smallBlind,
                                bigBlind = updatedState.bigBlind,
                                heroStack = updatedState.heroStack
                            )

                            // 2. Advanced Branch
                            val advResult = com.example.SimulationEngine.runHoldemSimulationAdvanced(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                opponents = updatedState.opponents,
                                board = updatedState.board,
                                simulations = simSize
                            )
                            val advRecommendation = com.example.AdvisorEngine.computeRecommendationAdvanced(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                board = updatedState.board,
                                potSize = updatedState.potSize,
                                heroBet = updatedState.heroBet,
                                opponents = updatedState.opponents,
                                activeOpponentsCount = updatedState.opponents.count { it.isActive },
                                simResult = advResult,
                                settings = updatedState.settings,
                                position = updatedState.position,
                                stage = updatedState.stage,
                                smallBlind = updatedState.smallBlind,
                                bigBlind = updatedState.bigBlind,
                                heroStack = updatedState.heroStack
                            )

                            val l2Recommendation = com.example.AdvisorEngine.computeRecommendationL2(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                board = updatedState.board,
                                potSize = updatedState.potSize,
                                heroBet = updatedState.heroBet,
                                opponents = updatedState.opponents,
                                activeOpponentsCount = updatedState.opponents.count { it.isActive },
                                simResult = advResult,
                                settings = updatedState.settings,
                                position = updatedState.position,
                                stage = updatedState.stage,
                                smallBlind = updatedState.smallBlind,
                                bigBlind = updatedState.bigBlind,
                                heroStack = updatedState.heroStack
                            )

                            val l4Recommendation = com.example.AdvisorEngine.computeRecommendationL4(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                board = updatedState.board,
                                potSize = updatedState.potSize,
                                heroBet = updatedState.heroBet,
                                opponents = updatedState.opponents,
                                activeOpponentsCount = updatedState.opponents.count { it.isActive },
                                simResult = advResult,
                                settings = updatedState.settings,
                                position = updatedState.position,
                                stage = updatedState.stage,
                                smallBlind = updatedState.smallBlind,
                                bigBlind = updatedState.bigBlind,
                                heroStack = updatedState.heroStack
                            )

                            PokerHudSharedState.uiState.update { 
                                it.copy(
                                    simulationResult = result,
                                    recommendation = recommendation,
                                    advancedSimulationResult = advResult,
                                    l2Recommendation = l2Recommendation,
                                    advancedRecommendation = advRecommendation,
                                    l4Recommendation = l4Recommendation
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PokerHudService", "Simulation err", e)
                        }
                    }
                    }
                } else if (action is ExternalAction.ControlHud) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        when (action.command.lowercase(Locale.US)) {
                        "hide", "minimize" -> {
                            expandedLayout?.visibility = View.GONE
                            minimizedLayout?.visibility = View.VISIBLE
                        }
                        "show", "expand", "maximize" -> {
                            minimizedLayout?.visibility = View.GONE
                            expandedLayout?.visibility = View.VISIBLE
                        }
                        "exit", "stop", "close" -> {
                            stopFloatingOverlay()
                            stopSelf()
                        }
                        "toast" -> {
                            Toast.makeText(applicationContext, "Termux connection online!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

    private fun bringHudsToFront() {
        try {
            // Re-adding the views puts them at the top of the Z-order.
            floatingOverlayView?.let { v ->
                val p = v.layoutParams
                windowManager?.removeView(v)
                windowManager?.addView(v, p)
            }
            floatingProbsOverlay?.let { v ->
                val p = v.layoutParams
                windowManager?.removeView(v)
                windowManager?.addView(v, p)
            }
            floatingCommOverlay?.let { v ->
                val p = v.layoutParams
                windowManager?.removeView(v)
                windowManager?.addView(v, p)
            }
            floatingHoleOverlay?.let { v ->
                val p = v.layoutParams
                windowManager?.removeView(v)
                windowManager?.addView(v, p)
            }
        } catch (e: Exception) {
            android.util.Log.e("PokerHudService", "bringHudsToFront error", e)
        }
    }

    fun getHudRects(): List<android.graphics.Rect> {
        val rects = mutableListOf<android.graphics.Rect>()
        
        fun addRect(view: View?) {
            view?.let { v ->
                if (v.visibility == View.VISIBLE) {
                    val pos = IntArray(2)
                    v.getLocationOnScreen(pos)
                    rects.add(android.graphics.Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height))
                }
            }
        }

        addRect(floatingOverlayView)
        addRect(floatingProbsOverlay)
        addRect(floatingAdvisorOverlay)
        
        return rects
    }

    private fun updateBoxOverlays() {
        val gameMode = PokerHudSharedState.isGameMode.value
        val isHiding = PokerHudSharedState.isPasswordHidingActive.value
        if (isHiding) {
            hideCommOverlay()
            hideHoleOverlay()
            hideProbsOverlay()
            hideScannerOutlinesOverlay()
            return
        }
        
        if (PokerHudSharedState.showCommBox.value && !gameMode) showCommOverlay() else hideCommOverlay()
        if (PokerHudSharedState.showHoleBox.value && !gameMode) showHoleOverlay() else hideHoleOverlay()
        if (PokerHudSharedState.showProbsBox.value && !gameMode) showProbsOverlay() else hideProbsOverlay()
        
        if (!gameMode && PokerHudSharedState.showScannerBoxes.value) {
            showScannerOutlinesOverlay()
        } else {
            hideScannerOutlinesOverlay()
        }
        
        // Ensure our interactive HUDs stay on top of the scanner overlay Canvas
        bringHudsToFront()
    }

    fun getCommRect(): android.graphics.Rect {
        val view = floatingCommOverlay ?: return android.graphics.Rect(0, 0, 0, 0)
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        val w = if (view.width > 30) view.width else (view.layoutParams?.width?.takeIf { it > 30 } ?: dpToPx(200f))
        val h = if (view.height > 30) view.height else (view.layoutParams?.height?.takeIf { it > 30 } ?: dpToPx(120f))
        return android.graphics.Rect(pos[0], pos[1], pos[0] + w, pos[1] + h)
    }

    fun getHoleRect(): android.graphics.Rect {
        val view = floatingHoleOverlay ?: return android.graphics.Rect(0, 0, 0, 0)
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        val w = if (view.width > 30) view.width else (view.layoutParams?.width?.takeIf { it > 30 } ?: dpToPx(150f))
        val h = if (view.height > 30) view.height else (view.layoutParams?.height?.takeIf { it > 30 } ?: dpToPx(100f))
        return android.graphics.Rect(pos[0], pos[1], pos[0] + w, pos[1] + h)
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams, canMinimize: Boolean = false) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var clickStartTime = 0L

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    notifyInteraction()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    clickStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    val isMinimized = canMinimize && PokerHudSharedState.isProbsHudMinimized.value
                    if (isMinimized) {
                        params.x = -dpToPx(38f)
                        params.y = initialY + deltaY
                    } else {
                        val isEnd = (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END || (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                        val isBottom = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                        
                        params.x = if (isEnd) initialX - deltaX else initialX + deltaX
                        params.y = if (isBottom) initialY - deltaY else initialY + deltaY
                    }
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - clickStartTime
                    val distanceX = Math.abs(event.rawX - initialTouchX)
                    val distanceY = Math.abs(event.rawY - initialTouchY)
                    if (canMinimize && duration < 250 && distanceX < 12 && distanceY < 12) {
                        val isMinimized = PokerHudSharedState.isProbsHudMinimized.value
                        if (isMinimized) {
                            PokerHudSharedState.isProbsHudMinimized.value = false
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControllerDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var clickStartTime = 0L

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    notifyInteraction()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    clickStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    val isMinimized = PokerHudSharedState.isControllerHudMinimized.value
                    if (isMinimized) {
                        params.x = -dpToPx(38f)
                        params.y = initialY + deltaY
                    } else {
                        val isEnd = (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END || (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                        val isBottom = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                        
                        params.x = if (isEnd) initialX - deltaX else initialX + deltaX
                        params.y = if (isBottom) initialY - deltaY else initialY + deltaY
                    }
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - clickStartTime
                    val distanceX = Math.abs(event.rawX - initialTouchX)
                    val distanceY = Math.abs(event.rawY - initialTouchY)
                    if (duration < 250 && distanceX < 12 && distanceY < 12) {
                        val isMinimized = PokerHudSharedState.isControllerHudMinimized.value
                        if (isMinimized) {
                            PokerHudSharedState.isControllerHudMinimized.value = false
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupResizeListener(resizer: View, parentFrame: View, params: WindowManager.LayoutParams, minWidth: Int, minHeight: Int) {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        resizer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    params.width = Math.max(minWidth, initialWidth + deltaX)
                    params.height = Math.max(minHeight, initialHeight + deltaY)
                    try {
                        windowManager?.updateViewLayout(parentFrame, params)
                    } catch (ignored: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun animScanLaser(laserLine: View, heightPx: Int): ValueAnimator {
        return ValueAnimator.ofFloat(0f, (heightPx - dpToPx(3f)).toFloat()).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                laserLine.translationY = value
            }
        }
    }

    private fun showScannerOutlinesOverlay() {
        if (floatingScannerOverlay != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        val view = ScannerBoxesView(this)
        try {
            windowManager?.addView(view, params)
            floatingScannerOverlay = view
        } catch (e: Throwable) {
            Log.e("PokerHudService", "Failed to add scanner outlines overlay view: ${e.message}", e)
        }
        
        scannerOverlayJob?.cancel()
        scannerOverlayJob = serviceScope.launch {
            launch {
                PokerHudSharedState.uiState.collect { state ->
                    floatingScannerOverlay?.state = state
                }
            }
            launch {
                PokerHudSharedState.showScannerBoxes.collect { showBoxes ->
                    floatingScannerOverlay?.showGrid = showBoxes
                }
            }
            launch {
                PokerHudSharedState.scannerOffsetX.collect { dx ->
                    floatingScannerOverlay?.offsetX = dx
                }
            }
            launch {
                PokerHudSharedState.scannerOffsetY.collect { dy ->
                    floatingScannerOverlay?.offsetY = dy
                }
            }
        }
        floatingScannerOverlay?.isHidden = false
    }

    private fun hideScannerOutlinesOverlay() {
        scannerOverlayJob?.cancel()
        scannerOverlayJob = null
        floatingScannerOverlay?.let {
            try { windowManager?.removeView(it) } catch (ignored: Throwable) {}
        }
        floatingScannerOverlay = null
    }

    private fun showCommOverlay() {
        if (floatingCommOverlay != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            dpToPx(250f),
            dpToPx(75f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (screenWidth - dpToPx(250f)) / 2
            y = (screenHeight * 0.435f).toInt()
        }

        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#00000000"), // Transparent inside
                8f,
                dpToPx(1.5f),
                AndroidColor.parseColor("#FFD500F9") // Visible purple border
            )
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val spacerTitle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f))
            setOnClickListener {
                PokerHudSharedState.showCommBox.value = false
            }
        }

        header.addView(spacerTitle)
        header.addView(closeBtn)
        commHeader = header
        content.addView(header)

        val txtCardsInfo = TextView(this).apply {
            text = "⏳"
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dpToPx(24f), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            setShadowLayer(4f, 0f, 2f, AndroidColor.BLACK)
            visibility = View.GONE // Hide completely so it does not interfere with OCR/Scanning
        }
        commTxt = txtCardsInfo

        val laserLine = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#FF00FFCC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(3f)).apply {
                gravity = Gravity.TOP
            }
            visibility = View.GONE
        }

        val resizeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setColorFilter(AndroidColor.parseColor("#90CAF9"))
            alpha = 0.10f // Make it extremely faint so it won't obstruct cards OCR and suit detection
            layoutParams = FrameLayout.LayoutParams(dpToPx(24f), dpToPx(24f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }
        setupResizeListener(resizeBtn, frame, params, dpToPx(80f), dpToPx(50f))

        frame.addView(content)
        frame.addView(txtCardsInfo)
        frame.addView(resizeBtn)
        frame.addView(laserLine)

        setupDragListener(frame, params)

        try {
            windowManager?.addView(frame, params)
            floatingCommOverlay = frame
        } catch (e: Exception) {
            Log.e("PokerHudService", "Failed to add community overlay view: ${e.message}", e)
        }

        commLaserAnim?.cancel()
        val anim = animScanLaser(laserLine, dpToPx(115f))
        anim.start()
        commLaserAnim = anim

        commJob?.cancel()
        commJob = serviceScope.launch {
            launch {
                PokerHudSharedState.uiState.collect { state ->
                    try {
                        val cards = state.board.filterNotNull()
                        if (cards.isNotEmpty()) {
                            txtCardsInfo.text = "✅"
                        } else {
                            txtCardsInfo.text = "⏳"
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PokerHudService", "Error in commOverlay collect", e)
                    }
                }
            }
        }
    }

    private fun hideCommOverlay() {
        commLaserAnim?.cancel()
        commLaserAnim = null
        commJob?.cancel()
        commJob = null
        val view = floatingCommOverlay
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (ignored: Exception) {}
            floatingCommOverlay = null
        }
    }

    private fun showHoleOverlay() {
        if (floatingHoleOverlay != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            dpToPx(75f),
            dpToPx(55f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (screenWidth / 2) + dpToPx(20f)
            y = (screenHeight * 0.765f).toInt()
        }

        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#00000000"), // Transparent inside
                8f,
                dpToPx(1.5f),
                AndroidColor.parseColor("#FFE53935") // Visible border
            )
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE // Hide completely from the card scanning window area
        }

        val spacerTitle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f))
            setOnClickListener {
                PokerHudSharedState.showHoleBox.value = false
            }
        }

        header.addView(spacerTitle)
        header.addView(closeBtn)
        holeHeader = header
        content.addView(header)

        val txtCardsInfo = TextView(this).apply {
            text = "⏳"
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dpToPx(24f), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            setShadowLayer(4f, 0f, 2f, AndroidColor.BLACK)
            visibility = View.GONE // Hide completely so it does not interfere with OCR/Scanning
        }
        holeTxt = txtCardsInfo

        val laserLine = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#FF00FFCC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(3f)).apply {
                gravity = Gravity.TOP
            }
            visibility = View.GONE
        }

        val resizeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setColorFilter(AndroidColor.parseColor("#90CAF9"))
            alpha = 0.10f // Make it extremely faint so it won't obstruct cards OCR and suit detection
            layoutParams = FrameLayout.LayoutParams(dpToPx(24f), dpToPx(24f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }
        setupResizeListener(resizeBtn, frame, params, dpToPx(60f), dpToPx(50f))

        frame.addView(content)
        frame.addView(txtCardsInfo)
        frame.addView(resizeBtn)
        frame.addView(laserLine)

        setupDragListener(frame, params)

        try {
            windowManager?.addView(frame, params)
            floatingHoleOverlay = frame
        } catch (e: Exception) {
            Log.e("PokerHudService", "Failed to add hole overlay view: ${e.message}", e)
        }

        holeLaserAnim?.cancel()
        val anim = animScanLaser(laserLine, dpToPx(90f))
        anim.start()
        holeLaserAnim = anim

        holeJob?.cancel()
        holeJob = serviceScope.launch {
            launch {
                PokerHudSharedState.uiState.collect { state ->
                    try {
                        if (state.heroCard1 != null && state.heroCard2 != null) {
                            txtCardsInfo.text = "✅"
                        } else {
                            txtCardsInfo.text = "⏳"
                        }
                    } catch(e: Exception) {
                        android.util.Log.e("PokerHudService", "Error in holeOverlay collect", e)
                    }
                }
            }
        }
    }

    private fun hideHoleOverlay() {
        holeLaserAnim?.cancel()
        holeLaserAnim = null
        holeJob?.cancel()
        holeJob = null
        val view = floatingHoleOverlay
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (ignored: Exception) {}
            floatingHoleOverlay = null
        }
    }

    private fun showProbsOverlay() {
        if (floatingProbsOverlay != null) return
        val params = WindowManager.LayoutParams(
            dpToPx(160f),
            dpToPx(210f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dpToPx(300f)
            y = dpToPx(50f)
        }
        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#E6111C24"), 
                8f, 
                dpToPx(1.5f), 
                AndroidColor.parseColor("#FF4CAF50")
            )
            setPadding(dpToPx(1f), dpToPx(1f), dpToPx(1f), dpToPx(1f))
        }
        val content = FrameLayout(this)
        
        val mainVert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
        }
        
        val toggleBtnHorizontal = TextView(this).apply {
            text = "🔁"
            textSize = 10f
            setTextColor(AndroidColor.parseColor("#888888"))
            layoutParams = FrameLayout.LayoutParams(dpToPx(16f), dpToPx(16f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dpToPx(3f)
                rightMargin = dpToPx(3f)
            }
            
            var initialWidth = 0
            var initialHeight = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            setOnTouchListener { view, event ->
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX)
                        val deltaY = (event.rawY - initialTouchY)
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isClick = false
                            params.width = Math.max(dpToPx(130f), initialWidth + deltaX.toInt())
                            params.height = Math.max(dpToPx(150f), initialHeight + deltaY.toInt())
                            try {
                                windowManager?.updateViewLayout(frame, params)
                            } catch(e: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            val isVert = PokerHudSharedState.isProbsHudVertical.value
                            val isMin = PokerHudSharedState.isProbsHudMinimized.value
                            if (!isVert && !isMin) {
                                PokerHudSharedState.isProbsHudVertical.value = true
                                PokerHudSharedState.isProbsHudMinimized.value = false
                            } else if (isVert && !isMin) {
                                PokerHudSharedState.isProbsHudVertical.value = false
                                PokerHudSharedState.isProbsHudMinimized.value = true
                            } else {
                                PokerHudSharedState.isProbsHudVertical.value = false
                                PokerHudSharedState.isProbsHudMinimized.value = false
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createBackgroundDrawable(AndroidColor.TRANSPARENT, dpToPx(6f).toFloat(), dpToPx(1f), AndroidColor.parseColor("#44FFFFFF"))
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
                bottomMargin = dpToPx(4f)
            }
        }
        
        // Info Slot Content
        val txtCardsBoard = TextView(this).apply {
            text = "🎴 -- | 📋 --"
            setTextColor(AndroidColor.parseColor("#FFD54F"))
            textSize = 8f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(1.5f)
            }
        }

        val txtHandRankStrength = TextView(this).apply {
            text = "🎯 -- | 💪 --"
            setTextColor(AndroidColor.parseColor("#90CAF9"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(1.5f)
            }
        }
        
        val txtSklan = TextView(this).apply {
            text = "👥 [Нет карт]"
            setTextColor(AndroidColor.parseColor("#FFFF7043"))
            textSize = 8f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(1.5f)
            }
        }

        infoRow.addView(txtCardsBoard)
        infoRow.addView(txtHandRankStrength)
        infoRow.addView(txtSklan)
        
        mainVert.addView(infoRow)
        
        // Equalizer View Integration
        val equalizer = EqualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(24f)).apply {
                topMargin = dpToPx(2f)
                bottomMargin = dpToPx(2f)
                leftMargin = 0
                rightMargin = dpToPx(4f)
            }
        }
        mainVert.addView(equalizer)
        
        // Divider before Advisor Slot
        val advDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                topMargin = dpToPx(2f)
                bottomMargin = dpToPx(2f)
                leftMargin = 0
                rightMargin = dpToPx(4f)
            }
            setBackgroundColor(AndroidColor.parseColor("#22FFFFFF"))
        }
        mainVert.addView(advDivider)
        
        // Advisor Slot
        val txtL1Advisor = TextView(this).apply {
            text = "🧮 L1: ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FF90CAF9"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(1.5f)
            }
        }
        
        val txtL2Advisor = TextView(this).apply {
            text = "🖩 L2: ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FFCC80"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(1.5f)
            }
        }
        
        val txtL3Advisor = TextView(this).apply {
            text = "🦾 L3: ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FF80DEEA"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(1.5f)
            }
        }

        val txtL4Advisor = TextView(this).apply {
            text = "🖐️ L4: ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(1.5f)
            }
        }

        val txtL5Advisor = TextView(this).apply {
            text = "🤖 L5: Робот Off"
            setTextColor(AndroidColor.parseColor("#FFA7FFEB"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 0
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(4f)
            }
        }
        
        val advVert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        
        advVert.addView(txtL1Advisor)
        advVert.addView(txtL2Advisor)
        advVert.addView(txtL3Advisor)
        advVert.addView(txtL4Advisor)
        advVert.addView(txtL5Advisor)
        
        val scrollAdvisor = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scrollAdvisor.addView(advVert)
        
        mainVert.addView(scrollAdvisor)

        // Vertical specific layouts
        val vertToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(2f)
            }
            visibility = View.GONE
        }
        
        val btnSwitchToHoriz = TextView(this).apply {
            text = "🔁"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                PokerHudSharedState.isProbsHudVertical.value = false
            }
        }
        
        val btnMiniVert = TextView(this).apply {
            text = "➖"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                PokerHudSharedState.isProbsHudMinimized.value = true
            }
        }
        
        val btnCloseVert = TextView(this).apply {
            text = "❌"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                PokerHudSharedState.showProbsBox.value = false
            }
        }
        vertToolbar.addView(btnSwitchToHoriz)
        vertToolbar.addView(btnMiniVert)
        vertToolbar.addView(btnCloseVert)
        
        mainVert.addView(vertToolbar, 0)

        val emojiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(4f)
            }
            visibility = View.GONE
        }

        fun createEmojiButton(emoji: String, flow: StateFlow<Boolean>, strokeColorHex: String): TextView {
            val sizePx = dpToPx(26f)
            val btn = TextView(this).apply {
                text = emoji
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(0, dpToPx(3f), 0, dpToPx(3f))
                }
                setOnClickListener {
                    if (flow is MutableStateFlow<Boolean>) {
                        flow.value = !flow.value
                    }
                }
            }
            serviceScope.launch {
                flow.collect { active ->
                    btn.background = createDynamicGlow(active, strokeColorHex)
                    animateButtonPulse(btn, active)
                }
            }
            return btn
        }

        val btnBoard = createEmojiButton("📋", PokerHudSharedState.showCommBox, "#FF2ECC71")
        val btnCards = createEmojiButton("🎴", PokerHudSharedState.showHoleBox, "#FFF39C12")
        val btnAdv = createEmojiButton("📊", PokerHudSharedState.showActionAdvisor, "#FF3498DB")
        val btnScan = createEmojiButton("🔍", PokerHudSharedState.showScannerBoxes, "#FF9B59B6")
        val btnRobot = createEmojiButton("🤖", RobotPlayer.isRobotModeEnabled, "#FF00E676")

        emojiContainer.addView(btnBoard)
        emojiContainer.addView(btnCards)
        emojiContainer.addView(btnAdv)
        emojiContainer.addView(btnScan)
        emojiContainer.addView(btnRobot)

        mainVert.addView(emojiContainer)

        val miniHandleView = TextView(this).apply {
            text = "◀️📊"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        content.addView(mainVert)
        content.addView(toggleBtnHorizontal)
        
        frame.addView(content)
        frame.addView(miniHandleView)
        
        setupDragListener(frame, params, canMinimize = true)
        
        fun applyHudLayoutState() {
            val isVertical = PokerHudSharedState.isProbsHudVertical.value
            val isMinimized = PokerHudSharedState.isProbsHudMinimized.value
            val isRobotActive = RobotPlayer.isRobotModeEnabled.value
 
            if (isMinimized) {
                params.width = dpToPx(55f)
                params.height = dpToPx(55f)
                params.x = -dpToPx(38f) // SNAP to left edge, half-hidden!
                
                if (!isRobotActive) {
                    frame.background = createBackgroundDrawable(AndroidColor.parseColor("#CC111C24"), 8f, dpToPx(1.5f), AndroidColor.parseColor("#FF4CAF50"))
                }
                
                content.visibility = View.GONE
                mainVert.visibility = View.GONE
                miniHandleView.visibility = View.VISIBLE
            } else if (isVertical) {
                params.width = dpToPx(55f)
                params.height = dpToPx(270f)
                if (params.x < 0) {
                    params.x = dpToPx(10f)
                }
                
                if (!isRobotActive) {
                    frame.background = createBackgroundDrawable(AndroidColor.parseColor("#E6111C24"), 8f, dpToPx(1.5f), AndroidColor.parseColor("#FF4CAF50"))
                }
                
                content.visibility = View.VISIBLE
                mainVert.visibility = View.VISIBLE
                toggleBtnHorizontal.visibility = View.GONE
                miniHandleView.visibility = View.GONE
                
                infoRow.visibility = View.GONE
                advDivider.visibility = View.GONE
                scrollAdvisor.visibility = View.GONE
                
                vertToolbar.visibility = View.VISIBLE
                emojiContainer.visibility = View.VISIBLE
                
                equalizer.isVertical = true
                equalizer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(55f)).apply {
                    topMargin = dpToPx(4f)
                    bottomMargin = dpToPx(4f)
                    leftMargin = 0
                    rightMargin = 0
                }
            } else {
                val isAdvVisible = PokerHudSharedState.showActionAdvisor.value
                params.width = dpToPx(160f)
                params.height = if (isAdvVisible) dpToPx(210f) else dpToPx(110f)
                if (params.x < 0) {
                    params.x = dpToPx(100f)
                }
                
                if (!isRobotActive) {
                    frame.background = createBackgroundDrawable(
                        AndroidColor.parseColor("#E6111C24"), 
                        8f, 
                        dpToPx(1.5f), 
                        AndroidColor.parseColor("#FF4CAF50")
                    )
                }
                
                content.visibility = View.VISIBLE
                mainVert.visibility = View.VISIBLE
                toggleBtnHorizontal.visibility = View.VISIBLE
                miniHandleView.visibility = View.GONE
                
                vertToolbar.visibility = View.GONE
                emojiContainer.visibility = View.GONE
                
                infoRow.visibility = View.VISIBLE
                
                advDivider.visibility = if (isAdvVisible) View.VISIBLE else View.GONE
                scrollAdvisor.visibility = if (isAdvVisible) View.VISIBLE else View.GONE
                
                equalizer.isVertical = false
                equalizer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(24f)).apply {
                    topMargin = dpToPx(2f)
                    bottomMargin = dpToPx(2f)
                    leftMargin = 0
                    rightMargin = dpToPx(4f)
                }
            }
            
            try {
                windowManager?.updateViewLayout(frame, params)
            } catch (ignored: Exception) {}
        }

        serviceScope.launch {
            combine(
                PokerHudSharedState.isProbsHudVertical,
                PokerHudSharedState.isProbsHudMinimized,
                PokerHudSharedState.showActionAdvisor
            ) { isVertical, isMinimized, showAdv ->
                Triple(isVertical, isMinimized, showAdv)
            }.collect {
                applyHudLayoutState()
            }
        }

        serviceScope.launch {
            RobotPlayer.isRobotModeEnabled.collect { isRobotActive ->
                updateFrameBorderAnimation(isRobotActive, frame)
            }
        }

        try {
            windowManager?.addView(frame, params)
            floatingProbsOverlay = frame
        } catch (e: Exception) {}

        probsJob?.cancel()
        probsJob = serviceScope.launch {
            var lastHero1: Card? = null
            var lastHero2: Card? = null
            var lastBoard: List<Card?> = emptyList()
            var lastSimulationResult: SimulationResult? = null
            var lastAdvSimulationResult: SimulationResult? = null
            var lastOpponents: List<OpponentState> = emptyList()
            var lastRecommendation: Recommendation? = null
            var lastL2Recommendation: Recommendation? = null
            var lastAdvRecommendation: Recommendation? = null
            var lastL4Recommendation: Recommendation? = null

            var currentHandRank = "--"
            var currentStrength = "--"
            var currentCardsStr = "--"
            var currentBoardStr = "--"
            var currentWin = "0.0%"
            var currentAdvWin = "0.0%"

            PokerHudSharedState.uiState.collect { state ->
                try {
                    val isRobotActive = RobotPlayer.isRobotModeEnabled.value
                    // 1. Update Probabilities & Cards
                    val res = state.simulationResult
                    val advRes = state.advancedSimulationResult
                    val heroCardsChanged = state.heroCard1 != lastHero1 || state.heroCard2 != lastHero2
                    val boardChanged = state.board != lastBoard
                    val simResultChanged = res != lastSimulationResult || advRes != lastAdvSimulationResult
                    val opponentsChanged = state.opponents != lastOpponents
                    val rec = state.recommendation
                    val l2Rec = state.l2Recommendation
                    val advRec = state.advancedRecommendation
                    val l4Rec = state.l4Recommendation
                    val recommendationChanged = rec != lastRecommendation || l2Rec != lastL2Recommendation || advRec != lastAdvRecommendation || l4Rec != lastL4Recommendation

                    if (heroCardsChanged || boardChanged || simResultChanged) {
                        // Render Board independent of Hero cards
                        val boardStr = state.board.filterNotNull().joinToString(" ") { it.toHtmlString() }
                        currentBoardStr = if (boardStr.isEmpty()) "--" else boardStr
                                               
                        val allVisible = (listOf(state.heroCard1, state.heroCard2) + state.board).filterNotNull()
                        if (allVisible.size >= 5) {
                            val bestHand = HandEvaluator.findBest5CardHand(allVisible)
                            currentHandRank = bestHand.category.displayNameRu
                        } else if (allVisible.isNotEmpty()) {
                            val partialHand = HandEvaluator.findBestHand(allVisible)
                            currentHandRank = partialHand.category.displayNameRu
                        } else {
                            currentHandRank = "--"
                        }

                        if (state.heroCard1 == null || state.heroCard2 == null) {
                            currentWin = "0.0%"
                            currentAdvWin = "0.0%"
                            currentCardsStr = "--"
                        } else {
                            if (res != null) {
                                val combinedWin = res.heroWinPct + res.heroTiePct
                                currentWin = String.format(Locale.US, "%.1f%%", combinedWin)
                            } else {
                                currentWin = "Ждем..."
                            }

                            if (advRes != null) {
                                val combinedWin = advRes.heroWinPct + advRes.heroTiePct
                                currentAdvWin = String.format(Locale.US, "%.1f%%", combinedWin)
                            } else {
                                currentAdvWin = "Ждем..."
                            }
                            
                            currentCardsStr = "${state.heroCard1.toHtmlString()} ${state.heroCard2.toHtmlString()}"
                        }
                    }

                    if (heroCardsChanged || opponentsChanged) {
                        val mainOpp = state.opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
                        val vpip = mainOpp?.stats?.histVpip ?: mainOpp?.stats?.vpip ?: 100f
                        val sRange = AdvisorEngine.getSklanskyRangeForVpip(vpip)

                        if (state.heroCard1 != null && state.heroCard2 != null) {
                            val groupNum = AdvisorEngine.getSklanskyGroup(state.heroCard1, state.heroCard2)
                            txtSklan.text = "👥 $groupNum | 📊 1-$sRange"
                            
                            val strengthDesc = when (groupNum) {
                                1, 2 -> "Топ (1/20)"
                                3, 4 -> "Высокая (4/20)"
                                5, 6 -> "Средняя (8/20)"
                                else -> "Слабая (14/20)"
                            }
                            
                            val relativePos = if (groupNum <= sRange) "Впереди диапазона" else "Позади диапазона"
                            currentStrength = "$strengthDesc ($relativePos)"
                        } else {
                            txtSklan.text = "👥 [Нет карт]"
                            currentStrength = "--"
                        }
                    }

                    if (heroCardsChanged || boardChanged || simResultChanged || opponentsChanged || recommendationChanged) {
                        val l3Val = if (state.heroCard1 != null && state.heroCard2 != null && advRec != null) {
                            String.format(Locale.US, "%.0f%%", advRec.confidence)
                        } else if (state.heroCard1 != null && state.heroCard2 != null) {
                            "Ждем..."
                        } else {
                            "0.0%"
                        }
                        txtCardsBoard.text = android.text.Html.fromHtml("🎴 $currentCardsStr | 📋 $currentBoardStr", android.text.Html.FROM_HTML_MODE_LEGACY)
                        txtHandRankStrength.text = "🎯 $currentHandRank | 💪 $currentStrength"
                    }
                    
                    if (recommendationChanged) {
                        // 1. Update L1 recommendation
                        if (rec != null) {
                            val actName = translateAction(rec.action)
                            val exp = if (rec.explanation.isNotEmpty()) " | ${rec.explanation}" else ""
                            txtL1Advisor.text = "🧮 L1: $actName (${String.format(Locale.US, "%.0f%%", rec.confidence)})$exp"
                            setRecommendationColor(txtL1Advisor, rec.action)
                        } else {
                            txtL1Advisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "🧮 L1: Ждем..." else "🧮 L1: Ждем карты"
                            txtL1Advisor.setTextColor(AndroidColor.parseColor("#FFFFD54F"))
                        }

                        // 2. Update L2 recommendation
                        if (l2Rec != null) {
                            val actName = translateAction(l2Rec.action)
                            val exp = if (l2Rec.explanation.isNotEmpty()) " | ${l2Rec.explanation}" else ""
                            txtL2Advisor.text = "🖩 L2: $actName (${String.format(Locale.US, "%.0f%%", l2Rec.confidence)})$exp"
                            setRecommendationColor(txtL2Advisor, l2Rec.action)
                        } else {
                            txtL2Advisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "🖩 L2: Ждем..." else ""
                            txtL2Advisor.setTextColor(AndroidColor.parseColor("#FFCC80"))
                        }

                        // 3. Update L3 recommendation
                        if (advRec != null) {
                            val actName = translateAction(advRec.action)
                            val exp = if (advRec.explanation.isNotEmpty()) " | ${advRec.explanation}" else ""
                            txtL3Advisor.text = "🦾 L3: $actName (${String.format(Locale.US, "%.0f%%", advRec.confidence)})$exp"
                            setRecommendationColor(txtL3Advisor, advRec.action)
                        } else {
                            txtL3Advisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "🦾 L3: Ждем..." else ""
                            txtL3Advisor.setTextColor(AndroidColor.parseColor("#FF80DEEA"))
                        }

                        // 4. Update L4 recommendation
                        if (l4Rec != null) {
                            val actName = translateAction(l4Rec.action)
                            val exp = if (l4Rec.explanation.isNotEmpty()) " | ${l4Rec.explanation}" else ""
                            txtL4Advisor.text = "🖐️ L4: $actName (${String.format(Locale.US, "%.0f%%", l4Rec.confidence)})$exp"
                            setRecommendationColor(txtL4Advisor, l4Rec.action)
                        } else {
                            txtL4Advisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "🖐️ L4: Ждем..." else ""
                            txtL4Advisor.setTextColor(AndroidColor.parseColor("#FF00FFCC"))
                        }
                    }

                    // 5. Update L5 / Robot Autoclicker Status
                    if (isRobotActive) {
                        val buttons = RobotPlayer.availableActionButtons
                        if (buttons.isEmpty()) {
                            txtL5Advisor.text = "🤖 L5: Робот АКТИВЕН (Ожидание)"
                        } else {
                            val detectedActions = buttons.keys.joinToString(", ")
                            txtL5Advisor.text = "🤖 L5: Робот ХОД: $detectedActions"
                        }
                        txtL5Advisor.setTextColor(AndroidColor.parseColor("#FF00E676"))
                    } else {
                        txtL5Advisor.text = "🤖 L5: Робот ВЫКЛЮЧЕН"
                        txtL5Advisor.setTextColor(AndroidColor.parseColor("#FF90A4AE"))
                    }

                    // Unconditional reactive Equalizer block updating
                    val l1FillValue = if (state.heroCard1 != null && state.heroCard2 != null && res != null) {
                        (res.heroWinPct + res.heroTiePct) / 100f
                    } else if (state.heroCard1 != null && state.heroCard2 != null) {
                        0.35f
                    } else {
                        0f
                    }
                    val l1Col = when {
                        l1FillValue > 0.5f -> AndroidColor.parseColor("#4CAF50")
                        l1FillValue > 0.3f -> AndroidColor.parseColor("#FFC107")
                        l1FillValue > 0f -> AndroidColor.parseColor("#FF5252")
                        else -> AndroidColor.GRAY
                    }
                    
                    val l2FillValue = if (state.heroCard1 != null && state.heroCard2 != null && advRes != null) {
                        (advRes.heroWinPct + advRes.heroTiePct) / 100f
                    } else if (state.heroCard1 != null && state.heroCard2 != null) {
                        0.35f
                    } else {
                        0f
                    }
                    val l2Col = when {
                        l2FillValue > 0.5f -> AndroidColor.parseColor("#4CAF50")
                        l2FillValue > 0.3f -> AndroidColor.parseColor("#FFC107")
                        l2FillValue > 0f -> AndroidColor.parseColor("#FF5252")
                        else -> AndroidColor.GRAY
                    }
                    
                    val opponentsWithAngles = state.opponents.filter { it.nickname != "Unknown" && it.nickname != "Player" }
                    val l3Sg = if (opponentsWithAngles.isEmpty()) {
                        List(6) { AndroidColor.parseColor("#33FFFFFF") }
                    } else {
                        opponentsWithAngles.map { opp ->
                            val v = opp.stats?.histVpip ?: opp.stats?.vpip ?: 30f
                            when {
                                v > 45f -> AndroidColor.parseColor("#FF5252")
                                v < 15f -> AndroidColor.parseColor("#00E5FF")
                                else -> AndroidColor.parseColor("#4CAF50")
                            }
                        }
                    }
                    
                    val l4FillValue = if (isRobotActive) 1f else 0f
                    val l4Col = if (isRobotActive) AndroidColor.parseColor("#00E676") else AndroidColor.parseColor("#555555")
                    
                    equalizer.updateState(EqualizerState(
                        l1Fill = l1FillValue,
                        l1Color = l1Col,
                        l2Fill = l2FillValue,
                        l2Color = l2Col,
                        l3Segments = l3Sg,
                        l4Fill = l4FillValue,
                        l4Color = l4Col
                    ))

                    lastHero1 = state.heroCard1
                    lastHero2 = state.heroCard2
                    lastBoard = state.board
                    lastSimulationResult = res
                    lastAdvSimulationResult = advRes
                    lastOpponents = state.opponents
                    lastRecommendation = rec
                    lastL2Recommendation = l2Rec
                    lastAdvRecommendation = advRec
                    lastL4Recommendation = l4Rec
                } catch (e: Exception) {
                    android.util.Log.e("PokerHudService", "Error inside HUD uiState catch", e)
                }
            }
        }
        
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.winProbToggle.collect { isVisible ->
                // Merged into header
            }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.handStrengthToggle.collect { isVisible ->
                // Merged into txtHandRankStrength
            }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.sklanskyToggle.collect { isVisible ->
                txtSklan.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.showActionAdvisor.collect { isVisible ->
                advDivider.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtL1Advisor.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtL2Advisor.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtL3Advisor.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtL4Advisor.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtL5Advisor.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.winProbScale.collect { scale -> 
                // Merged into header
            }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.handStrengthScale.collect { scale -> 
                // Merged into txtHandRankStrength
            }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.sklanskyScale.collect { scale -> txtSklan.textSize = 8f * scale }
        }
        serviceScope.launch(probsJob!!) {
            PokerHudSharedState.actionAdvisorScale.collect { scale -> 
                txtL1Advisor.textSize = 8f * scale
                txtL2Advisor.textSize = 8f * scale
                txtL3Advisor.textSize = 8f * scale
                txtL4Advisor.textSize = 8f * scale
                txtL5Advisor.textSize = 8f * scale
            }
        }
        // serviceScope.launch(probsJob!!) {
        //     PokerHudSharedState.advStatsScale.collect { scale -> oppStatsTxt.textSize = 9f * scale }
        // }
    }

    private fun translateAction(action: String): String {
        return when (action.uppercase(Locale.US)) {
            "FOLD" -> "ФОЛД"
            "CHECK" -> "ЧЕК"
            "CALL" -> "КОЛЛ"
            "RAISE" -> "РЕЙЗ"
            "ALL-IN" -> "ОЛЛ-ИН"
            "BET" -> "БЕТ"
            else -> action
        }
    }

    private fun setRecommendationColor(textView: TextView, action: String) {
        when (action.uppercase(Locale.US)) {
            "FOLD" -> textView.setTextColor(AndroidColor.parseColor("#FF90A4AE"))
            "CHECK" -> textView.setTextColor(AndroidColor.parseColor("#FF81C784"))
            "CALL" -> textView.setTextColor(AndroidColor.parseColor("#FF4CAF50"))
            "RAISE", "ALL-IN", "BET" -> textView.setTextColor(AndroidColor.parseColor("#FFE57373"))
            else -> textView.setTextColor(AndroidColor.parseColor("#FF90CAF9"))
        }
    }

    private fun hideProbsOverlay() {
        probsJob?.cancel()
        probsJob = null
        floatingProbsOverlay?.let { try { windowManager?.removeView(it) } catch (ignored: Exception) {} }
        floatingProbsOverlay = null
    }

    private fun stopFloatingOverlay() {
        if (isOverlayShowing && floatingOverlayView != null) {
            try {
                windowManager?.removeView(floatingOverlayView)
            } catch (ignored: Exception) {}
            isOverlayShowing = false
            floatingOverlayView = null
        }
        hideCommOverlay()
        hideHoleOverlay()
        hideProbsOverlay()
        serviceJob.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        PokerHudSharedState.isHudOverlayRunning.value = false
        screenScanner?.stop()
        stopFloatingOverlay()
        floatingScannerOverlay?.let { 
            try { windowManager?.removeView(it) } catch(e: Exception){} 
        }
        floatingScannerOverlay = null
        try {
            stopService(Intent(this, BotLogWidgetService::class.java))
            BotLogSharedState.isBotLogWidgetRunning.value = false
        } catch (e: Exception) {}
        ScannerConfig.activeProjection?.stop()
        ScannerConfig.activeProjection = null
        ScannerConfig.pendingProjectionData = null
        ScannerConfig.isProjectionGranted.value = false
    }

    private var frameBorderAnimator: android.animation.ValueAnimator? = null

    private fun updateFrameBorderAnimation(isRobotActive: Boolean, frame: FrameLayout) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            frameBorderAnimator?.cancel()
            frameBorderAnimator = null

            if (isRobotActive) {
                val startColor = AndroidColor.parseColor("#FF4CAF50") 
                val endColor = AndroidColor.parseColor("#FF00E676") 
                
                frameBorderAnimator = android.animation.ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                    duration = 1800
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        val animatedWidth = dpToPx(1.5f + fraction * 2.0f)
                        val animatedColor = interpolateColor(startColor, endColor, fraction)
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                frame.background = createBackgroundDrawable(
                                    AndroidColor.parseColor("#E6111C24"),
                                    8f,
                                    animatedWidth,
                                    animatedColor
                                )
                            } catch (ignored: Exception) {}
                        }
                    }
                    start()
                }
            } else {
                try {
                    frame.background = createBackgroundDrawable(
                        AndroidColor.parseColor("#E6111C24"),
                        8f,
                        dpToPx(1.5f),
                        AndroidColor.parseColor("#FF4CAF50")
                    )
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startAlpha = android.graphics.Color.alpha(startColor)
        val startRed = android.graphics.Color.red(startColor)
        val startGreen = android.graphics.Color.green(startColor)
        val startBlue = android.graphics.Color.blue(startColor)

        val endAlpha = android.graphics.Color.alpha(endColor)
        val endRed = android.graphics.Color.red(endColor)
        val endGreen = android.graphics.Color.green(endColor)
        val endBlue = android.graphics.Color.blue(endColor)

        return android.graphics.Color.argb(
            (startAlpha + fraction * (endAlpha - startAlpha)).toInt(),
            (startRed + fraction * (endRed - startRed)).toInt(),
            (startGreen + fraction * (endGreen - startGreen)).toInt(),
            (startBlue + fraction * (endBlue - startBlue)).toInt()
        )
    }
}
