package com.example.game

enum class GameState {
    START, PLAYING, GAMEOVER
}

enum class Difficulty(val label: String, val gapSize: Float, val speed: Float, val gravity: Float, val jumpHeight: Float) {
    EASY("Easy", 300f, 4.5f, 0.9f, -14f),
    MEDIUM("Medium", 240f, 6.0f, 1.1f, -16f),
    HARD("Hard", 195f, 7.5f, 1.3f, -17.5f)
}

enum class BirdSkin(val label: String, val primaryColor: Long, val secondaryColor: Long, val beakColor: Long, val eyeColor: Long) {
    YELLOW("Goldy", 0xFFF7D308, 0xFFF7B400, 0xFFF75210, 0xFFFFFFFF),
    BLUE("Cobalt", 0xFF4FC3F7, 0xFF0288D1, 0xFFFF7043, 0xFFFFFFFF),
    RED("Phoenix", 0xFFEF5350, 0xFFC62828, 0xFFFFCA28, 0xFFFFFFFF),
    PINK("Sakura", 0xFFF06292, 0xFFD81B60, 0xFFFFD54F, 0xFFFFFFFF)
}

enum class GameTheme(
    val label: String,
    val skyColorTop: Long,
    val skyColorBottom: Long,
    val groundColor: Long,
    val grassColor: Long,
    val pipeColorTop: Long,
    val pipeColorBottom: Long,
    val mountainColorTop: Long,
    val mountainColorBottom: Long
) {
    DAY(
        "Daytime",
        0xFF70C5CE, 0xFF70C5CE, // Immersive sky (#70c5ce)
        0xFFDED895, 0xFF73BF2F, // Immersive ground (#ded895) / grass (#73bf2f)
        0xFF73BF2F, 0xFF52A11B, // Pipe filled green gradient
        0xFFB5E2E6, 0xFF8DCACF  // Atmosphere hills
    ),
    SUNSET(
        "Twilight",
        0xFF311B92, 0xFFFF7043, // purple to orange twilight
        0xFF5D4037, 0xFFFF8A65, // ground / grass
        0xFF9575CD, 0xFF4527A0, // pipe violet purple
        0xFF3E2723, 0xFF271510  // silhouette hills
    ),
    NEON(
        "Neon Synth",
        0xFF0D021A, 0xFF31003E, // dark cyberpunk sky
        0xFF1A1A1A, 0xFFE040FB, // ground / neon lines
        0xFF00E5FF, 0xFF006064, // pipe glowing cyan
        0xFF210024, 0xFF0F0012  // retro neon wireframe grids
    )
}

data class Pipe(
    val id: Int,
    var x: Float,
    val gapY: Float,
    val gapSize: Float,
    var passed: Boolean = false
) {
    val topPipeHeight: Float get() = gapY - gapSize / 2f
    val bottomPipeStartY: Float get() = gapY + gapSize / 2f
}

data class Cloud(
    val id: Int,
    var x: Float,
    val y: Float,
    val speedScale: Float,
    val size: Float
)

data class Hill(
    val id: Int,
    var x: Float,
    val height: Float,
    val width: Float,
    val speedScale: Float
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Long,
    val size: Float,
    var alpha: Float,
    val decay: Float
)
