package com.example.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.HighScore
import com.example.db.HighScoreRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Random

class GameViewModel(private val repository: HighScoreRepository) : ViewModel() {

    // Gameplay CONSTANTS (Normalized coordinates: 1000 x 1000)
    val viewWidth = 1000f
    val viewHeight = 1000f
    val groundY = 860f
    val birdX = 250f
    val birdRadius = 22f
    val pipeWidth = 120f

    private val random = Random()

    // Configuration Settings (States observable by UI)
    private val _difficulty = MutableStateFlow(Difficulty.MEDIUM)
    val difficulty: StateFlow<Difficulty> = _difficulty.asStateFlow()

    private val _skin = MutableStateFlow(BirdSkin.YELLOW)
    val skin: StateFlow<BirdSkin> = _skin.asStateFlow()

    private val _theme = MutableStateFlow(GameTheme.DAY)
    val theme: StateFlow<GameTheme> = _theme.asStateFlow()

    private val _playerName = MutableStateFlow("Lucky Flapper")
    val playerName: StateFlow<String> = _playerName.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    // Game Runtime States
    private val _gameState = MutableStateFlow(GameState.START)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _birdY = MutableStateFlow(450f)
    val birdY: StateFlow<Float> = _birdY.asStateFlow()

    private val _birdVelocityY = MutableStateFlow(0f)
    val birdVelocityY: StateFlow<Float> = _birdVelocityY.asStateFlow()

    private val _birdAngle = MutableStateFlow(0f)
    val birdAngle: StateFlow<Float> = _birdAngle.asStateFlow()

    private val _score = MutableStateFlow(0)
    val score: StateFlow<Int> = _score.asStateFlow()

    private val _highScore = MutableStateFlow(0)
    val highScore: StateFlow<Int> = _highScore.asStateFlow()

    // Dynamic Lists for background & active entities
    private val _pipes = MutableStateFlow<List<Pipe>>(emptyList())
    val pipes: StateFlow<List<Pipe>> = _pipes.asStateFlow()

    private val _clouds = MutableStateFlow<List<Cloud>>(emptyList())
    val clouds: StateFlow<List<Cloud>> = _clouds.asStateFlow()

    private val _hills = MutableStateFlow<List<Hill>>(emptyList())
    val hills: StateFlow<List<Hill>> = _hills.asStateFlow()

    private val _particles = MutableStateFlow<List<Particle>>(emptyList())
    val particles: StateFlow<List<Particle>> = _particles.asStateFlow()

    private val _groundOffset = MutableStateFlow(0f)
    val groundOffset: StateFlow<Float> = _groundOffset.asStateFlow()

    // UI Feedback events (Triggers, observed as one-offs or state changes)
    private val _vibeTrigger = MutableStateFlow(0L)
    val vibeTrigger: StateFlow<Long> = _vibeTrigger.asStateFlow()

    // Observing Room leaderboards
    val leaderboard: StateFlow<List<HighScore>> = repository.topScores
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var gameLoopJob: Job? = null
    private var menuHoverJob: Job? = null
    private var pipeIdCounter = 0
    private var scoreRecordedInCurrentSession = false

    init {
        setupStaticElements()
        loadHighScore()
        startMenuHoverAnimation()
    }

    private fun setupStaticElements() {
        // Pre-generate clouds
        val initialClouds = mutableListOf<Cloud>()
        for (i in 0..4) {
            initialClouds.add(
                Cloud(
                    id = i,
                    x = i * 250f + random.nextFloat() * 80f,
                    y = 80f + random.nextFloat() * 150f,
                    speedScale = 0.3f + random.nextFloat() * 0.4f,
                    size = 80f + random.nextFloat() * 70f
                )
            )
        }
        _clouds.value = initialClouds

        // Pre-generate mountains
        val initialHills = mutableListOf<Hill>()
        for (i in 0..3) {
            initialHills.add(
                Hill(
                    id = i,
                    x = i * 350f,
                    height = 120f + random.nextFloat() * 140f,
                    width = 300f + random.nextFloat() * 150f,
                    speedScale = 0.6f + random.nextFloat() * 0.4f
                )
            )
        }
        _hills.value = initialHills
    }

