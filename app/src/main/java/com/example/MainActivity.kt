package com.example

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.GameDatabase
import com.example.db.HighScoreRepository
import com.example.game.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

// Dynamic retro tone player
object GameChimePlayer {
    private var toneGenerator: ToneGenerator? = null
    var soundEnabled = true

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    fun playFlap() {
        if (!soundEnabled) return
        try {
            // Quick short chirpy beep
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
        } catch (e: Exception) {}
    }

    fun playScore() {
        if (!soundEnabled) return
        try {
            // High pitch gold coin pickup chime
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 120)
        } catch (e: Exception) {}
    }

    fun playCrash() {
        if (!soundEnabled) return
        try {
            // Heavy buzzing error crash tone
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 300)
        } catch (e: Exception) {}
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        FlappyBirdGameScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun FlappyBirdGameScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Setup Room components via lazy initialization
    val database = remember { GameDatabase.getDatabase(context) }
    val repository = remember { HighScoreRepository(database.highScoreDao()) }
    val factory = remember { GameViewModelFactory(repository) }

    // Resolve Game ViewModel
    val viewModel: GameViewModel = viewModel(factory = factory)

    // Collect States
    val gameState by viewModel.gameState.collectAsState()
    val currentDifficulty by viewModel.difficulty.collectAsState()
    val currentSkin by viewModel.skin.collectAsState()
    val currentTheme by viewModel.theme.collectAsState()
    val playerName by viewModel.playerName.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()

    val score by viewModel.score.collectAsState()
    val highScore by viewModel.highScore.collectAsState()

    // Game Objects flow
    val birdY by viewModel.birdY.collectAsState()
    val birdAngle by viewModel.birdAngle.collectAsState()
    val birdVelocityY by viewModel.birdVelocityY.collectAsState()
    val pipes by viewModel.pipes.collectAsState()
    val clouds by viewModel.clouds.collectAsState()
    val hills by viewModel.hills.collectAsState()
    val particles by viewModel.particles.collectAsState()
    val groundOffset by viewModel.groundOffset.collectAsState()

    // Sound Toggle State
    var soundEffectsOn by remember { mutableStateOf(true) }
    GameChimePlayer.soundEnabled = soundEffectsOn

    // Haptic & Chime side-effect observers
    val vibeTrigger by viewModel.vibeTrigger.collectAsState()
    var lastVibeId by remember { mutableStateOf(0L) }

    LaunchedEffect(vibeTrigger) {
        if (vibeTrigger > 0L && vibeTrigger != lastVibeId) {
            lastVibeId = vibeTrigger
            if (hapticsEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // Play chimes matching gameplay incidents
    var lastScore by remember { mutableStateOf(0) }
    LaunchedEffect(score) {
        if (score > lastScore && gameState == GameState.PLAYING) {
            GameChimePlayer.playScore()
        }
        lastScore = score
    }

    var lastState by remember { mutableStateOf(GameState.START) }
    LaunchedEffect(gameState) {
        if (gameState == GameState.PLAYING && lastState == GameState.START) {
            lastScore = 0
        } else if (gameState == GameState.GAMEOVER && lastState == GameState.PLAYING) {
            GameChimePlayer.playCrash()
        }
        lastState = gameState
    }

    // Main Box Layer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .pointerInput(gameState) {
                // Global tap anywhere during active flying leaps
                if (gameState == GameState.PLAYING) {
                    detectTapGestures(
                        onPress = {
                            viewModel.flap()
                            GameChimePlayer.playFlap()
                        }
                    )
                }
            }
            .testTag("game_screen_root")
    ) {

        // --- LAYER 1: Interactive High Performance Rendering Canvas ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("game_canvas")
        ) {
            val scaleX = size.width / viewModel.viewWidth
            val scaleY = size.height / viewModel.viewHeight

            val isNeon = currentTheme == GameTheme.NEON
            val outlineColor = if (isNeon) Color(0xFFE040FB).copy(alpha = 0.85f) else Color(0xFF533847)
            val borderStroke = Stroke(width = 4.5f * scaleX)

            // 1. Draw Sky Atmospheric Gradients
            val skyBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(currentTheme.skyColorTop),
                    Color(currentTheme.skyColorBottom)
                )
            )
            drawRect(brush = skyBrush, size = size)

            // Neon ambient grid visual decoration
            if (currentTheme == GameTheme.NEON) {
                // Background futuristic star field
                drawNeonGridDecoration(size, groundOffset * scaleX)
            }

            // 2. Draw Parallax Hills / Mountains
            val mountainBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(currentTheme.mountainColorTop),
                    Color(currentTheme.mountainColorBottom)
                )
            )
            hills.forEach { hill ->
                val hx = hill.x * scaleX
                val hH = hill.height * scaleY
                val hW = hill.width * scaleX
                
                if (currentTheme == GameTheme.NEON) {
                    // Synthwave hollow glowing wireframe mountain
                    val mPath = Path().apply {
                        moveTo(hx, viewModel.groundY * scaleY)
                        lineTo(hx + hW * 0.5f, viewModel.groundY * scaleY - hH)
                        lineTo(hx + hW, viewModel.groundY * scaleY)
                        close()
                    }
                    drawPath(path = mPath, color = Color(0x25E040FB))
                    drawPath(path = mPath, color = Color(0xFFE040FB), style = Stroke(width = 3f * scaleX))
                } else {
                    // Traditional filled parallax peaks
                    val mPath = Path().apply {
                        moveTo(hx, viewModel.groundY * scaleY)
                        lineTo(hx + hW * 0.5f, viewModel.groundY * scaleY - hH)
                        lineTo(hx + hW, viewModel.groundY * scaleY)
                        close()
                    }
                    drawPath(path = mPath, brush = mountainBrush)
                }
            }

            // 3. Draw Parallax Clouds
            clouds.forEach { cloud ->
                drawStyledCloud(
                    x = cloud.x * scaleX,
                    y = cloud.y * scaleY,
                    size = cloud.size * scaleX,
                    alpha = if (currentTheme == GameTheme.NEON) 0.15f else 0.45f
                )
            }

            // 4. Draw Pipes (Graduated 3D shading profiles with Immersive retro borders!)
            pipes.forEach { pipe ->
                val px = pipe.x * scaleX
                val pW = viewModel.pipeWidth * scaleX
                val topH = pipe.topPipeHeight * scaleY
                val botStartY = pipe.bottomPipeStartY * scaleY
                val gY = viewModel.groundY * scaleY

                val pipeBrush = Brush.horizontalGradient(
                    colors = if (isNeon) {
                        listOf(
                            Color(currentTheme.pipeColorTop),
                            Color(currentTheme.pipeColorTop).copy(alpha = 0.85f),
                            Color(currentTheme.pipeColorBottom)
                        )
                    } else {
                        listOf(
                            Color(0xFF90E040),
                            Color(0xFF73BF2F),
                            Color(0xFF52A11B)
                        )
                    }
                )

                // Top Pipe Body Fill
                drawRect(
                    brush = pipeBrush,
                    topLeft = Offset(px, 0f),
                    size = Size(pW, topH)
                )
                // Top Pipe Body Outline
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(px, -10f * scaleY),
                    size = Size(pW, topH + 10f * scaleY),
                    style = borderStroke
                )

                // Top Pipe Flange Lip Ring
                val lipHeight = 35f * scaleY
                val lipWidthPadding = 12f * scaleX
                drawRect(
                    brush = pipeBrush,
                    topLeft = Offset(px - lipWidthPadding, topH - lipHeight),
                    size = Size(pW + lipWidthPadding * 2f, lipHeight)
                )
                // Top Pipe Flange Lip Outline
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(px - lipWidthPadding, topH - lipHeight),
                    size = Size(pW + lipWidthPadding * 2f, lipHeight),
                    style = borderStroke
                )

                // Bottom Pipe Body Fill
                drawRect(
                    brush = pipeBrush,
                    topLeft = Offset(px, botStartY),
                    size = Size(pW, gY - botStartY)
                )
                // Bottom Pipe Body Outline
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(px, botStartY),
                    size = Size(pW, gY - botStartY + 10f * scaleY),
                    style = borderStroke
                )

                // Bottom Pipe Flange Lip Ring
                drawRect(
                    brush = pipeBrush,
                    topLeft = Offset(px - lipWidthPadding, botStartY),
                    size = Size(pW + lipWidthPadding * 2f, lipHeight)
                )
                // Bottom Pipe Flange Lip Outline
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(px - lipWidthPadding, botStartY),
                    size = Size(pW + lipWidthPadding * 2f, lipHeight),
                    style = borderStroke
                )
            }

            // 5. Draw Particle Sparks
            particles.forEach { p ->
                drawCircle(
                    color = Color(p.color).copy(alpha = p.alpha),
                    center = Offset(p.x * scaleX, p.y * scaleY),
                    radius = p.size * scaleY
                )
            }

            // 6. Draw Bird (Translates coordinates with smooth rotation matrices and thick retro borders!)
            val bx = viewModel.birdX * scaleX
            val by = birdY * scaleY
            val br = viewModel.birdRadius * scaleY

            withTransform({
                rotate(degrees = birdAngle, pivot = Offset(bx, by))
            }) {
                // Bird Main Body Sphere Fill
                drawCircle(
                    color = Color(currentSkin.primaryColor),
                    center = Offset(bx, by),
                    radius = br
                )
                // Bird Main Body Sphere Outline
                drawCircle(
                    color = outlineColor,
                    center = Offset(bx, by),
                    radius = br,
                    style = borderStroke
                )
                // Inner highlight sphere
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    center = Offset(bx - br * 0.2f, by - br * 0.2f),
                    radius = br * 0.5f
                )

                // Bird Flapper wing
                val wingYOffset = if (birdVelocityY < 0f) -5f * scaleY else 5f * scaleY
                drawOval(
                    color = Color(currentSkin.secondaryColor),
                    topLeft = Offset(bx - br * 0.9f, by - br * 0.4f + wingYOffset),
                    size = Size(br * 1.1f, br * 0.8f)
                )
                // Wing outline
                drawOval(
                    color = outlineColor,
                    topLeft = Offset(bx - br * 0.9f, by - br * 0.4f + wingYOffset),
                    size = Size(br * 1.1f, br * 0.8f),
                    style = borderStroke
                )

                // Big white target eye
                val eyeRadius = br * 0.4f
                val eyeCenterX = bx + br * 0.35f
                val eyeCenterY = by - br * 0.3f
                drawCircle(
                    color = Color(currentSkin.eyeColor),
                    center = Offset(eyeCenterX, eyeCenterY),
                    radius = eyeRadius
                )
                // Eye outline
                drawCircle(
                    color = outlineColor,
                    center = Offset(eyeCenterX, eyeCenterY),
                    radius = eyeRadius,
                    style = Stroke(width = 2.5f * scaleX)
                )
                // Big cartoon pupil
                drawCircle(
                    color = Color(0xFF1E1E1E),
                    center = Offset(eyeCenterX + br * 0.08f, eyeCenterY),
                    radius = eyeRadius * 0.55f
                )
                // Eyes sparkle reflect dot
                drawCircle(
                    color = Color.White,
                    center = Offset(eyeCenterX + br * 0.16f, eyeCenterY - br * 0.08f),
                    radius = eyeRadius * 0.2f
                )

                // Chubby Triangular Beak
                val beakPath = Path().apply {
                    moveTo(bx + br * 0.82f, by - br * 0.15f)
                    lineTo(bx + br * 1.55f, by + br * 0.15f)
                    lineTo(bx + br * 0.82f, by + br * 0.45f)
                    close()
                }
                drawPath(
                    path = beakPath,
                    color = Color(currentSkin.beakColor)
                )
                // Beak outline
                drawPath(
                    path = beakPath,
                    color = outlineColor,
                    style = borderStroke
                )
                // Beak accent divide line
                drawLine(
                    color = outlineColor,
                    start = Offset(bx + br * 0.82f, by + br * 0.15f),
                    end = Offset(bx + br * 1.3f, by + br * 0.15f),
                    strokeWidth = 3f * scaleX
                )
            }

            // 7. Draw Moving ground and top grass trim
            val gYPx = viewModel.groundY * scaleY
            val groundBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(currentTheme.grassColor),
                    Color(currentTheme.groundColor)
                )
            )

            // Ground base card
            drawRect(
                brush = groundBrush,
                topLeft = Offset(0f, gYPx),
                size = Size(size.width, size.height - gYPx)
            )

            // Scrolling Grass Pattern (creates rapid fast-speed horizontal movement sync)
            val stepSize = 40f * scaleX
            val remOffset = groundOffset * scaleX
            var currentSpurt = -stepSize
            while (currentSpurt < size.width + stepSize) {
                // Nice retro diagonal dark green divider dashes
                val xPos = currentSpurt - remOffset
                drawLine(
                    color = Color.Black.copy(alpha = 0.12f),
                    start = Offset(xPos, gYPx + 15f * scaleY),
                    end = Offset(xPos + 22f * scaleX, size.height),
                    strokeWidth = 10f * scaleX
                )
                currentSpurt += stepSize
            }

            // Top Grass crisp border line
            drawLine(
                color = outlineColor,
                start = Offset(0f, gYPx),
                end = Offset(size.width, gYPx),
                strokeWidth = 5f * scaleY
            )
        }

        // --- LAYER 2: Overlay UI States ---

        // A. Dynamic Game Score badge hovering during fly sessions
        if (gameState == GameState.PLAYING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(26.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = score.toString(),
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        color = Color.White,
                        letterSpacing = (-2).sp,
                        // Exact shadow offset matching flat drop-shadow-[4px_4px_0px_rgba(0,0,0,0.4)]
                        style = LocalTextStyle.current.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.4f),
                                offset = Offset(4f, 4f),
                                blurRadius = 0f
                            )
                        ),
                        modifier = Modifier.testTag("flying_score_badge")
                    )
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Leader",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "BEST: $highScore",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Quick tap cue fading out after score increases
                if (score == 0) {
                    Text(
                        text = "TAP TO JUMP!",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 80.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }

                // Immersive Ground HUD at the bottom of the screen (Personal Best & Tap to Jump)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Personal Best metric
                        Column(
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "PERSONAL BEST",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF533847),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = highScore.toString(),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF533847)
                            )
                        }

                        // Tactile Jump Button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF006A6A))
                                .clickable {
                                    viewModel.flap()
                                    GameChimePlayer.playFlap()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("hud_jump_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "TAP TO JUMP",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Jump icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // B. Main Settings Menu dashboard when in START State
        AnimatedVisibility(
            visible = gameState == GameState.START,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            StartMenuOverlay(
                score = score,
                highScore = highScore,
                playerName = playerName,
                currentDifficulty = currentDifficulty,
                currentSkin = currentSkin,
                currentTheme = currentTheme,
                soundEffectsOn = soundEffectsOn,
                hapticsEnabled = hapticsEnabled,
                viewModel = viewModel,
                onSoundToggle = { soundEffectsOn = it },
                onStartClick = { viewModel.startGame() }
            )
        }

        // C. Game Over Board Overlay when GAMEOVER
        AnimatedVisibility(
            visible = gameState == GameState.GAMEOVER,
            enter = fadeIn(animationSpec = tween(500)),
            exit = fadeOut()
        ) {
            GameOverOverlay(
                score = score,
                highScore = highScore,
                playerName = playerName,
                currentDifficulty = currentDifficulty,
                viewModel = viewModel,
                onRestartClick = { viewModel.startGame() },
                onBackToMenuClick = { viewModel.saveManualScoreAndRestart() }
            )
        }
    }
}

