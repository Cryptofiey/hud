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
import kotlinx.coroutines.launch
import java.util.Locale

// Shared object to communicate with MainActivity and PokerViewModel safely
sealed class ExternalAction {
    data class UpdateCards(
        val hero1: Card?,
        val hero2: Card?,
        val board: List<Card?>,
        val opponents: List<OpponentState> = emptyList()
    ) : ExternalAction()
    data class ControlHud(val command: String) : ExternalAction()
}

object PokerHudSharedState {
    val uiState = MutableStateFlow<PokerUiState>(PokerUiState())
    val triggerPreset = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val externalActions = MutableSharedFlow<ExternalAction>(extraBufferCapacity = 1)

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
    private var advisorJob: Job? = null
    private var floatingOpponentsOverlay: FrameLayout? = null
    private var oppJob: Job? = null
    private var screenScanner: ScreenScanner? = null

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(30f)
            y = dpToPx(120f)
        }

        // Parent FrameLayout containing both expanded panel & minimized handle
        val parentFrame = FrameLayout(this)
        floatingOverlayView = parentFrame

        // 1. MINIMIZED BADGE LAYOUT (Pill)
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
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
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(4f), 0, 0, 0)
        }
        mini.addView(txtMiniIcon)
        mini.addView(txtMiniLabel)
        parentFrame.addView(mini)

        // 2. EXPANDED HUD LAYOUT
        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12f), dpToPx(12f), dpToPx(12f), dpToPx(12f))
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#F50D151D"), // Dark blue/grey high contrast
                10f,
                dpToPx(2f),
                AndroidColor.parseColor("#FF2196F3") // Neon Blue Outline
            )
            val shadowParams = FrameLayout.LayoutParams(dpToPx(240f), WindowManager.LayoutParams.WRAP_CONTENT)
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
            )
        }

        val txtTitle = TextView(this).apply {
            text = "HUD MONITOR"
            setTextColor(AndroidColor.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinimize = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "HIDE"
            textSize = 9f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FF37474F"), 4f)
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            val btnParams = LinearLayout.LayoutParams(dpToPx(48f), dpToPx(26f)).apply {
                setMargins(0, 0, dpToPx(4f), 0)
            }
            layoutParams = btnParams
        }

        val btnExit = Button(this, null, 0, android.R.style.Widget_Button).apply {
            text = "EXIT"
            textSize = 9f
            setTextColor(AndroidColor.WHITE)
            background = createBackgroundDrawable(AndroidColor.parseColor("#FFD82229"), 4f)
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(44f), dpToPx(26f))
        }

        headerRow.addView(txtTitle)
        headerRow.addView(btnMinimize)
        headerRow.addView(btnExit)
        expanded.addView(headerRow)

        // Divider
        val divider1 = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                setMargins(0, dpToPx(6f), 0, dpToPx(6f))
            }
        }
        expanded.addView(divider1)

        // MULTI-DATA SCANNER STATUS BOX
        val scannerBoxLocal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            background = createBackgroundDrawable(AndroidColor.parseColor("#1500FFCC"), 4f, dpToPx(1f), AndroidColor.parseColor("#3300FFCC"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dpToPx(6f))
            }
        }
        scannerStatusBox = scannerBoxLocal

        val scannerTxt = TextView(this).apply {
            text = "🔍 SCANNER (CoinPoker): ACTIVE\n- Autotracking: 6 seats\n- Fail-safe fallback enabled"
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        txtScannerStatus = scannerTxt
        scannerBoxLocal.addView(scannerTxt)
        expanded.addView(scannerBoxLocal)

        // PRESET STAGE INJECTORS
        val injectorTitleText = TextView(this).apply {
            text = "INJECT DECK PRESET"
            setTextColor(AndroidColor.LTGRAY)
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(4f), 0, dpToPx(4f))
        }
        txtPreText = injectorTitleText
        expanded.addView(injectorTitleText)

        val presetButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val stages = listOf("Pre-flop", "Flop", "Turn", "River")
        for (stg in stages) {
            val btn = Button(this, null, 0, android.R.style.Widget_Button).apply {
                text = stg.split("-")[0].uppercase(Locale.US)
                textSize = 8f
                setTextColor(AndroidColor.WHITE)
                background = createBackgroundDrawable(AndroidColor.parseColor("#FF1976D2"), 4f)
                setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
                val params = LinearLayout.LayoutParams(0, dpToPx(28f), 1f).apply {
                    setMargins(dpToPx(2f), 0, dpToPx(2f), 0)
                }
                layoutParams = params
            }
            btn.setOnClickListener {
                serviceScope.launch {
                    PokerHudSharedState.triggerPreset.emit(stg)
                }
            }
            presetButtonsRow.addView(btn)
        }
        expanded.addView(presetButtonsRow)

        parentFrame.addView(expanded)

        // 3. SET WINDOW SEAMLESS TOUCH DRAGGING LISTENERS
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
            PokerHudSharedState.uiState.collect { state ->
                // Emit calculations to LOGCAT under tag POKER_HUD_LOG and send local broadcast
                val h1Raw = state.heroCard1?.let { formatCardRaw(it) } ?: "?"
                val h2Raw = state.heroCard2?.let { formatCardRaw(it) } ?: "?"
                val bRaw = state.board.filterNotNull().joinToString(" ") { formatCardRaw(it) }
                val res = state.simulationResult
                val rec = state.recommendation
                val winPctRaw = if (res != null) String.format(Locale.US, "%.1f", (res.heroWinPct + res.heroTiePct) * 100) else "0.0"
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
            }
        }

        // 5b. LISTEN TO COUPLING SETTINGS CHECKBOXES DYNAMIC FLOWS
        serviceScope.launch {
            PokerHudSharedState.multiDataScannerToggle.collect { checked ->
                scannerStatusBox?.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked && ScannerConfig.isProjectionGranted.value && ScannerConfig.pendingProjectionData != null && screenScanner == null) {
                    screenScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode)
                    screenScanner?.start()
                    
                    launch {
                        screenScanner?.scanStatus?.collect { status ->
                            txtPreText?.text = "Scan Phase: $status"
                        }
                    }
                } else if (!checked) {
                    screenScanner?.stop()
                    screenScanner = null
                }
            }
        }
        
        serviceScope.launch {
            ScannerConfig.isProjectionGranted.collect { granted ->
                if (granted && PokerHudSharedState.multiDataScannerToggle.value && ScannerConfig.pendingProjectionData != null && screenScanner == null) {
                    screenScanner = ScreenScanner(this@PokerHudService, ScannerConfig.pendingProjectionData!!, ScannerConfig.pendingProjectionResultCode)
                    screenScanner?.start()
                    
                    launch {
                        screenScanner?.scanStatus?.collect { status ->
                            txtPreText?.text = "Scan Phase: $status"
                        }
                    }
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
                presetButtonsRow.visibility = visibilityMode
                txtPreText?.visibility = visibilityMode

                // Scanner status is completely hidden in game play to not draw any blocks on screen
                scannerStatusBox?.visibility = if (gameMode) View.GONE else (if (PokerHudSharedState.multiDataScannerToggle.value) View.VISIBLE else View.GONE)

                if (gameMode) {
                    expanded.background = createBackgroundDrawable(AndroidColor.TRANSPARENT, 0f)
                } else {
                    expanded.background = createBackgroundDrawable(
                        AndroidColor.parseColor("#F50D151D"),
                        10f,
                        dpToPx(2f),
                        AndroidColor.parseColor("#FF2196F3")
                    )
                }
                updateBoxOverlays()
            }
        }

        // 5c. LISTEN TO CALIBRATION BOX TOGGLES FOR OVERLAYS
        serviceScope.launch { PokerHudSharedState.showCommBox.collect { updateBoxOverlays() } }
        serviceScope.launch { PokerHudSharedState.showHoleBox.collect { updateBoxOverlays() } }
        serviceScope.launch { PokerHudSharedState.showProbsBox.collect { updateBoxOverlays() } }

        // 6. OBSERVE PROGRAMMATIC CONTROL COMMANDS VIA BROADCASTS (e.g. from Termux scripts)
        var backgroundSimulationJob: kotlinx.coroutines.Job? = null
        serviceScope.launch {
            PokerHudSharedState.externalActions.collect { action ->
                if (action is ExternalAction.UpdateCards) {
                    val currentState = PokerHudSharedState.uiState.value
                    val newBoard = action.board.take(5) + List(maxOf(0, 5 - action.board.size)) { null }
                    
                    val prefs = PreferencesManager(this@PokerHudService)

                    // Automated tracking of hands, VPIP and PFR
                    val heroCardsString = "${action.hero1?.toString() ?: "Empty"}_${action.hero2?.toString() ?: "Empty"}"
                    if (heroCardsString != "Empty_Empty" && heroCardsString != lastHandKey) {
                        lastHandKey = heroCardsString
                        countedHandPlayers.clear()
                        countedVpipPlayers.clear()
                        countedPfrPlayers.clear()
                    }

                    if (action.opponents.isNotEmpty()) {
                        val isPreflop = newBoard.all { it == null }
                        for (opponent in action.opponents) {
                            val name = opponent.nickname
                            if (name.isNotEmpty() && name != "Unknown") {
                                // 1. Increment Hands Played
                                if (!countedHandPlayers.contains(name)) {
                                    countedHandPlayers.add(name)
                                    val stats = prefs.loadPlayerStats(name)
                                    prefs.savePlayerStats(stats.copy(handsPlayed = stats.handsPlayed + 1))
                                }

                                // 2. Track Pre-flop Actions (VPIP & PFR)
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
                    val finalOpponentsList = if (action.opponents.isNotEmpty()) {
                        action.opponents.map { opp ->
                            val dbStats = prefs.loadPlayerStats(opp.nickname)
                            opp.copy(stats = dbStats)
                        }
                    } else {
                        currentState.opponents.map { opp ->
                            val dbStats = prefs.loadPlayerStats(opp.nickname)
                            opp.copy(stats = dbStats)
                        }
                    }

                    if (currentState.heroCard1 == action.hero1 && currentState.heroCard2 == action.hero2 && currentState.board == newBoard && currentState.opponents == finalOpponentsList) {
                        return@collect
                    }

                    val updatedState = currentState.copy(
                        heroCard1 = action.hero1,
                        heroCard2 = action.hero2,
                        board = newBoard,
                        opponents = finalOpponentsList
                    )
                    PokerHudSharedState.uiState.value = updatedState
                    
                    backgroundSimulationJob?.cancel()
                    backgroundSimulationJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            val result = com.example.SimulationEngine.runHoldemSimulation(
                                heroCard1 = updatedState.heroCard1,
                                heroCard2 = updatedState.heroCard2,
                                opponents = updatedState.opponents,
                                board = updatedState.board,
                                simulations = updatedState.simulationSize
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
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                PokerHudSharedState.uiState.value = PokerHudSharedState.uiState.value.copy(
                                    simulationResult = result,
                                    recommendation = recommendation
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PokerHudService", "Simulation err", e)
                        }
                    }
                } else if (action is ExternalAction.ControlHud) {
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

    private fun updateBoxOverlays() {
        val gameMode = PokerHudSharedState.isGameMode.value
        
        if (PokerHudSharedState.showCommBox.value && !gameMode) showCommOverlay() else hideCommOverlay()
        if (PokerHudSharedState.showHoleBox.value && !gameMode) showHoleOverlay() else hideHoleOverlay()
        if (PokerHudSharedState.showProbsBox.value && !gameMode) showProbsOverlay() else hideProbsOverlay()
    }

    fun getCommRect(): android.graphics.Rect {
        val view = floatingCommOverlay ?: return android.graphics.Rect(0, 0, 0, 0)
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return android.graphics.Rect(0, 0, 0, 0)
        return android.graphics.Rect(params.x, params.y, params.x + view.width, params.y + view.height)
    }

    fun getHoleRect(): android.graphics.Rect {
        val view = floatingHoleOverlay ?: return android.graphics.Rect(0, 0, 0, 0)
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return android.graphics.Rect(0, 0, 0, 0)
        return android.graphics.Rect(params.x, params.y, params.x + view.width, params.y + view.height)
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun animScanLaser(laserLine: View, heightPx: Int): ValueAnimator {
        return ValueAnimator.ofInt(0, heightPx - dpToPx(3f)).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                val lp = laserLine.layoutParams as FrameLayout.LayoutParams
                lp.topMargin = value
                laserLine.layoutParams = lp
            }
        }
    }

    private fun showCommOverlay() {
        if (floatingCommOverlay != null) return

        val params = WindowManager.LayoutParams(
            dpToPx(280f),
            dpToPx(115f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(15f)
            y = dpToPx(150f)
        }

        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#00000000"), // Transparent inside
                8f,
                dpToPx(1.5f),
                AndroidColor.parseColor("#FF2196F3") // Visible border
            )
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
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

        val title = TextView(this).apply {
            text = "COMMUNITY CARDS CROP BOX"
            setTextColor(AndroidColor.parseColor("#FF2196F3"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = LinearLayout.LayoutParams(dpToPx(16f), dpToPx(16f))
            setOnClickListener {
                PokerHudSharedState.showCommBox.value = false
            }
        }

        header.addView(title)
        header.addView(closeBtn)
        content.addView(header)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(4f))
        }
        content.addView(spacer)

        val laserLine = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#FF00FFCC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(3f)).apply {
                gravity = Gravity.TOP
            }
            visibility = View.GONE
        }

        frame.addView(content)
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
        commLaserAnim = anim

        commJob?.cancel()
        commJob = serviceScope.launch {
            launch {
                PokerHudSharedState.isScanning.collect { scanning ->
                    laserLine.visibility = if (scanning) View.VISIBLE else View.GONE
                    if (scanning) {
                        frame.background = createBackgroundDrawable(
                            AndroidColor.parseColor("#00000000"),
                            8f,
                            dpToPx(1.5f),
                            AndroidColor.parseColor("#FF00FFCC")
                        )
                        try { anim.start() } catch (ignored: Exception) {}
                    } else {
                        frame.background = createBackgroundDrawable(
                            AndroidColor.parseColor("#00000000"),
                            8f,
                            dpToPx(1.5f),
                            AndroidColor.parseColor("#FF2196F3")
                        )
                        try { anim.cancel() } catch (ignored: Exception) {}
                    }
                }
            }
            launch {
                PokerHudSharedState.uiState.collect { state ->
                    // Removed txtCardsInfo updates
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

        val params = WindowManager.LayoutParams(
            dpToPx(180f),
            dpToPx(90f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(15f)
            y = dpToPx(290f)
        }

        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(
                AndroidColor.parseColor("#00000000"), // Transparent inside
                8f,
                dpToPx(1.5f),
                AndroidColor.parseColor("#FFE53935") // Visible border
            )
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
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

        val title = TextView(this).apply {
            text = "HOLE CARDS CROP BOX"
            setTextColor(AndroidColor.parseColor("#FFE53935"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = LinearLayout.LayoutParams(dpToPx(16f), dpToPx(16f))
            setOnClickListener {
                PokerHudSharedState.showHoleBox.value = false
            }
        }

        header.addView(title)
        header.addView(closeBtn)
        content.addView(header)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(4f))
        }
        content.addView(spacer)

        val laserLine = View(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#FF00FFCC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(3f)).apply {
                gravity = Gravity.TOP
            }
            visibility = View.GONE
        }

        frame.addView(content)
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
        holeLaserAnim = anim

        holeJob?.cancel()
        holeJob = serviceScope.launch {
            launch {
                PokerHudSharedState.isScanning.collect { scanning ->
                    laserLine.visibility = if (scanning) View.VISIBLE else View.GONE
                    if (scanning) {
                        frame.background = createBackgroundDrawable(
                            AndroidColor.parseColor("#00000000"),
                            8f,
                            dpToPx(1.5f),
                            AndroidColor.parseColor("#FF00FFCC")
                        )
                        try { anim.start() } catch (ignored: Exception) {}
                    } else {
                        frame.background = createBackgroundDrawable(
                            AndroidColor.parseColor("#00000000"),
                            8f,
                            dpToPx(1.5f),
                            AndroidColor.parseColor("#FFE53935")
                        )
                        try { anim.cancel() } catch (ignored: Exception) {}
                    }
                }
            }
            launch {
                PokerHudSharedState.uiState.collect { state ->
                    // Removed txtCardsInfo updates
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
            dpToPx(240f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(300f)
            y = dpToPx(150f)
        }
        val frame = FrameLayout(this).apply {
            background = createBackgroundDrawable(AndroidColor.parseColor("#E6111C24"), 10f, dpToPx(1.5f), AndroidColor.parseColor("#FFD82229"))
            setPadding(dpToPx(10f), dpToPx(10f), dpToPx(10f), dpToPx(10f))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        // Header Row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "LIVE HUD DASHBOARD"
            setTextColor(AndroidColor.parseColor("#FFFFD54F"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            layoutParams = LinearLayout.LayoutParams(dpToPx(16f), dpToPx(16f))
            setOnClickListener { PokerHudSharedState.showProbsBox.value = false }
        }
        header.addView(title)
        header.addView(closeBtn)
        content.addView(header)
        
        // Underline block
        content.addView(View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                topMargin = dpToPx(4f)
                bottomMargin = dpToPx(6f)
            }
            setBackgroundColor(AndroidColor.parseColor("#33FFFFFF"))
        })
        
        // 1. PROBABILITIES SECTION
        val txtWin = TextView(this).apply {
            text = "Winning chance: 0.0%"
            setTextColor(AndroidColor.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val txtStrength = TextView(this).apply {
            text = "Strength: High"
            setTextColor(AndroidColor.parseColor("#FF00FFCC"))
            textSize = 9.5f
        }
        val txtSklan = TextView(this).apply {
            text = "Sklansky: Group 1"
            setTextColor(AndroidColor.parseColor("#FFFF7043"))
            textSize = 9.5f
        }
        content.addView(txtWin)
        content.addView(txtStrength)
        content.addView(txtSklan)
        
        // 2. ACTION ADVISOR SECTION
        val advDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                topMargin = dpToPx(6f)
                bottomMargin = dpToPx(6f)
            }
            setBackgroundColor(AndroidColor.parseColor("#22FFFFFF"))
        }
        val txtAdvisor = TextView(this).apply {
            text = "Advisor Strategy: FOLD"
            setTextColor(AndroidColor.parseColor("#FF90CAF9"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(advDivider)
        content.addView(txtAdvisor)
        
        // 3. LIVE OPPONENTS SECTION
        val oppDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
                topMargin = dpToPx(6f)
                bottomMargin = dpToPx(6f)
            }
            setBackgroundColor(AndroidColor.parseColor("#22FFFFFF"))
        }
        val txtOppHeader = TextView(this).apply {
            text = "LIVE OPPONENT PROFILE"
            setTextColor(AndroidColor.parseColor("#FF90CAF9"))
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val oppStatsTxt = TextView(this).apply {
            text = "Loading opponents..."
            setTextColor(AndroidColor.WHITE)
            textSize = 9f
        }
        content.addView(oppDivider)
        content.addView(txtOppHeader)
        content.addView(oppStatsTxt)
        
        frame.addView(content)
        setupDragListener(frame, params)
        try {
            windowManager?.addView(frame, params)
            floatingProbsOverlay = frame
        } catch (e: Exception) {}

        probsJob?.cancel()
        val job = Job()
        probsJob = job
        
        serviceScope.launch(job) {
            PokerHudSharedState.uiState.collect { state ->
                // 1. Update Probabilities
                val res = state.simulationResult
                if (state.heroCard1 == null || state.heroCard2 == null) {
                    txtWin.text = "Winning chance: 0.0%"
                } else if (res != null) {
                    val combinedWin = res.heroWinPct + res.heroTiePct
                    txtWin.text = String.format(Locale.US, "Winning chance: %.1f%%", combinedWin)
                } else {
                    txtWin.text = "Winning chance: Calculating..."
                }

                if (state.heroCard1 != null && state.heroCard2 != null) {
                    val groupNum = AdvisorEngine.getSklanskyGroup(state.heroCard1, state.heroCard2)
                    txtSklan.text = "Sklansky: Group $groupNum"
                    val strengthDesc = when (groupNum) {
                        1, 2 -> "Premium (Top 1/20)"
                        3, 4 -> "High (Top 4/20)"
                        5, 6 -> "Medium (Top 8/20)"
                        else -> "Low (Top 14/20)"
                    }
                    txtStrength.text = "Strength: $strengthDesc"
                } else {
                    txtSklan.text = "Sklansky: [No starting cards]"
                    txtStrength.text = "Strength: --"
                }

                // 2. Update Action Advisor Recommendation
                val rec = state.recommendation
                if (rec != null) {
                    val actName = rec.action.uppercase(Locale.US)
                    txtAdvisor.text = "Advisor Strategy: $actName"
                    when (actName) {
                        "FOLD" -> {
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FF90A4AE"))
                        }
                        "CHECK" -> {
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FF81C784"))
                        }
                        "CALL" -> {
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FF4CAF50"))
                        }
                        "RAISE", "ALL-IN", "BET" -> {
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FFE57373"))
                        }
                        else -> {
                            txtAdvisor.setTextColor(AndroidColor.parseColor("#FF90CAF9"))
                        }
                    }
                } else {
                    if (state.heroCard1 != null && state.heroCard2 != null) {
                        txtAdvisor.text = "Advisor Strategy: Calculating..."
                        txtAdvisor.setTextColor(AndroidColor.parseColor("#FFFFD54F"))
                    } else {
                        txtAdvisor.text = "Advisor Strategy: Enter starter cards"
                        txtAdvisor.setTextColor(AndroidColor.parseColor("#FF90CAF9"))
                    }
                }

                // 3. Update Opponents Section
                val sb = StringBuilder()
                val activeOpps = state.opponents.filter { it.isActive }
                if (activeOpps.isEmpty()) {
                    sb.append("No active opponents tracked.")
                } else {
                    val minHands = PokerHudSharedState.advMinHands.value
                    activeOpps.forEachIndexed { index, opp ->
                        val prefix = if (index > 0) "\n" else ""
                        val hands = opp.stats?.handsPlayed ?: 0
                        
                        val vpipVal = opp.stats?.vpip?.toInt() ?: 0
                        val pfrVal = opp.stats?.pfr?.toInt() ?: 0
                        val actStr = if (opp.currentAction != "NONE") " (${opp.currentAction})" else ""
                        val nameToShow = if (opp.nickname.length > 10) opp.nickname.take(9) + ".." else opp.nickname
                        
                        if (hands >= minHands) {
                            sb.append("${prefix}${nameToShow}${actStr}: H:${hands} VPIP:${vpipVal}% PFR:${pfrVal}%")
                        } else {
                            sb.append("${prefix}${nameToShow}${actStr}: Need ${minHands - hands} hands")
                        }
                    }
                }
                oppStatsTxt.text = sb.toString()
            }
        }
        
        serviceScope.launch(job) {
            PokerHudSharedState.winProbToggle.collect { isVisible ->
                txtWin.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.handStrengthToggle.collect { isVisible ->
                txtStrength.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.sklanskyToggle.collect { isVisible ->
                txtSklan.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.showActionAdvisor.collect { isVisible ->
                advDivider.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtAdvisor.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.advStatsToggle.collect { isVisible ->
                oppDivider.visibility = if (isVisible) View.VISIBLE else View.GONE
                txtOppHeader.visibility = if (isVisible) View.VISIBLE else View.GONE
                oppStatsTxt.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
        
        serviceScope.launch(job) {
            PokerHudSharedState.winProbScale.collect { scale -> txtWin.textSize = 10.5f * scale }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.handStrengthScale.collect { scale -> txtStrength.textSize = 9.5f * scale }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.sklanskyScale.collect { scale -> txtSklan.textSize = 9.5f * scale }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.actionAdvisorScale.collect { scale -> txtAdvisor.textSize = 10f * scale }
        }
        serviceScope.launch(job) {
            PokerHudSharedState.advStatsScale.collect { scale -> oppStatsTxt.textSize = 9f * scale }
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
        stopFloatingOverlay()
        ScannerConfig.activeProjection?.stop()
        ScannerConfig.activeProjection = null
        ScannerConfig.pendingProjectionData = null
        ScannerConfig.isProjectionGranted.value = false
    }
}