    private fun startMenuHoverAnimation() {
        menuHoverJob?.cancel()
        menuHoverJob = viewModelScope.launch {
            var time = 0f
            while (_gameState.value == GameState.START) {
                // Gentle hover up and down like standard starting screens
                time += 0.08f
                _birdY.value = 450f + kotlin.math.sin(time) * 20f
                _birdAngle.value = kotlin.math.sin(time) * 12f
                
                // Drift elements slowly even in start menu for organic feel
                driftBackground(1.5f)
                delay(16)
            }
        }
    }

    fun setDifficulty(diff: Difficulty) {
        _difficulty.value = diff
        loadHighScore()
    }

    fun setSkin(skin: BirdSkin) {
        _skin.value = skin
    }

    fun setTheme(theme: GameTheme) {
        _theme.value = theme
    }

    fun setPlayerName(name: String) {
        _playerName.value = name
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _hapticsEnabled.value = enabled
    }

    private fun loadHighScore() {
        viewModelScope.launch {
            _highScore.value = repository.getMaxScore(_difficulty.value.label)
        }
    }

    fun startGame() {
        menuHoverJob?.cancel()
        gameLoopJob?.cancel()

        _score.value = 0
        _birdY.value = 450f
        _birdVelocityY.value = 0f
        _birdAngle.value = 0f
        _pipes.value = emptyList()
        _particles.value = emptyList()
        pipeIdCounter = 0
        scoreRecordedInCurrentSession = false
        _gameState.value = GameState.PLAYING

        // Spawn first pipe
        spawnPipe(startX = 1000f)

        gameLoopJob = viewModelScope.launch {
            while (_gameState.value == GameState.PLAYING) {
                updatePhysics()
                delay(16) // Target 60 FPS
            }
        }
    }

    fun flap() {
        if (_gameState.value != GameState.PLAYING) return

        // Jump physics
        val currentDiff = _difficulty.value
        _birdVelocityY.value = currentDiff.jumpHeight

        // Spawn cute feather particles on flap
        spawnFlapParticles()

        // Haptic feedback
        triggerVibe()
    }

    private fun updatePhysics() {
        val diff = _difficulty.value
        val speed = diff.speed
        val grav = diff.gravity

        // 1. Update Bird Y physics
        val currentVel = _birdVelocityY.value + grav
        _birdVelocityY.value = currentVel.coerceIn(-24f, 22f) // Terminal velocity limits
        
        val nextY = _birdY.value + _birdVelocityY.value
        // Block sky collision but cap at top, or fall if hitting ceiling
        if (nextY <= 0f) {
            _birdY.value = 0f
            _birdVelocityY.value = 0.5f // gentle bounce downwards
        } else {
            _birdY.value = nextY
        }

        // Compute rotation angle based on velocity scale
        // -30 degrees when jumping to 75 degrees nose-diving
        val targetRot = (_birdVelocityY.value * 4.2f).coerceIn(-30f, 75f)
        _birdAngle.value = _birdAngle.value * 0.7f + targetRot * 0.3f // smooth interpolation

        // 2. Drift Parallax backgrounds
        driftBackground(speed)

        // 3. Spawning & moving pipes
        val activePipes = _pipes.value.map { it.copy() }
        activePipes.forEach { it.x -= speed }

        // Filter out pipes that scrolled off the left edge (-180px safe margins)
        val visiblePipes = activePipes.filter { it.x > -150f }

        // Spawn replacement pipe when spacing distance reached
        if (visiblePipes.isEmpty() || (1050f - visiblePipes.last().x) >= 420f) {
            spawnPipe(startX = 1000f)
        } else {
            _pipes.value = visiblePipes
        }

        // 4. Scoring system
        var scored = false
        _pipes.value.forEach { pipe ->
            if (!pipe.passed && (birdX > pipe.x + pipeWidth)) {
                pipe.passed = true
                scored = true
            }
        }
        if (scored) {
            _score.value += 1
            if (_score.value > _highScore.value) {
                _highScore.value = _score.value
            }
            spawnScoreStars()
            triggerVibe()
        }

        // 5. Update floating particles
        updateParticles()

        // 6. Collision Checking
        checkCollisions()
    }

