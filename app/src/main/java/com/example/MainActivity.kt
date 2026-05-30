package com.example

import android.app.Activity
import android.util.Log
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity.RESULT_OK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScannerConfig {
    var pendingProjectionData: Intent? = null
    var pendingProjectionResultCode: Int = 0
    val isProjectionGranted = MutableStateFlow(false)
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScannerConfig.pendingProjectionData = result.data
            ScannerConfig.pendingProjectionResultCode = result.resultCode
            ScannerConfig.isProjectionGranted.value = true
        }
    }

    fun requestScreenCapture() {
        if (ScannerConfig.isProjectionGranted.value) return
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF131313)
                ) { innerPadding ->
                    MainOverlayApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // Requests Overlay Draw permission if needed on real Android devices
    fun checkAndRequestOverlayPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    startActivity(intent)
                    return false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start Overlay settings activity: ${e.message}", e)
                return true // Fallback to let the app try running anyway
            }
        }
        return true
    }
}

enum class AppMode {
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainOverlayApp(
    modifier: Modifier = Modifier,
    pokerViewModel: PokerViewModel = viewModel()
) {
    val uiState by pokerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Switch between settings and simulation screen
    var appMode by remember { mutableStateOf(AppMode.SETTINGS) }
    
    // Tab controller inside settings
    var settingsTabIdx by remember { mutableStateOf(0) }
    
    // Interactive card choosing dialog
    var targetSlotToPick by remember { mutableStateOf<SelectionTarget?>(null) }
    
    // Scale slider controller of the HUD probabilities widget
    var hudWidgetScale by remember { mutableFloatStateOf(1.0f) }
    var hudOpacity by remember { mutableFloatStateOf(0.9f) }

    // Toggle specific functions displayed on UI
    var winProbToggle by remember { mutableStateOf(true) }
    var handStrengthToggle by remember { mutableStateOf(true) }
    var sklanskyToggle by remember { mutableStateOf(true) }
    var advStatsToggle by remember { mutableStateOf(true) }
    var showActionAdvisor by remember { mutableStateOf(true) }
    var multiDataScannerToggle by remember { mutableStateOf(true) }

    // Quick explanations modal dialog
    var moreInfoTopic by remember { mutableStateOf<String?>(null) }

    // Active status of the accessibility automation service
    var isAccessibilityActive by remember { mutableStateOf(PokerAutomationService.isRunning()) }
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityActive = PokerAutomationService.isRunning()
            delay(500)
        }
    }

    // Direct real-time state bridge for the background HUD overlay service
    LaunchedEffect(pokerViewModel) {
        pokerViewModel.uiState.collect { state ->
            PokerHudSharedState.uiState.value = state
        }
    }

    LaunchedEffect(winProbToggle, handStrengthToggle, sklanskyToggle, advStatsToggle, showActionAdvisor, multiDataScannerToggle, hudWidgetScale, hudOpacity) {
        PokerHudSharedState.winProbToggle.value = winProbToggle
        PokerHudSharedState.handStrengthToggle.value = handStrengthToggle
        PokerHudSharedState.sklanskyToggle.value = sklanskyToggle
        PokerHudSharedState.advStatsToggle.value = advStatsToggle
        PokerHudSharedState.showActionAdvisor.value = showActionAdvisor
        PokerHudSharedState.multiDataScannerToggle.value = multiDataScannerToggle
        PokerHudSharedState.hudScale.value = hudWidgetScale
        PokerHudSharedState.hudOpacity.value = hudOpacity
    }

    LaunchedEffect(pokerViewModel) {
        PokerHudSharedState.triggerPreset.collect { phase ->
            val h1: Card?
            val h2: Card?
            val boardList: List<Card?>
            
            when (phase) {
                "Pre-flop" -> {
                    h1 = Card(Rank.ACE, Suit.HEARTS)
                    h2 = Card(Rank.ACE, Suit.DIAMONDS)
                    boardList = listOf(null, null, null, null, null)
                }
                "Flop" -> {
                    h1 = Card(Rank.ACE, Suit.HEARTS)
                    h2 = Card(Rank.ACE, Suit.DIAMONDS)
                    boardList = listOf(
                        Card(Rank.KING, Suit.SPADES),
                        Card(Rank.QUEEN, Suit.CLUBS),
                        Card(Rank.JACK, Suit.DIAMONDS),
                        null,
                        null
                    )
                }
                "Turn" -> {
                    h1 = Card(Rank.ACE, Suit.HEARTS)
                    h2 = Card(Rank.ACE, Suit.DIAMONDS)
                    boardList = listOf(
                        Card(Rank.KING, Suit.SPADES),
                        Card(Rank.QUEEN, Suit.CLUBS),
                        Card(Rank.JACK, Suit.DIAMONDS),
                        Card(Rank.TEN, Suit.HEARTS),
                        null
                    )
                }
                "River" -> {
                    h1 = Card(Rank.ACE, Suit.HEARTS)
                    h2 = Card(Rank.ACE, Suit.DIAMONDS)
                    boardList = listOf(
                        Card(Rank.KING, Suit.SPADES),
                        Card(Rank.QUEEN, Suit.CLUBS),
                        Card(Rank.JACK, Suit.DIAMONDS),
                        Card(Rank.TEN, Suit.HEARTS),
                        Card(Rank.NINE, Suit.SPADES)
                    )
                }
                else -> return@collect
            }
            pokerViewModel.loadGamePreset(h1, h2, boardList)
        }
    }

    LaunchedEffect(pokerViewModel) {
        PokerHudSharedState.externalActions.collect { action ->
            if (action is ExternalAction.UpdateCards) {
                pokerViewModel.loadGamePreset(action.hero1, action.hero2, action.board)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        when (appMode) {
            AppMode.SETTINGS -> {
                // SCREENSHOT 2 DIRECT REPLICA
                SettingsLayout(
                    tabIndex = settingsTabIdx,
                    onTabChecked = { settingsTabIdx = it },
                    winProbToggle = winProbToggle,
                    onWinProbChange = { winProbToggle = it },
                    handStrengthToggle = handStrengthToggle,
                    onHandStrengthChange = { handStrengthToggle = it },
                    sklanskyToggle = sklanskyToggle,
                    onSklanskyChange = { sklanskyToggle = it },
                    advStatsToggle = advStatsToggle,
                    onAdvStatsChange = { advStatsToggle = it },
                    showActionAdvisor = showActionAdvisor,
                    onShowAdvisorChange = { showActionAdvisor = it },
                    multiDataScannerToggle = multiDataScannerToggle,
                    onMultiDataScannerChange = { 
                        multiDataScannerToggle = it
                        if (it) {
                            val activity = context.findActivity() as? MainActivity
                            activity?.requestScreenCapture()
                        }
                    },
                    onMoreInfoClicked = { moreInfoTopic = it },
                    uiState = uiState,
                    pokerViewModel = pokerViewModel,
                    isAccessibilityActive = isAccessibilityActive,
                    onPlayClicked = {
                        // Launch actual foreground background overlay service for actual installations
                        val activity = context.findActivity() as? MainActivity
                        val ok = activity?.checkAndRequestOverlayPermission(context) ?: true
                        if (ok) {
                            if (multiDataScannerToggle && !ScannerConfig.isProjectionGranted.value) {
                                activity?.requestScreenCapture()
                            }
                            try {
                                val serviceIntent = Intent(context, PokerHudService::class.java)
                                ContextCompat.startForegroundService(context, serviceIntent)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to start PokerHudService: ${e.message}", e)
                                Toast.makeText(context, "Error starting HUD service: \${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Please grant Overlay Permissions to display floating HUD above other apps!", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }

        // 3. INTERACTIVE GENERAL PURPOSE CARD SELECTOR DIALOG FOR CALIBRATION
        targetSlotToPick?.let { target ->
            CardPickerDialog(
                title = when (target) {
                    is SelectionTarget.Hero1 -> "Pick your 1st Hole Card"
                    is SelectionTarget.Hero2 -> "Pick your 2nd Hole Card"
                    is SelectionTarget.Board -> "Choose Flop/Turn/River Card"
                    is SelectionTarget.OpponentCard -> "Assign exact card to opponent"
                },
                selectedCards = uiState.getAllSelectedCards(),
                onCardSelected = { card ->
                    pokerViewModel.selectTarget(target)
                    pokerViewModel.setCardInActiveTarget(card)
                    targetSlotToPick = null
                    Toast.makeText(context, "Assigned ${card}! Real-time HUD stats updated.", Toast.LENGTH_SHORT).show()
                },
                onCleared = {
                    pokerViewModel.clearSlot(target)
                    targetSlotToPick = null
                },
                onDismiss = { targetSlotToPick = null }
            )
        }

        // 4. "MORE INFORMATION" MODAL DIALOGS
        moreInfoTopic?.let { topic ->
            AlertDialog(
                onDismissRequest = { moreInfoTopic = null },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color(0xFFD82229),
                textContentColor = Color(0xFFE0E0E0),
                title = {
                    Text(
                        text = topic,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = when (topic) {
                            "Winning probabilities" -> "Calculates your overall mathematical equity (probability of winning or splitting the pot) after running thousands of random simulated matchups. Takes into consideration the specific cards on board and remaining live opponents."
                            "Hand strength probability distribution" -> "Segments all potential winning hands into twenty equal clusters (top 1/20, top 2/20 up to top 20/20). Informs you exactly where your private pre-flop starting cards place inside the global hand range spectrum."
                            "Winning probabilities vs. Sklansky groups" -> "Ranks starting poker holdings according to standard Sklansky & Malmuth groups (Group 1 with AA, KK down to weak Group 8 hands). Shows your exact equity and optimal response vectors mathematically derived when matching up against these distinct groups."
                            "Opponent Advanced Stats" -> "Collects player profiles over time. Shows HUD parameters next to each avatar on table—VPIP (Voluntarily Put money in Pot), PFR (Pre-flop Raise rate), and AF (Aggression Factor) to aid in predicting bluffs."
                            "Termux ADB & Broadcast integration" -> "You can control this HUD app and capture real-time calculated results using custom scripts in Termux:\n\n1. DISPATCH CARDS FROM TERMUX:\nam broadcast -a com.example.UPDATE_CARDS --es hole1 Ah --es hole2 Ad --es flop1 Ks --es flop2 Qd --es flop3 Jc\n\n2. TRIGGER SCREEN INTENT CLICK:\nam broadcast -a com.example.CLICK --ef x 450.0 --ef y 1250.0\n\n3. EXPORT LIVE RESULTS FROM HUD LOGCAT IN REALTIME:\nlogcat -s POKER_HUD_LOG"
                            else -> "Continuous calculation solver monitors active card layout, and updates the floating HUD layout elements periodically."
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD82229)),
                        onClick = { moreInfoTopic = null }
                    ) {
                        Text("UNDERSTOOD", fontWeight = FontWeight.Black)
                    }
                }
            )
        }
    }
}

// ==========================================
// 1. SETTINGS DESIGN (Screenshot 2 Theme)
// ==========================================
@Composable
fun SettingsLayout(
    tabIndex: Int,
    onTabChecked: (Int) -> Unit,
    winProbToggle: Boolean,
    onWinProbChange: (Boolean) -> Unit,
    handStrengthToggle: Boolean,
    onHandStrengthChange: (Boolean) -> Unit,
    sklanskyToggle: Boolean,
    onSklanskyChange: (Boolean) -> Unit,
    advStatsToggle: Boolean,
    onAdvStatsChange: (Boolean) -> Unit,
    showActionAdvisor: Boolean,
    onShowAdvisorChange: (Boolean) -> Unit,
    multiDataScannerToggle: Boolean,
    onMultiDataScannerChange: (Boolean) -> Unit,
    onMoreInfoClicked: (String) -> Unit,
    uiState: PokerUiState,
    pokerViewModel: PokerViewModel,
    isAccessibilityActive: Boolean,
    onPlayClicked: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B4D22)) // Bright Lush Casino Felt Green Background
    ) {
        // --- A. Red Header (Screenshot 2) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFD82229)) // Vibrant Crimson Red Header
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Poker Equity HUD",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
            var optionsMenuExpanded by remember { mutableStateOf(false) }
            Box {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options menu",
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { 
                            optionsMenuExpanded = true
                        }
                )
                DropdownMenu(
                    expanded = optionsMenuExpanded,
                    onDismissRequest = { optionsMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF2E2E2E))
                ) {
                    DropdownMenuItem(
                        text = { Text("Activate HUD Overlay", color = Color.White, fontSize = 13.sp) },
                        onClick = {
                            optionsMenuExpanded = false
                            val activity = context.findActivity() as? MainActivity
                            val ok = activity?.checkAndRequestOverlayPermission(context) ?: true
                            if (ok) {
                                try {
                                    val serviceIntent = Intent(context, PokerHudService::class.java)
                                    ContextCompat.startForegroundService(context, serviceIntent)
                                    Toast.makeText(context, "HUD Overlay Service Started", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to start PokerHudService from menu: ${e.message}", e)
                                    Toast.makeText(context, "Error starting HUD: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "Please grant Overlay Permissions first!", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Stop HUD Overlay", color = Color.White, fontSize = 13.sp) },
                        onClick = {
                            optionsMenuExpanded = false
                            try {
                                val stopIntent = Intent(context, PokerHudService::class.java).apply {
                                    action = "STOP_HUD"
                                }
                                context.startService(stopIntent)
                                Toast.makeText(context, "HUD Overlay Service Stopped", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to stop PokerHudService: ${e.message}", e)
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0x33FFFFFF))
                    DropdownMenuItem(
                        text = { Text("Accessibility clicker Settings", color = Color.White, fontSize = 13.sp) },
                        onClick = {
                            optionsMenuExpanded = false
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Please enable Accessibility service dynamically.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Termux API Integration Help", color = Color.White, fontSize = 13.sp) },
                        onClick = {
                            optionsMenuExpanded = false
                            onMoreInfoClicked("Termux ADB & Broadcast integration")
                        }
                    )
                }
            }
        }

        // --- B. Red Tab Strip (Screenshot 2 layout) ---
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = Color(0xFF1B4D22),
            contentColor = Color(0xFFD82229),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                    color = Color(0xFFD82229),
                    height = 3.dp
                )
            }
        ) {
            Tab(
                selected = tabIndex == 0,
                onClick = { onTabChecked(0) },
                text = {
                    Text(
                        text = "Game interpretation",
                        fontWeight = if (tabIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (tabIndex == 0) Color.White else Color(0x99FFFFFF)
                    )
                }
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { onTabChecked(1) },
                text = {
                    Text(
                        text = "Visualization of calculations",
                        fontWeight = if (tabIndex == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (tabIndex == 1) Color.White else Color(0x99FFFFFF)
                    )
                }
            )
        }

        // --- C. Tab Content Panels ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (tabIndex == 0) {
                // Toggles and list options
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Option Card 1: Winning Probabilities
                    val winProbMaxHands by PokerHudSharedState.winProbMaxHands.collectAsStateWithLifecycle()
                    val winProbScale by PokerHudSharedState.winProbScale.collectAsStateWithLifecycle()
                    SettingsToggleCard(
                        title = "Winning probabilities",
                        description = "Shows the chance your hand will be the best among 2,3,4... players.",
                        checked = winProbToggle,
                        onCheckedChange = onWinProbChange,
                        onMoreInfo = { onMoreInfoClicked("Winning probabilities") },
                        settingsContent = {
                            Column {
                                Text("Max opponents hands to display: $winProbMaxHands", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = winProbMaxHands.toFloat(),
                                    onValueChange = { PokerHudSharedState.winProbMaxHands.value = it.toInt() },
                                    valueRange = 2f..9f,
                                    steps = 6,
                                    modifier = Modifier.height(24.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Visual UI scaling: ${String.format(Locale.US, "%.2f", winProbScale)}x", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = winProbScale,
                                    onValueChange = { PokerHudSharedState.winProbScale.value = it },
                                    valueRange = 0.6f..2.0f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    )

                    // Option Card 2: Hand Strength Probability Distribution
                    val handStrengthScale by PokerHudSharedState.handStrengthScale.collectAsStateWithLifecycle()
                    SettingsToggleCard(
                        title = "Hand strength probability distribution",
                        description = "Shows the probabilities that your hand will be among the top 1/20 of all possible hands, among the top 2/20, etc",
                        checked = handStrengthToggle,
                        onCheckedChange = onHandStrengthChange,
                        onMoreInfo = { onMoreInfoClicked("Hand strength probability distribution") },
                        settingsContent = {
                            Column {
                                Text("Visual UI scaling: ${String.format(Locale.US, "%.2f", handStrengthScale)}x", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = handStrengthScale,
                                    onValueChange = { PokerHudSharedState.handStrengthScale.value = it },
                                    valueRange = 0.6f..2.0f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    )

                    // Option Card 3: Winning Probabilities vs Sklansky Groups
                    val sklanskyScale by PokerHudSharedState.sklanskyScale.collectAsStateWithLifecycle()
                    SettingsToggleCard(
                        title = "Winning probabilities vs. Sklansky groups",
                        description = "Shows the chances to win against the different Sklansky groups.",
                        checked = sklanskyToggle,
                        onCheckedChange = onSklanskyChange,
                        onMoreInfo = { onMoreInfoClicked("Winning probabilities vs. Sklansky groups") },
                        settingsContent = {
                            Column {
                                Text("Visual UI scaling: ${String.format(Locale.US, "%.2f", sklanskyScale)}x", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = sklanskyScale,
                                    onValueChange = { PokerHudSharedState.sklanskyScale.value = it },
                                    valueRange = 0.6f..2.0f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    )

                    // Option Card 4: Detailed Opponent HUD Stats
                    val advStatsScale by PokerHudSharedState.advStatsScale.collectAsStateWithLifecycle()
                    val advMinHands by PokerHudSharedState.advMinHands.collectAsStateWithLifecycle()
                    SettingsToggleCard(
                        title = "Show advanced opponent stats overlay",
                        description = "Displays live-updated stats (VPIP, PFR, hands profile) over opponents in the room.",
                        checked = advStatsToggle,
                        onCheckedChange = onAdvStatsChange,
                        onMoreInfo = { onMoreInfoClicked("Opponent Advanced Stats") },
                        settingsContent = {
                            Column {
                                Text("Min hands to show stats: $advMinHands", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = advMinHands.toFloat(),
                                    onValueChange = { PokerHudSharedState.advMinHands.value = it.toInt() },
                                    valueRange = 0f..500f,
                                    steps = 49,
                                    modifier = Modifier.height(24.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Visual UI scaling: ${String.format(Locale.US, "%.2f", advStatsScale)}x", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = advStatsScale,
                                    onValueChange = { PokerHudSharedState.advStatsScale.value = it },
                                    valueRange = 0.6f..2.0f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    )

                    // Option Card 5: Show Real-Time Game Advisor overlay
                    val actionAdvisorScale by PokerHudSharedState.actionAdvisorScale.collectAsStateWithLifecycle()
                    val actionAdvisorAggression by PokerHudSharedState.actionAdvisorAggression.collectAsStateWithLifecycle()
                    SettingsToggleCard(
                        title = "Action Advisor recommendation overlay",
                        description = "Triggers an intelligent overlay recommending CHECK, FOLD, CALL, RAISE or ALL-IN based on blinds, stack depth and position calculations.",
                        checked = showActionAdvisor,
                        onCheckedChange = onShowAdvisorChange,
                        onMoreInfo = { onMoreInfoClicked("Action Advisor Overlay") },
                        settingsContent = {
                            Column {
                                Text("Advisor Playstyle: ${if(actionAdvisorAggression == 0) "Normal" else if(actionAdvisorAggression == 1) "Aggressive" else "Safe"}", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = actionAdvisorAggression.toFloat(),
                                    onValueChange = { PokerHudSharedState.actionAdvisorAggression.value = it.toInt() },
                                    valueRange = 0f..2f,
                                    steps = 1,
                                    modifier = Modifier.height(24.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Visual UI scaling: ${String.format(Locale.US, "%.2f", actionAdvisorScale)}x", color = Color.LightGray, fontSize = 11.sp)
                                Slider(
                                    value = actionAdvisorScale,
                                    onValueChange = { PokerHudSharedState.actionAdvisorScale.value = it },
                                    valueRange = 0.6f..2.0f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    )

                    // Option Card 6: Automatic Screen Parsing and Adaptive Framing (CoinPoker)
                    SettingsToggleCard(
                        title = "Автоматическое сканирование экрана и адаптивные фреймы",
                        description = "Собирает всю информацию с виджетов игроков за столом: ники, VPIP, действия (колл/пас/рейз), банк, текущие ставки и стадию игры с автоматической расстановкой фреймов считывания (оптимизировано под CoinPoker). Сбои в распознавании не влияют на работу HUD.",
                        checked = multiDataScannerToggle,
                        onCheckedChange = onMultiDataScannerChange,
                        onMoreInfo = { onMoreInfoClicked("Автоматическое сканирование экрана") }
                    )

                    // Option Card 6: Accessibility Service for Termux Autoclicker Control
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F0F0F))
                            .border(BorderStroke(1.2.dp, Color(0xFF2196F3)), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🤖 Auto-Clicker Gestures",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                val isRunning = isAccessibilityActive
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isRunning) "ACTIVE" else "DISABLED",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Allows the background AI / Termux script to dispatch clicks and swipes on top of external poker tables for automated play.",
                                color = Color(0xFFEEEEEE),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            // Restricted Settings Guide
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFFB300).copy(alpha = 0.12f))
                                    .border(BorderStroke(1.dp, Color(0xFFFFB300)), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "⚠️ РЕШЕНИЕ: Доступ ограничен / Restricted Setting",
                                        color = Color(0xFFFFB300),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Если кнопка включения серая и Android пишет 'Доступ ограничен':\n\n" +
                                                "1. Откройте системные Настройки ➔ Приложения ➔ Poker Equity HUD.\n" +
                                                "2. В правом верхнем углу нажмите три точки (меню).\n" +
                                                "3. Выберите 'Разрешить ограниченные настройки' (Allow restricted settings) и введите PIN.\n" +
                                                "4. Снова откройте спец. возможности — теперь кнопку можно нажать!",
                                        color = Color(0xFFE0E0E0),
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAccessibilityActive) Color(0xFF37474F) else Color(0xFF2196F3)
                                ),
                                shape = RoundedCornerShape(6.dp),
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open Accessibility settings dynamically. Please open settings manually and enable 'Poker HUD Automated Clicker'.", Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text(
                                    text = if (isAccessibilityActive) "RECONFIGURE SERVICE" else "ACTIVATE AUTOMATION SERVICE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))
                }
            } else {
                // Calculations viz and telemetry configs
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "LIVE DATA TELEMETRY",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                    
                    // Detailed opponent selector config panel
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F0F0F))
                            .border(BorderStroke(1.dp, Color(0xFFD82229)), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Live Opponent Stats",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Real-time statistics tracked for active players at the table. (TODO: Implement automated VPIP/PFR extraction via OCR)",
                                color = Color(0xB3FFFFFF),
                                fontSize = 11.sp
                            )

                            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 4.dp))

                            uiState.opponents.forEach { opp ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(if (opp.nickname.isNotEmpty()) opp.nickname else "Empty Seed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            "VPIP: ${String.format(Locale.US, "%.0f", opp.stats?.vpip ?: 0f)}% | PFR: ${String.format(Locale.US, "%.0f", opp.stats?.pfr ?: 0f)}% | Hands: ${opp.stats?.handsPlayed ?: 0}",
                                            color = Color.LightGray,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Sklansky Group distribution preview of current cards
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F0F0F))
                            .border(BorderStroke(1.dp, Color(0xFFD82229)), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Starting cards hand class",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (uiState.heroCard1 != null && uiState.heroCard2 != null) {
                                val groupNum = AdvisorEngine.getSklanskyGroup(uiState.heroCard1!!, uiState.heroCard2!!)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFD82229)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("G$groupNum", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Column {
                                        Text(
                                            text = "Sklansky Hold'em Hand Category $groupNum",
                                            fontSize = 12.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val tierText = when (groupNum) {
                                            1, 2 -> "Premium Tier starting card power. Maximizing call and raise equity."
                                            3, 4, 5 -> "Medium strength holdings. Playable drawing potential from BTN/CO."
                                            else -> "Marginal category starting combo. Caution requested across early seats."
                                        }
                                        Text(tierText, fontSize = 10.sp, color = Color.LightGray)
                                    }
                                }
                            } else {
                                Text(
                                    text = "Assign starting hole cards inside the HUD overlay to trace automatic Sklansky strength classifications.",
                                    fontSize = 11.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }

                    // Performance & Accuracy configurations
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F0F0F))
                            .border(BorderStroke(1.dp, Color(0xFFD82229)), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Solver Monte Carlo fidelity",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1500, 3000, 5000).forEach { size ->
                                    val isSelected = uiState.simulationSize == size
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) Color(0xFFD82229) else Color(0xFF1E1E1E)
                                        ),
                                        onClick = { pokerViewModel.changeSimulationSize(size) }
                                    ) {
                                        Text("${size} runs", fontSize = 11.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Layout Calibration HUD Overlays
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F0F0F))
                            .border(BorderStroke(1.dp, Color(0xFFD82229)), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Calibration bounding boxes map layout",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Disable boxes after setting their coordinates over the poker table to leave only probabilities and statistics visually on screen during actual play.",
                                color = Color(0xB3FFFFFF),
                                fontSize = 11.sp
                            )

                            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 4.dp))

                            val showCommBox by PokerHudSharedState.showCommBox.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { PokerHudSharedState.showCommBox.value = !showCommBox }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showCommBox,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD82229), uncheckedColor = Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Community Cards scanning box", color = Color.White, fontSize = 12.sp)
                            }

                            val showHoleBox by PokerHudSharedState.showHoleBox.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { PokerHudSharedState.showHoleBox.value = !showHoleBox }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showHoleBox,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD82229), uncheckedColor = Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Hole Cards scanning box", color = Color.White, fontSize = 12.sp)
                            }

                            val showProbsBox by PokerHudSharedState.showProbsBox.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { PokerHudSharedState.showProbsBox.value = !showProbsBox }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showProbsBox,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD82229), uncheckedColor = Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Probabilities dashboard map location", color = Color.White, fontSize = 12.sp)
                            }

                            val showAdvisorBox by PokerHudSharedState.showAdvisorBox.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { PokerHudSharedState.showAdvisorBox.value = !showAdvisorBox }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showAdvisorBox,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD82229), uncheckedColor = Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Action Advisor panel location", color = Color.White, fontSize = 12.sp)
                            }

                            val showOppBox by PokerHudSharedState.showOpponentsBox.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { PokerHudSharedState.showOpponentsBox.value = !showOppBox }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showOppBox,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD82229), uncheckedColor = Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Opponents advanced stats frame", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }

        // --- D. GIGANTIC RED FLOATING PLAY BUTTON AT BOTTOM CENTER (Screenshot 2 style) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD82229)) // Glowing Crimson Red Circular Button
                    .border(BorderStroke(2.dp, Color.White), CircleShape)
                    .shadow(elevation = 10.dp, shape = CircleShape)
                    .clickable { onPlayClicked() },
                contentAlignment = Alignment.Center
            ) {
                // Play triangle icon (white)
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start HUD overlay process",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

// Option item card modeled directly after Screenshot 2 design
@Composable
fun SettingsToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onMoreInfo: () -> Unit,
    settingsContent: @Composable (() -> Unit)? = null
) {
    var showSettings by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F0F0F)) // Dark charcoal background
            .border(BorderStroke(1.2.dp, Color(0xFFD82229)), RoundedCornerShape(10.dp)) // Vibrant red neon outline
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        color = Color(0xFFEEEEEE),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "More information",
                            color = Color(0xFFD82229),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clickable { onMoreInfo() }
                                .padding(vertical = 4.dp)
                        )
                        if (settingsContent != null && checked) {
                            Spacer(modifier = Modifier.width(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { showSettings = !showSettings }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = if (showSettings) Color.White else Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Settings",
                                    color = if (showSettings) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                // Switch switch
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFD82229),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF222222)
                    ),
                    modifier = Modifier.testTag("toggle_" + title.replace(" ", "_").lowercase())
                )
            }
            if (showSettings && settingsContent != null && checked) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0x33D82229), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
                settingsContent()
            }
        }
    }
}


