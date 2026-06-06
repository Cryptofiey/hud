package com.example

import android.animation.ValueAnimator
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
        val heroBet: Float? = null
    ) : ExternalAction()
    data class ControlHud(val command: String) : ExternalAction()
}

object PokerHudSharedState {
    val isHudOverlayRunning = MutableStateFlow(false)
    val uiState = MutableStateFlow<PokerUiState>(PokerUiState())
    val triggerPreset = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val externalActions = MutableSharedFlow<ExternalAction>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // Scanner tuning
    val showScannerBoxes = MutableStateFlow(false)
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
}

class PokerHudService : Service() {

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
            gravity = Gravity.TOP or Gravity.START
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
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            background = CutoutBackgroundDrawable(
                AndroidColor.parseColor("#F50D151D"), // Dark blue/grey high contrast
                dpToPx(10f).toFloat(),
                dpToPx(2f).toFloat(),
                AndroidColor.parseColor("#FFD500F9"), // Neon Purple Outline
                dpToPx(40f).toFloat() // Cutout radius for player avatar
            )
            val shadowParams = FrameLayout.LayoutParams(resources.displayMetrics.widthPixels / 2, WindowManager.LayoutParams.WRAP_CONTENT)
            layoutParams = shadowParams
        }
        expandedLayout = expanded

        // HEADER ROW (Title | Hide btn | Exit btn)
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
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val readProfileBtn = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "ПРОФИЛЬ"
            textSize = 8f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF1976D2"), 4f)
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(56f), dpToPx(24f)).apply {
                setMargins(0, 0, dpToPx(2f), 0)
            }
        }
        readProfileBtn.setOnClickListener {
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

        val btnMinimize = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "СКРЫТЬ"
            textSize = 8f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF37474F"), 4f)
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(2f))
            val btnParams = LinearLayout.LayoutParams(dpToPx(46f), dpToPx(24f)).apply {
                setMargins(0, 0, dpToPx(2f), 0)
            }
            layoutParams = btnParams
        }

        val btnExit = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "ВЫХОД"
            textSize = 8f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF1E88E5"), 4f)
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(42f), dpToPx(24f))
        }

        headerRow.addView(txtTitle)
        headerRow.addView(readProfileBtn)
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

        fun createToggleButton(icon: String, initialState: Boolean, onChange: (Boolean) -> Unit): TextView {
            return TextView(this).apply {
                text = icon
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(AndroidColor.WHITE)
                background = createBackgroundDrawable(if (initialState) AndroidColor.parseColor("#FFD500F9") else AndroidColor.parseColor("#FF37474F"), 6f)
                setPadding(0, dpToPx(4f), 0, dpToPx(4f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dpToPx(2f), 0, dpToPx(2f), 0)
                }
                var state = initialState
                setOnClickListener {
                    state = !state
                    background = createBackgroundDrawable(if (state) AndroidColor.parseColor("#FFD500F9") else AndroidColor.parseColor("#FF37474F"), 6f)
                    onChange(state)
                }
            }
        }

        val commCheckBox = createToggleButton("Борд", PokerHudSharedState.showCommBox.value) { PokerHudSharedState.showCommBox.value = it }
        val holeCheckBox = createToggleButton("Карты", PokerHudSharedState.showHoleBox.value) { PokerHudSharedState.showHoleBox.value = it }
        val probsCheckBox = createToggleButton("Статы", PokerHudSharedState.showProbsBox.value) { PokerHudSharedState.showProbsBox.value = it }
        val scannerCheckBox = createToggleButton("Рамки", PokerHudSharedState.showScannerBoxes.value) { PokerHudSharedState.showScannerBoxes.value = it }
        
        toggles1.addView(commCheckBox)
        toggles1.addView(holeCheckBox)
        toggles1.addView(probsCheckBox)
        toggles1.addView(scannerCheckBox)
        expanded.addView(toggles1)

        parentFrame.addView(expanded)

        // 3. SET WINDOW SEAMLESS TOUCH DRAGGING LISTENERS
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    notifyInteraction()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(parentFrame, params)
                    } catch (ignored: Exception) {}
                    true
                }
                else -> false
            }
        }

        // Dedicated drag & click-tap detector for the minimized widget badge
        var miniInitialX = 0
        var miniInitialY = 0
        var miniInitialTouchX = 0f
        var miniInitialTouchY = 0f
        var miniClickStartTime = 0L

        val miniDragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    notifyInteraction()
                    miniInitialX = params.x
                    miniInitialY = params.y
                    miniInitialTouchX = event.rawX
                    miniInitialTouchY = event.rawY
                    miniClickStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = miniInitialX + (event.rawX - miniInitialTouchX).toInt()
                    params.y = miniInitialY + (event.rawY - miniInitialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(parentFrame, params)
                    } catch (ignored: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - miniClickStartTime
                    val distanceX = java.lang.Math.abs(event.rawX - miniInitialTouchX)
                    val distanceY = java.lang.Math.abs(event.rawY - miniInitialTouchY)
                    if (duration < 280 && distanceX < 15f && distanceY < 15f) {
                        mini.visibility = View.GONE
                        expanded.visibility = View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        // Expanded is dragged via the top part or header
        headerRow.setOnTouchListener(dragListener)
        // Minimized is fully draggable and clickable
        mini.setOnTouchListener(miniDragListener)

        // 4. SET GONE TOGGLES
        btnMinimize.setOnClickListener {
            expanded.visibility = View.GONE
            mini.visibility = View.VISIBLE
        }

        btnExit.setOnClickListener {
            stopFloatingOverlay()
            stopSelf()
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
                val parent = floatingOverlayView
                if (parent != null) {
                    if (gameMode) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    try { windowManager?.updateViewLayout(parent, params) } catch (ignored: Exception) {}
                }

                val visibilityMode = if (gameMode) View.GONE else View.VISIBLE
                headerRow.visibility = visibilityMode
                divider1.visibility = visibilityMode
                togglesRow1?.visibility = visibilityMode
                txtPreText?.visibility = visibilityMode

                // Scanner status is completely hidden in game play to not draw any blocks on screen
                scannerStatusBox?.visibility = if (gameMode) View.GONE else (if (PokerHudSharedState.multiDataScannerToggle.value) View.VISIBLE else View.GONE)

                if (gameMode) {
                    expanded.background = createBackgroundDrawable(AndroidColor.TRANSPARENT, 0f)
                } else {
                    expanded.background = CutoutBackgroundDrawable(
                        AndroidColor.parseColor("#F50D151D"),
                        dpToPx(10f).toFloat(),
                        dpToPx(2f).toFloat(),
                        AndroidColor.parseColor("#FFD500F9"),
                        dpToPx(40f).toFloat()
                    )
                }
                updateBoxOverlays()
            }
        }

        // 5c. LISTEN TO CALIBRATION BOX TOGGLES FOR OVERLAYS
        // (Removed redundant separate collectors since they are handled via combine operator above)

        // 6. OBSERVE PROGRAMMATIC CONTROL COMMANDS VIA BROADCASTS (e.g. from Termux scripts)
        var backgroundSimulationJob: kotlinx.coroutines.Job? = null
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
                        heroBet = action.heroBet ?: currentState.heroBet
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
                    
                    backgroundSimulationJob?.cancel()
                    backgroundSimulationJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            kotlinx.coroutines.delay(100)
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

                            PokerHudSharedState.uiState.update { 
                                it.copy(
                                    simulationResult = result,
                                    recommendation = recommendation,
                                    advancedSimulationResult = advResult,
                                    advancedRecommendation = advRecommendation
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PokerHudService", "Simulation err", e)
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
        addRect(floatingCommOverlay)
        addRect(floatingHoleOverlay)
        
        return rects
    }

    private fun updateBoxOverlays() {
        val gameMode = PokerHudSharedState.isGameMode.value
        
        if (PokerHudSharedState.showCommBox.value && !gameMode) showCommOverlay() else hideCommOverlay()
        if (PokerHudSharedState.showHoleBox.value && !gameMode) showHoleOverlay() else hideHoleOverlay()
        if (PokerHudSharedState.showProbsBox.value && !gameMode) showProbsOverlay() else hideProbsOverlay()
        
        if (!gameMode) showScannerOutlinesOverlay() else hideScannerOutlinesOverlay()
        
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

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    notifyInteraction()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    val isEnd = (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END || (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                    val isBottom = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                    
                    params.x = if (isEnd) initialX - deltaX else initialX + deltaX
                    params.y = if (isBottom) initialY - deltaY else initialY + deltaY
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
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
            dpToPx(280f),
            dpToPx(115f),
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
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dpToPx(280f)) / 2
            y = (screenHeight * 0.42f).toInt()
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
            dpToPx(85f),
            dpToPx(85f),
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
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dpToPx(85f)) / 2
            y = (screenHeight * 0.75f).toInt()
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
            dpToPx(200f),
            dpToPx(130f),
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
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(300f)
            y = dpToPx(50f)
        }
        val frame = FrameLayout(this).apply {
            background = CutoutBackgroundDrawable(
                AndroidColor.parseColor("#E6111C24"), 
                dpToPx(10f).toFloat(), 
                dpToPx(1.5f).toFloat(), 
                AndroidColor.parseColor("#FF4CAF50"),
                dpToPx(42f).toFloat(),
                CutoutCorner.BOTTOM_LEFT
            )
            setPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
        }
        val content = FrameLayout(this)
        
        val mainVert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = FrameLayout.LayoutParams(dpToPx(16f), dpToPx(16f)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(2f)
                rightMargin = dpToPx(2f)
            }
            setColorFilter(AndroidColor.parseColor("#888888"))
            setOnClickListener { PokerHudSharedState.showProbsBox.value = false }
        }
        
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        
        val leftInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val rightInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dpToPx(20f) // Keep clear of close button
                leftMargin = dpToPx(4f)
            }
        }

        val title = TextView(this).apply {
            text = "LHD | Поб: 0.0% | L3: 0.0%"
            setTextColor(AndroidColor.parseColor("#FFFFD54F"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
        }
        
        // Info Slot Content
        val txtCardsBoard = TextView(this).apply {
            text = "Карты: -- | Борд: --"
            setTextColor(AndroidColor.parseColor("#FFD54F"))
            textSize = 8f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(2f)
            }
        }
        
        leftInfoCol.addView(title)
        leftInfoCol.addView(txtCardsBoard)

        val txtHandRankStrength = TextView(this).apply {
            text = "Комбо: -- | Сила: --"
            setTextColor(AndroidColor.parseColor("#90CAF9"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(1f) // Align with Карты text if needed
            }
        }
        val txtSklan = TextView(this).apply {
            text = "Группа: [Нет карт]"
            setTextColor(AndroidColor.parseColor("#FFFF7043"))
            textSize = 8f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(2f)
            }
        }
        
        rightInfoCol.addView(txtHandRankStrength)
        rightInfoCol.addView(txtSklan)
        
        infoRow.addView(leftInfoCol)
        infoRow.addView(rightInfoCol)
        
        mainVert.addView(infoRow)
        
        // Divider before Advisor Slot
        val advDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                topMargin = dpToPx(4f)
                bottomMargin = dpToPx(2f)
                leftMargin = dpToPx(42f) // Keep clear of cutout
                rightMargin = dpToPx(20f) // Keep clear of close button area
            }
            setBackgroundColor(AndroidColor.parseColor("#22FFFFFF"))
        }
        mainVert.addView(advDivider)
        
        // Advisor Slot
        val txtAdvisor = TextView(this).apply {
            text = "🧮 ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FF90CAF9"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dpToPx(42f) // Avoid cutout area
                rightMargin = dpToPx(2f)
                bottomMargin = dpToPx(2f)
            }
        }
        
        val txtAdvAdvisor = TextView(this).apply {
            text = "✍️ ЖДЕМ..."
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(1f)
                leftMargin = dpToPx(42f) // Avoid cutout area
                bottomMargin = dpToPx(4f)
            }
        }
        
        val advVert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        
        advVert.addView(txtAdvisor)
        advVert.addView(txtAdvAdvisor)
        
        val scrollAdvisor = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scrollAdvisor.addView(advVert)
        
        mainVert.addView(scrollAdvisor)
        
        content.addView(mainVert)
        content.addView(closeBtn)
        frame.addView(content)
        setupDragListener(frame, params)
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
            var lastAdvRecommendation: Recommendation? = null

            var currentHandRank = "--"
            var currentStrength = "--"
            var currentCardsStr = "--"
            var currentBoardStr = "--"
            var currentWin = "0.0%"
            var currentAdvWin = "0.0%"

            PokerHudSharedState.uiState.collect { state ->
                try {
                    // 1. Update Probabilities & Cards
                    val res = state.simulationResult
                    val advRes = state.advancedSimulationResult
                    val heroCardsChanged = state.heroCard1 != lastHero1 || state.heroCard2 != lastHero2
                    val boardChanged = state.board != lastBoard
                    val simResultChanged = res != lastSimulationResult || advRes != lastAdvSimulationResult
                    val opponentsChanged = state.opponents != lastOpponents
                    val rec = state.recommendation
                    val advRec = state.advancedRecommendation
                    val recommendationChanged = rec != lastRecommendation || advRec != lastAdvRecommendation

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
                        if (state.heroCard1 != null && state.heroCard2 != null) {
                            val groupNum = AdvisorEngine.getSklanskyGroup(state.heroCard1, state.heroCard2)
                            
                            // Find the target opponent for range comparison
                            val mainOpp = state.opponents.filter { it.isActive }.maxByOrNull { it.stats?.handsPlayed ?: 0 }
                            val vpip = mainOpp?.stats?.histVpip ?: mainOpp?.stats?.vpip ?: 100f
                            val sRange = AdvisorEngine.getSklanskyRangeForVpip(vpip)
                            
                            txtSklan.text = "Группа: $groupNum | Диапазон: 1-$sRange"
                            
                            val strengthDesc = when (groupNum) {
                                1, 2 -> "Топ (1/20)"
                                3, 4 -> "Высокая (4/20)"
                                5, 6 -> "Средняя (8/20)"
                                else -> "Слабая (14/20)"
                            }
                            
                            val relativePos = if (groupNum <= sRange) "Впереди диапазона" else "Позади диапазона"
                            currentStrength = "$strengthDesc ($relativePos)"
                        } else {
                            txtSklan.text = "Группа: [Нет карт]"
                            currentStrength = "--"
                        }
                    }

                    if (heroCardsChanged || boardChanged || simResultChanged || opponentsChanged) {
                        title.text = "LHD | Поб: $currentWin | L3: $currentAdvWin"
                        txtCardsBoard.text = android.text.Html.fromHtml("<b>Карты:</b> $currentCardsStr | <b>Борд:</b> $currentBoardStr", android.text.Html.FROM_HTML_MODE_LEGACY)
                        txtHandRankStrength.text = "Комбо: $currentHandRank | Сила: $currentStrength"
                    }
                    
                    if (recommendationChanged) {
                        // Update original recommendation
                        if (rec != null) {
                            val actName = translateAction(rec.action)
                            txtAdvisor.text = "🧮 $actName (${String.format(Locale.US, "%.0f%%", rec.confidence)})"
                            setRecommendationColor(txtAdvisor, rec.action)
                        } else {
                            txtAdvisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "🧮 Ждем..." else "🧮 Ждем карты"
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FFFFD54F"))
                        }

                        // Update advanced recommendation
                        if (advRec != null) {
                            val actName = translateAction(advRec.action)
                            txtAdvAdvisor.text = "✍️ $actName (${String.format(Locale.US, "%.0f%%", advRec.confidence)})"
                            setRecommendationColor(txtAdvAdvisor, advRec.action)
                        } else {
                            txtAdvAdvisor.text = if (state.heroCard1 != null && state.heroCard2 != null) "✍️ Ждем..." else ""
                            txtAdvAdvisor.setTextColor(AndroidColor.parseColor("#FF00FFCC"))
                        }
                    }

                    lastHero1 = state.heroCard1
                    lastHero2 = state.heroCard2
                    lastBoard = state.board
                    lastSimulationResult = res
                    lastAdvSimulationResult = advRes
                    lastOpponents = state.opponents
                    lastRecommendation = rec
                    lastAdvRecommendation = advRec
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
                txtAdvisor.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtAdvAdvisor.visibility = if (isVisible) View.VISIBLE else View.GONE
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
                txtAdvisor.textSize = 8f * scale
                txtAdvAdvisor.textSize = 8f * scale
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
        PokerHudSharedState.isHudOverlayRunning.value = false
        screenScanner?.stop()
        stopFloatingOverlay()
        floatingScannerOverlay?.let { 
            try { windowManager?.removeView(it) } catch(e: Exception){} 
        }
        floatingScannerOverlay = null
        ScannerConfig.activeProjection?.stop()
        ScannerConfig.activeProjection = null
        ScannerConfig.pendingProjectionData = null
        ScannerConfig.isProjectionGranted.value = false
    }
}
