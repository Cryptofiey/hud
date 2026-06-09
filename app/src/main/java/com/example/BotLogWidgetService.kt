package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class BotLogWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingOverlayView: FrameLayout? = null
    private var isOverlayShowing = false
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Current selected indices
    private val currentLIndex = MutableStateFlow(1) // 1, 2, 3
    private val currentMIndex = MutableStateFlow(1) // 1, 2, 3, 4, 5
    
    enum class ViewMode { SPLIT_LM, L4_ONLY, BOT_ONLY }
    private val currentViewMode = MutableStateFlow(ViewMode.SPLIT_LM)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopFloatingOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        showFloatingOverlay()
        
        // Add sample logs to show how the UI populates
        BotLogSharedState.appendLogL(1, "[L1] Math GTO calculated...")
        BotLogSharedState.appendLogL(2, "[L2] Adjusting for user VPIP...")
        BotLogSharedState.appendLogL(3, "[L3] Persona matched: TAG")
        
        BotLogSharedState.appendLogM(1, "[M1] Gathering L1+L2 diff...")
        BotLogSharedState.appendLogM(2, "[M2] Pot odds check...")
        BotLogSharedState.appendLogM(3, "[M3] Adjusting for stack depth...")
        
        BotLogSharedState.appendLogL4("[L4] Strategy Generated -> BET")
        BotLogSharedState.appendLogBot("[BOT][L5] Click scheduled at (x:100, y:200)")

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "bot_log_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bot Log Widget",
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
            .setContentTitle("Bot Log Widget Active")
            .setContentText("Displaying logic and mechanic logs.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
            
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = 0
                if (Build.VERSION.SDK_INT >= 34) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                if (type == 0) {
                    startForeground(718, notification)
                } else {
                    startForeground(718, notification, type)
                }
            } else {
                startForeground(718, notification)
            }
        } catch (e: Exception) {}
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

    private fun showFloatingOverlay() {
        if (isOverlayShowing) return

        val params = WindowManager.LayoutParams(
            dpToPx(240f),
            dpToPx(300f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(10f)
            y = dpToPx(10f)
        }

        val parentFrame = FrameLayout(this)
        floatingOverlayView = parentFrame

        // Main Layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createBackgroundDrawable(Color.parseColor("#E6121A24"), 8f, dpToPx(2f), Color.parseColor("#FFD500F9"))
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // --- SPLIT MODE LAYOUT ---
        val splitModeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // L Logs Slot (Top)
        val lScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dpToPx(4f)
            }
            background = createBackgroundDrawable(Color.parseColor("#15FFFFFF"), 6f, dpToPx(1f), Color.parseColor("#44FFFFFF"))
        }
        val lTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }
        lScroll.addView(lTextView)

        // M Logs Slot (Bottom)
        val mScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dpToPx(4f)
            }
            background = createBackgroundDrawable(Color.parseColor("#15FFFFFF"), 6f, dpToPx(1f), Color.parseColor("#44FFFFFF"))
        }
        val mTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }
        mScroll.addView(mTextView)

        splitModeLayout.addView(lScroll)
        splitModeLayout.addView(mScroll)


        // --- L4/BOT ONLY LAYOUT ---
        val singleModeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
        }
        val singleTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#FF00FFCC"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(4f))
        }
        val singleScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            background = createBackgroundDrawable(Color.parseColor("#15FFFFFF"), 6f, dpToPx(1f), Color.parseColor("#44FFFFFF"))
        }
        val singleTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }
        singleScroll.addView(singleTextView)
        singleModeLayout.addView(singleTitle)
        singleModeLayout.addView(singleScroll)

        rootLayout.addView(splitModeLayout)
        rootLayout.addView(singleModeLayout)

        // --- BUTTONS ROW ---
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36f)).apply {
                topMargin = dpToPx(8f)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createBtn(textFlow: MutableStateFlow<String>? = null, staticText: String = ""): TextView {
            val tv = TextView(this).apply {
                if (textFlow == null) text = staticText
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = createBackgroundDrawable(Color.parseColor("#6637474F"), 6f, dpToPx(1f), Color.parseColor("#FF37474F"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(2f), 0, dpToPx(2f), 0)
                }
            }
            if (textFlow != null) {
                serviceScope.launch {
                    textFlow.collect { tv.text = it }
                }
            }
            return tv
        }

        val lBtnText = MutableStateFlow("L1")
        val lBtn = createBtn(textFlow = lBtnText).apply {
            setOnClickListener {
                if (currentViewMode.value != ViewMode.SPLIT_LM) {
                    currentViewMode.value = ViewMode.SPLIT_LM
                } else {
                    currentLIndex.value = (currentLIndex.value % 3) + 1
                    lBtnText.value = "L${currentLIndex.value}"
                }
            }
        }

        val mBtnText = MutableStateFlow("M1")
        val mBtn = createBtn(textFlow = mBtnText).apply {
            setOnClickListener {
                if (currentViewMode.value != ViewMode.SPLIT_LM) {
                    currentViewMode.value = ViewMode.SPLIT_LM
                } else {
                    currentMIndex.value = (currentMIndex.value % 5) + 1
                    mBtnText.value = "M${currentMIndex.value}"
                }
            }
        }

        val l4Btn = createBtn(staticText = "L4").apply {
            setOnClickListener {
                if (currentViewMode.value == ViewMode.L4_ONLY) {
                    currentViewMode.value = ViewMode.SPLIT_LM
                } else {
                    currentViewMode.value = ViewMode.L4_ONLY
                }
            }
        }

        val botBtn = createBtn(staticText = "BOT").apply {
            setOnClickListener {
                if (currentViewMode.value == ViewMode.BOT_ONLY) {
                    currentViewMode.value = ViewMode.SPLIT_LM
                } else {
                    currentViewMode.value = ViewMode.BOT_ONLY
                }
            }
        }

        val resizeBtn = createBtn(staticText = "⤡").apply {
            var initialWidth = 0
            var initialHeight = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        
                        params.width = (initialWidth + dx).coerceAtLeast(dpToPx(160f))
                        params.height = (initialHeight + dy).coerceAtLeast(dpToPx(160f))
                        
                        try {
                            windowManager?.updateViewLayout(parentFrame, params)
                            BotLogSharedState.widgetRect.value = android.graphics.Rect(
                                params.x, params.y,
                                params.x + params.width, params.y + params.height
                            )
                        } catch (e: Exception) {}
                        true
                    }
                    else -> false
                }
            }
        }

        buttonsRow.addView(lBtn)
        buttonsRow.addView(mBtn)
        buttonsRow.addView(l4Btn)
        buttonsRow.addView(botBtn)
        buttonsRow.addView(resizeBtn)

        rootLayout.addView(buttonsRow)

        parentFrame.addView(rootLayout)

        setupDragListener(parentFrame, params)

        // Logic listeners
        serviceScope.launch {
            currentViewMode.collect { mode ->
                when (mode) {
                    ViewMode.SPLIT_LM -> {
                        splitModeLayout.visibility = View.VISIBLE
                        singleModeLayout.visibility = View.GONE
                        
                        // Reset button highlights
                        l4Btn.background = createBackgroundDrawable(Color.parseColor("#6637474F"), 6f, dpToPx(1f), Color.parseColor("#FF37474F"))
                        botBtn.background = createBackgroundDrawable(Color.parseColor("#6637474F"), 6f, dpToPx(1f), Color.parseColor("#FF37474F"))
                    }
                    ViewMode.L4_ONLY -> {
                        splitModeLayout.visibility = View.GONE
                        singleModeLayout.visibility = View.VISIBLE
                        singleTitle.text = "LEVEL 4 LOGS"
                        
                        l4Btn.background = createBackgroundDrawable(Color.parseColor("#FFD500F9"), 6f)
                        botBtn.background = createBackgroundDrawable(Color.parseColor("#6637474F"), 6f, dpToPx(1f), Color.parseColor("#FF37474F"))
                    }
                    ViewMode.BOT_ONLY -> {
                        splitModeLayout.visibility = View.GONE
                        singleModeLayout.visibility = View.VISIBLE
                        singleTitle.text = "BOT (L5) LOGS"
                        
                        botBtn.background = createBackgroundDrawable(Color.parseColor("#FFD500F9"), 6f)
                        l4Btn.background = createBackgroundDrawable(Color.parseColor("#6637474F"), 6f, dpToPx(1f), Color.parseColor("#FF37474F"))
                    }
                }
            }
        }
        
        // Data bindings
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(BotLogSharedState.logL1, BotLogSharedState.logL2, BotLogSharedState.logL3, currentLIndex) { l1, l2, l3, idx ->
                when(idx) {
                    1 -> l1 to "L1"
                    2 -> l2 to "L2"
                    else -> l3 to "L3"
                }
            }.collect { (text, title) ->
                lTextView.text = "[$title Logs]\n$text"
                lScroll.post { lScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.combine(BotLogSharedState.logM1, BotLogSharedState.logM2, BotLogSharedState.logM3) { m1, m2, m3 -> listOf(m1, m2, m3) },
                kotlinx.coroutines.flow.combine(BotLogSharedState.logM4, BotLogSharedState.logM5) { m4, m5 -> listOf(m4, m5) },
                currentMIndex
            ) { list1, list2, idx ->
                val texts = list1 + list2
                texts[idx - 1] to "M$idx"
            }.collect { (text, title) ->
                mTextView.text = "[$title Logs]\n$text"
                mScroll.post { mScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        
        serviceScope.launch {
            BotLogSharedState.logL4.collect { text ->
                if (currentViewMode.value == ViewMode.L4_ONLY) {
                    singleTextView.text = text
                    singleScroll.post { singleScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        
        serviceScope.launch {
            BotLogSharedState.logBot.collect { text ->
                if (currentViewMode.value == ViewMode.BOT_ONLY) {
                    singleTextView.text = text
                    singleScroll.post { singleScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        
        serviceScope.launch {
            currentViewMode.collect { mode ->
                 when(mode) {
                     ViewMode.L4_ONLY -> {
                         singleTextView.text = BotLogSharedState.logL4.value
                         singleScroll.post { singleScroll.fullScroll(View.FOCUS_DOWN) }
                     }
                     ViewMode.BOT_ONLY -> {
                         singleTextView.text = BotLogSharedState.logBot.value
                         singleScroll.post { singleScroll.fullScroll(View.FOCUS_DOWN) }
                     }
                     else -> {}
                 }
            }
        }

        try {
            windowManager?.addView(parentFrame, params)
            isOverlayShowing = true
            BotLogSharedState.widgetRect.value = android.graphics.Rect(
                params.x, params.y,
                params.x + params.width, params.y + params.height
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
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
                        BotLogSharedState.widgetRect.value = android.graphics.Rect(
                            params.x, params.y,
                            params.x + params.width, params.y + params.height
                        )
                    } catch (e: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun stopFloatingOverlay() {
        if (isOverlayShowing) {
            try {
                windowManager?.removeView(floatingOverlayView)
            } catch (e: Exception) {
            }
            isOverlayShowing = false
            BotLogSharedState.widgetRect.value = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        stopFloatingOverlay()
    }
}