// ==========================================
// 2. HUD INTERACTIVE SIMULATOR (CoinPoker Overlay & Calibration)
// ==========================================
@Composable
fun OverlaySimulatorLayout(
    uiState: PokerUiState,
    pokerViewModel: PokerViewModel,
    hudScale: Float,
    hudOpacity: Float,
    onHudScaleChanged: (Float) -> Unit,
    onHudOpacityChanged: (Float) -> Unit,
    winProbToggle: Boolean,
    handStrengthToggle: Boolean,
    sklanskyToggle: Boolean,
    advStatsToggle: Boolean,
    showActionAdvisor: Boolean,
    multiDataScannerToggle: Boolean,
    onCloseHUD: () -> Unit,
    onTriggerCardSelection: (SelectionTarget) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    var isPanelMinimized by remember { mutableStateOf(false) }
    
    val isGameMode by PokerHudSharedState.isGameMode.collectAsStateWithLifecycle()
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Automatic trigger: transition to Game Mode after 7 seconds of inactivity
    LaunchedEffect(isGameMode, lastInteractionTime) {
        if (!isGameMode) {
            delay(7000)
            PokerHudSharedState.isGameMode.value = true
        }
    }
    
    // Target visibility states for each panel (can close then toggle back on from options)
    val showCommBox by PokerHudSharedState.showCommBox.collectAsStateWithLifecycle()
    val showHoleBox by PokerHudSharedState.showHoleBox.collectAsStateWithLifecycle()
    val showProbsBox by PokerHudSharedState.showProbsBox.collectAsStateWithLifecycle()
    val showAdvisorBox by PokerHudSharedState.showAdvisorBox.collectAsStateWithLifecycle()
    val showOpponentsBox by PokerHudSharedState.showOpponentsBox.collectAsStateWithLifecycle()
    
    // Trigger scanning states
    val isScanning by PokerHudSharedState.isScanning.collectAsStateWithLifecycle()
    
    // Instead of local state, lets read status if we need a global one or just show standard info if not scanning inside Main App layout
    val scanLoggedStep = if (isScanning) "OCR Pipeline Active. Bounding Boxes Aligned." else "Status: Waiting for Scanner..."
    
    // Draggable offsets of the hand probabilities overlay widget
    var floatWidgetOffsetX by remember { mutableFloatStateOf(10f) }
    var floatWidgetOffsetY by remember { mutableFloatStateOf(60f) }
    
    // Draggable coordinates for Community Crop Box
    var commCoordsX by remember { mutableFloatStateOf(15f) }
    var commCoordsY by remember { mutableFloatStateOf(120f) }
    
    // Draggable coordinates for Hole Cards Crop Box
    var holeCoordsX by remember { mutableFloatStateOf(15f) }
    var holeCoordsY by remember { mutableFloatStateOf(290f) }

    // Position coordinate offsets for Advisor overlay
    var advisorOffsetX by remember { mutableFloatStateOf(15f) }
    var advisorOffsetY by remember { mutableFloatStateOf(440f) }

    // Floating Scanner laser sweeper animation configuration
    val infiniteTransition = rememberInfiniteTransition(label = "Radar Sweep")
    val laserOffsetRatio by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Laser sweep line"
    )

    val scannerOpacity by animateFloatAsState(
        targetValue = if (isGameMode) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "Scanner Opacity Animation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF131313)) // Plain, solid dark background color. No table imitation at all.
            .pointerInput(isGameMode) {
                if (!isGameMode) {
                    detectTapGestures(
                        onPress = {
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    )
                }
            }
    ) {
        if (multiDataScannerToggle && scannerOpacity > 0.01f) {
            Box(
                modifier = Modifier
                    .graphicsLayer(alpha = scannerOpacity)
                    .fillMaxSize()
            ) {
                // Draw felt color background accent
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-110).dp)
                        .size(width = 330.dp, height = 250.dp)
                        .clip(RoundedCornerShape(125.dp))
                        .background(Color(0xFF104624))
                        .border(BorderStroke(4.dp, Color(0xFF1E6C38)), shape = RoundedCornerShape(125.dp))
                ) {
                    // Draw a beautiful center line decor
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(113.dp))
                    )
                }

                // Draw CoinPoker table automatic adaptive reader frames
                // TODO: Sync these coordinate layouts with actual player slots based on resolution
                val coordsMap = listOf(
                    Pair(135.dp, 30.dp),
                    Pair(15.dp, 110.dp),
                    Pair(255.dp, 110.dp),
                    Pair(40.dp, 210.dp),
                    Pair(230.dp, 210.dp)
                )

                uiState.opponents.forEachIndexed { idx, opp ->
                    if (idx < coordsMap.size) {
                        SeatScannerFrame(
                            label = "[Seat ${idx + 1}: ${opp.nickname}]\nVPIP: ${String.format(Locale.US, "%.0f", opp.stats?.vpip ?: 0f)}% | Bet: $${opp.betSize}",
                            x = coordsMap[idx].first,
                            y = coordsMap[idx].second,
                            width = 105.dp,
                            height = 36.dp,
                            isActive = opp.isActive
                        )
                    }
                }

                // Centered automatic Pot and Stage parsing frame
                SeatScannerFrame(
                    label = "[Pot Zone Parser]\nPot: $${uiState.potSize}",
                    x = 135.dp, y = 145.dp, width = 100.dp, height = 36.dp,
                    isActive = true
                )
            }
        }









        // --- 4. UNIFIED HUD CONTROL PANEL (Upper Right Config Matrix) ---
        if (!isGameMode) {
            if (isPanelMinimized) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 14.dp)
                    .clickable { isPanelMinimized = false }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xE6121A24))
                    .border(BorderStroke(1.dp, Color(0xFF2196F3)), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Expand Panel",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(14.dp)
                    )
                    Text("SHOW HUD CONTROL", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 14.dp)
                    .width(210.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xF2121A24))
                    .border(BorderStroke(1.dp, Color(0xFF2196F3)), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Panel Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "HUD Scraper Control",
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "HUD CONTROL PANEL",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                onClick = { isPanelMinimized = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Text("HIDE", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD82229)),
                                onClick = { onCloseHUD() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Text("EXIT", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                HorizontalDivider(color = Color(0x22FFFFFF))

                // Scraper step state log
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF070B0F))
                        .padding(6.dp)
                ) {
                    Text(
                        text = scanLoggedStep,
                        color = if (isScanning) Color(0xFF00FFCC) else Color.LightGray,
                        fontSize = 8.sp,
                        lineHeight = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                HorizontalDivider(color = Color(0x16FFFFFF))

                // Toggle visibility controls to easily bring back hidden panels!
                Text(text = "Overlay panel visibility:", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelVisibilityToggleRow(
                        label = "Community Box",
                        visible = showCommBox,
                        onChanged = { PokerHudSharedState.showCommBox.value = it },
                        color = Color(0xFF2196F3)
                    )
                    PanelVisibilityToggleRow(
                        label = "Hole Cards Box",
                        visible = showHoleBox,
                        onChanged = { PokerHudSharedState.showHoleBox.value = it },
                        color = Color(0xFFE53935)
                    )
                    PanelVisibilityToggleRow(
                        label = "Properties HUD",
                        visible = showProbsBox,
                        onChanged = { PokerHudSharedState.showProbsBox.value = it },
                        color = Color(0xFFFFD700)
                    )
                    PanelVisibilityToggleRow(
                        label = "Action Advisor",
                        visible = showAdvisorBox,
                        onChanged = { PokerHudSharedState.showAdvisorBox.value = it },
                        color = Color(0xFF00FFCC)
                    )
                    PanelVisibilityToggleRow(
                        label = "Opponents Profile",
                        visible = showOpponentsBox,
                        onChanged = { PokerHudSharedState.showOpponentsBox.value = it },
                        color = Color(0xFF90CAF9)
                    )
                }

                HorizontalDivider(color = Color(0x16FFFFFF))

                // Customizer Sliders
                Text("HUD customizer scaling:", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                
                Column {
                    Text("Scale: ${String.format(Locale.US, "%.2f", hudScale)}x", color = Color.LightGray, fontSize = 8.sp)
                    Slider(
                        value = hudScale,
                        onValueChange = onHudScaleChanged,
                        valueRange = 0.7f..1.3f,
                        modifier = Modifier.height(20.dp)
                    )
                }

                Column {
                    Text("Opacity: ${String.format(Locale.US, "%.0f", hudOpacity * 100f)}%", color = Color.LightGray, fontSize = 8.sp)
                    Slider(
                        value = hudOpacity,
                        onValueChange = onHudOpacityChanged,
                        valueRange = 0.4f..1.0f,
                        modifier = Modifier.height(20.dp)
                    )
                }

                Text(
                    text = "Tip: Drag frames around with finger. Assign cards from table.",
                    color = Color.Gray,
                    fontSize = 8.sp,
                    lineHeight = 10.sp
                )
            }
        }
    }
    }
}
}