    private fun spawnPipe(startX: Float) {
        val currentDiff = _difficulty.value
        // randomized gap center between 220f and 620f
        val gapY = 220f + random.nextFloat() * 400f
        val newPipe = Pipe(
            id = pipeIdCounter++,
            x = startX,
            gapY = gapY,
            gapSize = currentDiff.gapSize
        )
        _pipes.value = _pipes.value + newPipe
    }

    private fun driftBackground(gameSpeed: Float) {
        // Ground tiles shift
        val newOffset = (_groundOffset.value + gameSpeed) % 40f
        _groundOffset.value = newOffset

        // Clouds shift slowly
        val driftedClouds = _clouds.value.map { cloud ->
            val nextX = cloud.x - (0.5f * cloud.speedScale * (gameSpeed / 6f))
            if (nextX < -200f) {
                cloud.copy(
                    x = 1000f,
                    y = 80f + random.nextFloat() * 150f,
                    speedScale = 0.3f + random.nextFloat() * 0.4f,
                    size = 80f + random.nextFloat() * 70f
                )
            } else {
                cloud.copy(x = nextX)
            }
        }
        _clouds.value = driftedClouds

        // Hills shift medium
        val driftedHills = _hills.value.map { hill ->
            val nextX = hill.x - (1.2f * hill.speedScale * (gameSpeed / 6f))
            if (nextX < -320f) {
                hill.copy(
                    x = 1000f,
                    height = 120f + random.nextFloat() * 140f,
                    width = 280f + random.nextFloat() * 140f,
                    speedScale = 0.6f + random.nextFloat() * 0.4f
                )
            } else {
                hill.copy(x = nextX)
            }
        }
        _hills.value = driftedHills
    }

    private fun checkCollisions() {
        val by = _birdY.value
        val r = birdRadius

        // Collision with ground is instant death
        if (by + r >= groundY) {
            triggerGameOver()
            return
        }

        // Collision with pipes
        for (pipe in _pipes.value) {
            val closestX = birdX.coerceIn(pipe.x, pipe.x + pipeWidth)

            // Top pipe intersection
            val closestYTop = by.coerceIn(0f, pipe.topPipeHeight)
            val dxTop = birdX - closestX
            val dyTop = by - closestYTop
            val hitTop = (dxTop * dxTop + dyTop * dyTop) < (r * r)

            if (hitTop) {
                triggerGameOver()
                return
            }

            // Bottom pipe intersection
            val closestYBottom = by.coerceIn(pipe.bottomPipeStartY, groundY)
            val dxBottom = birdX - closestX
            val dyBottom = by - closestYBottom
            val hitBottom = (dxBottom * dxBottom + dyBottom * dyBottom) < (r * r)

            if (hitBottom) {
                triggerGameOver()
                return
            }
        }
    }

    private fun triggerGameOver() {
        gameLoopJob?.cancel()
        _gameState.value = GameState.GAMEOVER
        
        // Spawn massive collision explosion debris
        spawnCollisionExplosion()
        triggerVibe()

        // Autocommit score if it's over 0 to keep database filled cleanly
        commitScoreToDatabase()

        // Slowly descend bird onto ground for dramatic effect
        viewModelScope.launch {
            var landY = _birdY.value
            var vel = 0f
            while (landY + r < groundY) {
                vel += 1.5f
                landY += vel
                if (landY + r >= groundY) {
                    landY = groundY - r
                    break
                }
                _birdY.value = landY
                _birdAngle.value = 85f // Face plant nose-dive
                delay(16)
            }
        }
    }