@Composable
fun StartMenuOverlay(
    score: Int,
    highScore: Int,
    playerName: String,
    currentDifficulty: Difficulty,
    currentSkin: BirdSkin,
    currentTheme: GameTheme,
    soundEffectsOn: Boolean,
    hapticsEnabled: Boolean,
    viewModel: GameViewModel,
    onSoundToggle: (Boolean) -> Unit,
    onStartClick: () -> Unit
) {
    val leaderboard by viewModel.leaderboard.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.90f)
                .testTag("menu_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Game Title & logo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Logo",
                        tint = Color(0xFFF7D308),
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FLAPPY BIRD",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-1).sp,
                        color = Color(0xFF006A6A),
                        style = LocalTextStyle.current.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color(0xFFDED895),
                                offset = Offset(3f, 3f),
                                blurRadius = 0f
                            )
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = 12.dp))

                // Scrollable main content panel containing settings & Leaderboards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Item 1: Nickname input config
                    Column {
                        Text(
                            text = "FLAPPER USERNAME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = playerName,
                            onValueChange = { viewModel.setPlayerName(it) },
                            placeholder = { Text("Guest Flapper") },
                            singleLine = true,
                            maxLines = 1,
                            leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = "User") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 56.dp)
                                .testTag("nickname_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // Item 2: Settings selection rows (Difficulty)
                    Column {
                        Text(
                            text = "DIFFICULTY LEVEL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Difficulty.values().forEach { d ->
                                val selected = d == currentDifficulty
                                ScopeSelectionChip(
                                    label = d.label,
                                    selected = selected,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setDifficulty(d) },
                                    testTag = "diff_chip_${d.label.lowercase()}"
                                )
                            }
                        }
                    }

                    // Item 3: Skin choice
                    Column {
                        Text(
                            text = "SELECT YOUR BIRD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BirdSkin.values().forEach { s ->
                                val selected = s == currentSkin
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1.8f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) Color(s.primaryColor) else Color(s.primaryColor).copy(
                                                alpha = 0.45f
                                            )
                                        )
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(
                                                alpha = 0.3f
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable { viewModel.setSkin(s) }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = s.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Item 4: Theme landscape choice
                    Column {
                        Text(
                            text = "TIME OF DAY THEME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GameTheme.values().forEach { t ->
                                val selected = t == currentTheme
                                val brush = Brush.linearGradient(
                                    colors = listOf(Color(t.skyColorTop), Color(t.skyColorBottom))
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(brush)
                                        .border(
                                            width = if (selected) 2.5.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable { viewModel.setTheme(t) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = t.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (t == GameTheme.DAY) Color(0xFF333333) else Color.White,
                                        style = LocalTextStyle.current.copy(
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                blurRadius = 4f
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Playback toggles (System and sound)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (soundEffectsOn) Icons.Default.PlayArrow else Icons.Default.Close,
                                contentDescription = "Sound",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retro Sound", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = soundEffectsOn,
                                onCheckedChange = onSoundToggle,
                                modifier = Modifier.scale(0.7f)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (hapticsEnabled) Icons.Default.Refresh else Icons.Default.Check,
                                contentDescription = "Haptic",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Vibrations", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = hapticsEnabled,
                                onCheckedChange = { viewModel.setHapticsEnabled(it) },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }

                    // Item 5: Quick mini leaderboard
                    if (leaderboard.isNotEmpty()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TOP HIGH SCORES",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "CLEAR ALL",
                                    fontSize = 10.sp,
                                    color = Color.Red.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { viewModel.clearLeaderboard() }
                                        .padding(4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(75.dp)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    itemsIndexed(leaderboard) { index, entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                // Gold, Silver, Bronze badges
                                                val badge = when (index) {
                                                    0 -> "🏆"
                                                    1 -> "🥈"
                                                    2 -> "🥉"
                                                    else -> "${index + 1}."
                                                }
                                                Text(
                                                    text = badge,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(24.dp)
                                                )
                                                Text(
                                                    text = entry.playerName,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 130.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "(${entry.difficulty})",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                            Text(
                                                text = "${entry.score} pts",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Empty board placeholder
                        Text(
                            text = "Leaderboard is empty. Be the first to flap a score!",
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            color = Color.Gray
                        )
                    }
                }

                // CTA Launch Button
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("launch_flap_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Play")
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "TAP TO START FLYING",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun GameOverOverlay(
    score: Int,
    highScore: Int,
    playerName: String,
    currentDifficulty: Difficulty,
    viewModel: GameViewModel,
    onRestartClick: () -> Unit,
    onBackToMenuClick: () -> Unit
) {
    val leaderboard by viewModel.leaderboard.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .testTag("gameover_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Game Over banner
                Text(
                    text = "GAME OVER",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                
                Text(
                    text = "You smashed into an obstacle!",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Score metrics readout row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("FLAP SCORE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = score.toString(),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("BEST HIGH", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = highScore.toString(),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (score >= highScore && score > 0) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFFF9C4), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = "👑 NEW HIGHSCORE RECORD! 🎉",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFE65100)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Leadboard table list heading
                Text(
                    text = "LOCAL LEADERBOARD RANKINGS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Scrollable Leaderboard
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    if (leaderboard.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            itemsIndexed(leaderboard) { index, entry ->
                                val activeSessionRow = entry.playerName == playerName && entry.score == score
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (activeSessionRow) MaterialTheme.colorScheme.primaryContainer.copy(
                                                alpha = 0.5f
                                            ) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val trophy = when (index) {
                                            0 -> "🥇"
                                            1 -> "🥈"
                                            2 -> "🥉"
                                            else -> "${index + 1}."
                                        }
                                        Text(
                                            text = trophy,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Text(
                                            text = entry.playerName,
                                            fontSize = 12.sp,
                                            fontWeight = if (activeSessionRow) FontWeight.ExtraBold else FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 140.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "[${entry.difficulty}]",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text(
                                        text = "${entry.score} pts",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No rankings registered.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action controls layout buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onBackToMenuClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("back_to_menu_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MAIN MENU", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onRestartClick,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(50.dp)
                            .testTag("restart_fly_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Fly again")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("FLY AGAIN", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ScopeSelectionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Draw futuristic scrolling starry skies
fun DrawScope.drawNeonGridDecoration(canvasSize: Size, offsetPx: Float) {
    // Top horizon height matches game engine ground bounds
    val horizonY = 860f * (canvasSize.height / 1000f)
    val totalHeight = canvasSize.height
    val totalWidth = canvasSize.width

    // Perspective wireframe grid rows
    val gridCount = 7
    val rowSpace = (totalHeight - horizonY) / gridCount
    
    // Horizontal wire grid lines
    for (i in 0..gridCount) {
        val y = horizonY + i * rowSpace + (offsetPx % rowSpace)
        if (y < totalHeight) {
            val ratio = (y - horizonY) / (totalHeight - horizonY)
            drawLine(
                color = Color(0xFFE040FB).copy(alpha = ratio.coerceIn(0f, 1f)),
                start = Offset(0f, y),
                end = Offset(totalWidth, y),
                strokeWidth = 3f
            )
        }
    }

    // Perspective perspective converge lines (reaches down from center origin)
    val numLines = 8
    val centerX = totalWidth / 2f
    for (i in -numLines..numLines) {
        val startX = centerX + i * (totalWidth / (numLines * 2.5f))
        val endX = centerX + i * (totalWidth / (numLines * 0.6f))
        drawLine(
            color = Color(0xFFE040FB).copy(alpha = 0.3f),
            start = Offset(startX, horizonY),
            end = Offset(endX, totalHeight),
            strokeWidth = 2f
        )
    }

    // Small twinkling stars in high sky space
    val r = java.util.Random(42) // Constant seed prevents flickering star repositionings
    for (i in 0..12) {
        val sx = r.nextFloat() * totalWidth
        val sy = r.nextFloat() * horizonY * 0.8f
        val size = 2f + r.nextFloat() * 4f
        // Cute cross star flare shape
        drawRect(color = Color(0xFF00E5FF).copy(alpha = 0.4f + r.nextFloat() * 0.6f), topLeft = Offset(sx - size, sy), size = Size(size * 2, 1.5f))
        drawRect(color = Color(0xFF00E5FF).copy(alpha = 0.4f + r.nextFloat() * 0.6f), topLeft = Offset(sx, sy - size), size = Size(1.5f, size * 2))
    }
}

// High performance fluffy cloud paths
fun DrawScope.drawStyledCloud(x: Float, y: Float, size: Float, alpha: Float) {
    val cloudColor = Color.White.copy(alpha = alpha)

    // Draw three overlapping rounded bubbles representing classic cartoon clouds
    drawCircle(
        color = cloudColor,
        center = Offset(x, y + size * 0.18f),
        radius = size * 0.35f
    )
    drawCircle(
        color = cloudColor,
        center = Offset(x + size * 0.38f, y),
        radius = size * 0.45f
    )
    drawCircle(
        color = cloudColor,
        center = Offset(x + size * 0.72f, y + size * 0.15f),
        radius = size * 0.3f
    )
}