@Composable
fun SeatScannerFrame(
    label: String,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    isActive: Boolean
) {
    Box(
        modifier = Modifier
            .offset(x, y)
            .width(width)
            .height(height)
            .border(
                BorderStroke(
                    1.2.dp,
                    if (isActive) Color(0xFF00FFCC) else Color(0x3300FFCC)
                ),
                shape = RoundedCornerShape(4.dp)
            )
            .background(if (isActive) Color(0x1B00FFCC) else Color(0x0500FFCC))
            .padding(2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.size(3.dp).background(if (isActive) Color(0xFF00FFCC) else Color(0x5500FFCC)))
                Box(modifier = Modifier.size(3.dp).background(if (isActive) Color(0xFF00FFCC) else Color(0x5500FFCC)))
            }
            Text(
                text = label,
                color = if (isActive) Color(0xFF00FFCC) else Color(0x9900FFCC),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                lineHeight = 8.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.size(3.dp).background(if (isActive) Color(0xFF00FFCC) else Color(0x5500FFCC)))
                Box(modifier = Modifier.size(3.dp).background(if (isActive) Color(0xFF00FFCC) else Color(0x5500FFCC)))
            }
        }
    }
}

@Composable
fun PanelVisibilityToggleRow(
    label: String,
    visible: Boolean,
    onChanged: (Boolean) -> Unit,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(!visible) }
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 10.sp)
        }
        // Small custom radio/switch box
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (visible) color else Color(0xFF222222))
                .border(BorderStroke(1.dp, if (visible) color else Color.Gray), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (visible) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

// Interactive player avatar widget replicating Screenshot 1 design
@Composable
fun OpponentAvatarWidget(
    name: String,
    stack: String,
    avatarChar: String,
    avatarBgColor: Color,
    betText: String,
    isDealer: Boolean = false,
    isActive: Boolean = true,
    countdownActive: Boolean = false,
    isCheckText: Boolean = false,
    statsOverride: String? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.wrapContentSize()
    ) {
        // Active chip bet label if any
        if (betText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCheckText) Color(0xFF2E7D32) else Color(0xFFE65100))
                    .border(BorderStroke(1.dp, Color.White), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = betText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp)
        ) {
            // Count countdown progression timer on left side (e.g. Berenjain progress ring)
            if (countdownActive) {
                CircularProgressIndicator(
                    progress = { 0.75f },
                    color = Color(0xFFFFD700),
                    trackColor = Color(0x33FFFFFF),
                    strokeWidth = 3.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Avatar central circle
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(avatarBgColor)
                    .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.8f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarChar.take(3),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            // Dealer Button "D" if dealer
            if (isDealer) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(BorderStroke(1.dp, Color.Red), CircleShape)
                        .align(Alignment.BottomStart)
                        .offset(x = (-2).dp, y = (2).dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("D", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Name & Stack info card
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xE6050505))
                .border(BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "$stack",
                    color = Color(0xFFFFD54F),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Opponent profile HUD stats right under avatar if enabled
        if (statsOverride != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(statsOverride, color = Color(0xFF00FFCC), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// Elegant Micro Card View inside the HUD overlays
@Composable
fun MiniCardView(
    card: Card?,
    label: String,
    onClick: (() -> Unit)? = null
) {
    val baseModifier = Modifier
        .width(42.dp)
        .height(58.dp)
        .shadow(4.dp, RoundedCornerShape(4.dp))
        .clip(RoundedCornerShape(4.dp))
        .background(if (card != null) Color.White else Color(0xFF22313F))
        .border(
            BorderStroke(
                1.dp, 
                if (card != null) Color.LightGray else Color(0x33FFFFFF)
            ), 
            RoundedCornerShape(4.dp)
        )
    
    val appliedModifier = if (onClick != null) {
        baseModifier.clickable { onClick() }
    } else {
        baseModifier
    }

    Box(
        modifier = appliedModifier,
        contentAlignment = Alignment.Center
    ) {
        if (card != null) {
            val suitColor = Color(android.graphics.Color.parseColor(card.suit.colorHex))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = card.rank.symbol,
                    color = suitColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = card.suit.symbol,
                    color = suitColor,
                    fontSize = 16.sp
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "+",
                    color = Color.Gray,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    color = Color.Gray,
                    fontSize = 7.sp
                )
            }
        }
    }
}


// Interactive dialog allowing users to pick cards for testing
@Composable
fun CardPickerDialog(
    title: String,
    selectedCards: Set<Card>,
    onCardSelected: (Card) -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141F26),
        titleContentColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF9800)),
                    onClick = { onCleared() }
                ) {
                    Text("CLEAR SLOT", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Render card matrix categories
                val suits = Suit.values()
                val ranks = Rank.values().reversed() // Ace at top

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suits.forEach { suit ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Category Icon Label
                            val specColor = Color(android.graphics.Color.parseColor(suit.colorHex))
                            Text(
                                text = suit.symbol,
                                color = specColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                modifier = Modifier.width(20.dp)
                            )

                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ranks.forEach { rank ->
                                    val currentCard = Card(rank, suit)
                                    val isUsed = selectedCards.contains(currentCard)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(0.7f)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                if (isUsed) Color(0x33FFFFFF) else Color.White
                                            )
                                            .border(
                                                BorderStroke(1.dp, Color.LightGray),
                                                RoundedCornerShape(3.dp)
                                            )
                                            .clickable(enabled = !isUsed) {
                                                onCardSelected(currentCard)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = rank.symbol,
                                            color = if (isUsed) Color.DarkGray else specColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                onClick = onDismiss
            ) {
                Text("DISMISS", fontWeight = FontWeight.Bold)
            }
        }
    )
}