    fun saveManualScoreAndRestart() {
        // Safe trigger to save current score and go back to start
        commitScoreToDatabase()
        _gameState.value = GameState.START
        startMenuHoverAnimation()
    }

    fun returnToStartScreen() {
        _gameState.value = GameState.START
        startMenuHoverAnimation()
    }

    private fun commitScoreToDatabase() {
        if (scoreRecordedInCurrentSession) return
        val finalScore = _score.value
        if (finalScore <= 0) return // Don't record empty zeros

        val name = _playerName.value.trim().ifEmpty { "Flappy Guest" }
        val diff = _difficulty.value.label

        viewModelScope.launch {
            repository.insertScore(name, finalScore, diff)
            scoreRecordedInCurrentSession = true
            loadHighScore() // Update highest score
        }
    }

    fun clearLeaderboard() {
        viewModelScope.launch {
            repository.clearScores()
            _highScore.value = 0
        }
    }

    // Dynamic Visual Spark Particle Engines
    private fun spawnFlapParticles() {
        val currSkin = _skin.value
        val list = _particles.value.toMutableList()
        for (i in 0..4) {
            list.add(
                Particle(
                    x = birdX - 10f,
                    y = _birdY.value + (random.nextFloat() - 0.5f) * 15f,
                    vx = -4f - random.nextFloat() * 4f,
                    vy = (random.nextFloat() - 0.5f) * 3f,
                    color = currSkin.secondaryColor,
                    size = 5f + random.nextFloat() * 6f,
                    alpha = 1f,
                    decay = 0.03f + random.nextFloat() * 0.02f
                )
            )
        }
        _particles.value = list
    }

    private fun spawnScoreStars() {
        val list = _particles.value.toMutableList()
        // Spawn gold bursting particles around the bird
        for (i in 0..14) {
            list.add(
                Particle(
                    x = birdX,
                    y = _birdY.value,
                    vx = (random.nextFloat() - 0.5f) * 14f,
                    vy = (random.nextFloat() - 0.5f) * 14f,
                    color = 0xFFFFD700, // Gold Sparkle
                    size = 6f + random.nextFloat() * 8f,
                    alpha = 1.0f,
                    decay = 0.02f + random.nextFloat() * 0.03f
                )
            )
        }
        _particles.value = list
    }

    private fun spawnCollisionExplosion() {
        val list = _particles.value.toMutableList()
        val skinColor = _skin.value.primaryColor
        // Massive fiery feather splash around impact focus
        for (i in 0..24) {
            list.add(
                Particle(
                    x = birdX,
                    y = _birdY.value,
                    vx = (random.nextFloat() - 0.5f) * 18f,
                    vy = (random.nextFloat() - 0.6f) * 18f,
                    color = if (random.nextBoolean()) skinColor else 0xFFFF5722, // Skin color or fiery orange
                    size = 8f + random.nextFloat() * 10f,
                    alpha = 1.0f,
                    decay = 0.015f + random.nextFloat() * 0.02f
                )
            )
        }
        _particles.value = list
    }

    private fun updateParticles() {
        val list = _particles.value.map { p ->
            p.copy(
                x = p.x + p.vx,
                y = p.y + p.vy + 0.15f, // gentle gravity on particles
                alpha = p.alpha - p.decay
            )
        }.filter { p -> p.alpha > 0f }
        _particles.value = list
    }

    private fun triggerVibe() {
        if (_hapticsEnabled.value) {
            _vibeTrigger.value = System.currentTimeMillis()
        }
    }

    private val r: Float get() = birdRadius
}

// Custom simple provider constructor for MainActivity
class GameViewModelFactory(private val repository: HighScoreRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
