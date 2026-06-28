import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.sin

// --- AUDIO MANAGER ---
object AudioPlayer {
    private val clips = mutableMapOf<String, Clip>()
    fun load(name: String, path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                val stream = AudioSystem.getAudioInputStream(file)
                val clip = AudioSystem.getClip()
                clip.open(stream)
                clips[name] = clip
            }
        } catch (e: Exception) { println("Error loading audio $name: ${e.message}") }
    }
    fun play(name: String) { clips[name]?.apply { framePosition = 0; start() } }
    fun loop(name: String) { clips[name]?.apply { framePosition = 0; loop(Clip.LOOP_CONTINUOUSLY) } }
    fun stop(name: String) { clips[name]?.stop() }
    fun stopAll() { clips.values.forEach { it.stop() } }
}

// --- CORE DATA ---
data class Point(var x: Int, var y: Int)
enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class GameState { MENU, PLAYING, GAME_OVER, ABOUT, ABOUT_MICHAEL, DONATE }
enum class Level { NO_WALLS, WALLS }

enum class PreyType(val points: Int, val baseSize: Int, val color: Color, val isPoison: Boolean = false) {
    SMALL(10, 14, Color(255, 60, 100)),
    BIG(30, 18, Color(255, 180, 50)),
    GOLD(50, 16, Color(255, 255, 100)),
    POISON(-10, 14, Color(180, 20, 255), true),
    SOUL_ORB(0, 16, Color(0, 255, 255)),
    FEAR_ORB(0, 14, Color(180, 0, 20))
}

class Player {
    var score: Int = 0; private set
    var hearts: Int = 3; private set
    fun deductHeart() { hearts -= 1 }
    fun addScore(points: Int) { score = maxOf(0, score + points) }
    fun reset() { score = 0; hearts = 3 }
}

class FallingHeart(var x: Double, var y: Double) {
    var velocityY = -3.0; var velocityX = (Math.random() * 3 - 1.5); var alpha = 1.0f
    fun update() { x += velocityX; y += velocityY; velocityY += 0.3; alpha = maxOf(0.0f, alpha - 0.02f) }
}

class FloatingText(var x: Double, var y: Double, val text: String, val color: Color) {
    var velocityY = -1.0; var alpha = 1.0f
    fun update() { y += velocityY; alpha = maxOf(0.0f, alpha - 0.015f) }
}

open class Snake(val startX: Int, val startY: Int, val colorTheme: Color) {
    val body = mutableListOf<Point>()
    val prevBody = mutableListOf<Point>()
    var direction = Direction.RIGHT
    private val inputQueue = mutableListOf<Direction>()
    var growPending = 0; var sickTicks = 0

    init { reset() }

    fun queueDirection(dir: Direction) {
        val lastDir = inputQueue.lastOrNull() ?: direction
        val isOpposite = when (dir) {
            Direction.UP -> lastDir == Direction.DOWN
            Direction.DOWN -> lastDir == Direction.UP
            Direction.LEFT -> lastDir == Direction.RIGHT
            Direction.RIGHT -> lastDir == Direction.LEFT
        }
        if (!isOpposite && inputQueue.size < 2) inputQueue.add(dir)
    }

    open fun move(gridWidth: Int, gridHeight: Int, level: Level) {
        if (body.isEmpty()) return
        if (inputQueue.isNotEmpty()) direction = inputQueue.removeFirst()

        prevBody.clear(); body.forEach { prevBody.add(Point(it.x, it.y)) }

        val head = body.first()
        var newX = head.x; var newY = head.y

        when (direction) {
            Direction.UP -> newY--; Direction.DOWN -> newY++
            Direction.LEFT -> newX--; Direction.RIGHT -> newX++
        }

        if (level == Level.NO_WALLS) {
            if (newX < 0) newX = gridWidth - 1; if (newX >= gridWidth) newX = 0
            if (newY < 0) newY = gridHeight - 1; if (newY >= gridHeight) newY = 0
        }

        body.add(0, Point(newX, newY))
        if (growPending > 0) growPending-- else body.removeLast()
    }

    fun reset() {
        body.clear(); prevBody.clear(); inputQueue.clear()
        val startPoints = listOf(Point(startX, startY), Point(startX - 1, startY), Point(startX - 2, startY))
        body.addAll(startPoints); prevBody.addAll(startPoints)
        direction = Direction.RIGHT; growPending = 0; sickTicks = 0
    }
}

class BossSnake(startX: Int, startY: Int) : Snake(startX, startY, Color(255, 30, 50)) {
    fun calculateBestMove(targets: List<Prey>, gridWidth: Int, gridHeight: Int, level: Level) {
        if (body.isEmpty()) return
        val head = body.first()
        val target = targets.filter { it.type == PreyType.SOUL_ORB || it.type == PreyType.SMALL || it.type == PreyType.FEAR_ORB }
            .minByOrNull { abs(it.x - head.x) + abs(it.y - head.y) } ?: return

        val possibleMoves = Direction.values().mapNotNull { dir ->
            var nx = head.x; var ny = head.y
            when (dir) {
                Direction.UP -> ny--; Direction.DOWN -> ny++
                Direction.LEFT -> nx--; Direction.RIGHT -> nx++
            }
            val hitWall = level == Level.WALLS && (nx < 2 || ny < 2 || nx >= gridWidth - 2 || ny >= gridHeight - 2)
            if (hitWall) null else dir to Point(nx, ny)
        }

        val bestDir = possibleMoves.minByOrNull { (_, pt) -> abs(pt.x - target.x) + abs(pt.y - target.y) }?.first
        if (bestDir != null) direction = bestDir
    }
}

class Prey {
    var x: Int = 0; var y: Int = 0
    lateinit var type: PreyType
    var visualOffset = Math.random() * 100

    fun respawn(width: Int, height: Int, snakeBody: List<Point>, level: Level, forceType: PreyType? = null) {
        val minBounds = if (level == Level.WALLS) 2 else 0
        val maxW = if (level == Level.WALLS) width - 2 else width
        val maxH = if (level == Level.WALLS) height - 2 else height
        do {
            x = (minBounds until maxW).random(); y = (minBounds until maxH).random()
        } while (snakeBody.any { it.x == x && it.y == y })

        type = forceType ?: when {
            Math.random() > 0.90 -> PreyType.GOLD
            Math.random() > 0.60 -> PreyType.BIG
            else -> PreyType.SMALL
        }
    }
}

// --- MAIN ENGINE ---
class SnakeGamePanel : JPanel() {
    private val gridWidth = 40; private val gridHeight = 20
    private val cellSize = 22; private val uiHeight = 50

    private var state = GameState.MENU
    private var currentLevel = Level.NO_WALLS

    private val player = Player()
    private val snake = Snake(20, 10, Color(10, 220, 100))
    private val activePreys = mutableListOf<Prey>()
    private val heartAnimations = mutableListOf<FallingHeart>()
    private val floatingTexts = mutableListOf<FloatingText>()

    private var isBossPhase = false; private var isBossDevourSequence = false; private var isPlayerDevourSequence = false; private var isBossEntrance = false
    private var bossScoreThreshold = 150
    private var bossSpawnTicks = 0; private var standoffTicks = 0; private var invincibleTicks = 0; private var crashStunTicks = 0
    private var crashCause = ""

    private var bossSnake: BossSnake? = null
    private var playerBossScore = 0; private var bossBossScore = 0
    private val orbsToWin = 5

    private var isPaused = false
    private var currentDelay = 120L; private var bossDelay = 220L

    private var lastLogicTick = System.currentTimeMillis(); private var lastBossLogicTick = System.currentTimeMillis()
    private var tickCount = 0; private var screenShake = 0.0

    // Menu Interactions
    private var menuSelection = 1
    private var isHoveringAbout = false
    private var isHoveringDevBtn = false
    private var isHoveringCoffee = false
    private var hoverDonateIdx = -1 // 0=$5, 1=$20, 2=$50

    init {
        preferredSize = Dimension(gridWidth * cellSize, (gridHeight * cellSize) + uiHeight)
        background = Color(15, 16, 22)
        isFocusable = true

        AudioPlayer.load("eat", "sounds/eat.wav")
        AudioPlayer.load("poison", "sounds/poison.wav")
        AudioPlayer.load("warning", "sounds/warning.wav")
        AudioPlayer.load("damage", "sounds/damage.wav")
        AudioPlayer.load("gameover", "sounds/gameover.wav")
        AudioPlayer.load("bgm", "sounds/bgm.wav")

        val mouseAdapter = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val w = width; val h = height
                isHoveringAbout = false; isHoveringDevBtn = false; isHoveringCoffee = false; hoverDonateIdx = -1

                when (state) {
                    GameState.MENU -> {
                        val c1 = Rectangle(w / 2 - 280, 160, 250, 220)
                        val c2 = Rectangle(w / 2 + 30, 160, 250, 220)
                        val aboutBtn = Rectangle(w - 110, h - 50, 90, 35)
                        if (c1.contains(e.point)) menuSelection = 1
                        else if (c2.contains(e.point)) menuSelection = 2
                        isHoveringAbout = aboutBtn.contains(e.point)
                    }
                    GameState.ABOUT -> {
                        val devBtn = Rectangle(w / 2 - 125, h - 130, 250, 45)
                        isHoveringDevBtn = devBtn.contains(e.point)
                    }
                    GameState.ABOUT_MICHAEL -> {
                        val coffeeBtn = Rectangle(w / 2 - 140, h / 2 + 20, 280, 55)
                        isHoveringCoffee = coffeeBtn.contains(e.point)
                    }
                    GameState.DONATE -> {
                        val b5 = Rectangle(w / 2 - 210, h / 2 - 20, 120, 140)
                        val b20 = Rectangle(w / 2 - 60, h / 2 - 20, 120, 140)
                        val b50 = Rectangle(w / 2 + 90, h / 2 - 20, 120, 140)
                        if (b5.contains(e.point)) hoverDonateIdx = 0
                        else if (b20.contains(e.point)) hoverDonateIdx = 1
                        else if (b50.contains(e.point)) hoverDonateIdx = 2
                    }
                    else -> {}
                }
            }

            override fun mousePressed(e: MouseEvent) {
                val w = width; val h = height
                when (state) {
                    GameState.MENU -> {
                        val c1 = Rectangle(w / 2 - 280, 160, 250, 220)
                        val c2 = Rectangle(w / 2 + 30, 160, 250, 220)
                        val aboutBtn = Rectangle(w - 110, h - 50, 90, 35)
                        if (c1.contains(e.point)) startGame(Level.NO_WALLS)
                        else if (c2.contains(e.point)) startGame(Level.WALLS)
                        else if (aboutBtn.contains(e.point)) state = GameState.ABOUT
                    }
                    GameState.ABOUT -> {
                        val devBtn = Rectangle(w / 2 - 125, h - 130, 250, 45)
                        if (devBtn.contains(e.point)) state = GameState.ABOUT_MICHAEL
                        else state = GameState.MENU
                    }
                    GameState.ABOUT_MICHAEL -> {
                        val coffeeBtn = Rectangle(w / 2 - 140, h / 2 + 20, 280, 55)
                        if (coffeeBtn.contains(e.point)) state = GameState.DONATE
                        else state = GameState.MENU
                    }
                    GameState.DONATE -> {
                        val b5 = Rectangle(w / 2 - 210, h / 2 - 20, 120, 140)
                        val b20 = Rectangle(w / 2 - 60, h / 2 - 20, 120, 140)
                        val b50 = Rectangle(w / 2 + 90, h / 2 - 20, 120, 140)
                        if (b5.contains(e.point) || b20.contains(e.point) || b50.contains(e.point)) {
                            // In a real app, this would open a browser link
                            println("Thank you for your support!")
                            state = GameState.MENU
                        } else {
                            state = GameState.ABOUT_MICHAEL
                        }
                    }
                    else -> {}
                }
            }
        }
        addMouseListener(mouseAdapter); addMouseMotionListener(mouseAdapter)

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (state) {
                    GameState.MENU -> {
                        when (e.keyCode) {
                            KeyEvent.VK_LEFT, KeyEvent.VK_A -> menuSelection = 1
                            KeyEvent.VK_RIGHT, KeyEvent.VK_D -> menuSelection = 2
                            KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> startGame(if (menuSelection == 1) Level.NO_WALLS else Level.WALLS)
                            KeyEvent.VK_1 -> startGame(Level.NO_WALLS)
                            KeyEvent.VK_2 -> startGame(Level.WALLS)
                            KeyEvent.VK_I -> state = GameState.ABOUT
                        }
                    }
                    GameState.ABOUT, GameState.ABOUT_MICHAEL, GameState.DONATE -> {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) state = GameState.MENU
                    }
                    GameState.GAME_OVER -> if (e.keyCode == KeyEvent.VK_R) state = GameState.MENU
                    GameState.PLAYING -> {
                        when (e.keyCode) {
                            KeyEvent.VK_SPACE, KeyEvent.VK_P -> if (bossSpawnTicks == 0 && !isBossEntrance && crashStunTicks == 0) isPaused = !isPaused
                            else -> if (!isPaused && !isBossDevourSequence && bossSpawnTicks == 0 && !isBossEntrance && crashStunTicks == 0) {
                                when (e.keyCode) {
                                    KeyEvent.VK_UP, KeyEvent.VK_W -> snake.queueDirection(Direction.UP)
                                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> snake.queueDirection(Direction.DOWN)
                                    KeyEvent.VK_LEFT, KeyEvent.VK_A -> snake.queueDirection(Direction.LEFT)
                                    KeyEvent.VK_RIGHT, KeyEvent.VK_D -> snake.queueDirection(Direction.RIGHT)
                                }
                            }
                        }
                    }
                }
            }
        })

        Timer(16) {
            val now = System.currentTimeMillis(); tickCount++
            if (state != GameState.PLAYING && state != GameState.GAME_OVER) repaint()
            else if (state == GameState.PLAYING && !isPaused) {
                if (crashStunTicks > 0) {
                    crashStunTicks--; if (crashStunTicks % 5 == 0) addShake(2.0); if (crashStunTicks == 0) resolveCrashSequence()
                } else if (bossSpawnTicks > 0) {
                    bossSpawnTicks--; if (bossSpawnTicks % 10 == 0) addShake(3.0); if (bossSpawnTicks == 0) startBossEntrance()
                } else if (isBossEntrance) {
                    if (now - lastBossLogicTick >= 20L) { handleBossEntrance(); lastBossLogicTick = now }
                } else {
                    var playerMoved = false; var bossMoved = false
                    if (now - lastLogicTick >= currentDelay) {
                        if (isBossDevourSequence && bossSnake != null) handleBossDevourSequence()
                        else snake.move(gridWidth, gridHeight, currentLevel)
                        playerMoved = true; lastLogicTick = now
                    }
                    if (isBossPhase && !isPlayerDevourSequence && !isBossDevourSequence && bossSnake != null) {
                        if (now - lastBossLogicTick >= bossDelay) {
                            bossSnake!!.calculateBestMove(activePreys, gridWidth, gridHeight, currentLevel)
                            bossSnake!!.move(gridWidth, gridHeight, currentLevel)
                            bossMoved = true; lastBossLogicTick = now
                        }
                    }
                    if (playerMoved || bossMoved) checkLogic()
                }

                if (snake.sickTicks > 0) snake.sickTicks--
                if (bossSnake?.sickTicks ?: 0 > 0) bossSnake!!.sickTicks--
                if (invincibleTicks > 0) invincibleTicks--
                if (screenShake > 0.1) screenShake *= 0.85 else screenShake = 0.0

                heartAnimations.removeAll { it.alpha <= 0f }; heartAnimations.forEach { it.update() }
                floatingTexts.removeAll { it.alpha <= 0f }; floatingTexts.forEach { it.update() }
                repaint()
            }
        }.start()
    }

    private fun addShake(intensity: Double) { screenShake = minOf(15.0, screenShake + intensity) }

    private fun spawnFloatingText(x: Int, y: Int, text: String, color: Color) {
        floatingTexts.add(FloatingText((x * cellSize).toDouble(), (y * cellSize).toDouble() - 10.0, text, color))
    }

    private fun triggerCinematicEntrance() {
        bossSpawnTicks = 180; activePreys.clear(); addShake(6.0)
        AudioPlayer.stop("bgm"); AudioPlayer.play("warning")
    }

    private fun startBossEntrance() {
        isBossEntrance = true; activePreys.clear()
        val spawnX = if (snake.body.first().x < gridWidth / 2) gridWidth - 2 else 2
        bossSnake = BossSnake(spawnX, 10).apply { growPending = 5 }

        val head = snake.body.first(); val radius = 4
        val ringOffsets = listOf(Point(-radius, -radius), Point(0, -radius), Point(radius, -radius), Point(-radius, 0), Point(radius, 0), Point(-radius, radius), Point(0, radius), Point(radius, radius))
        for (offset in ringOffsets) {
            val px = (head.x + offset.x).coerceIn(2, gridWidth - 3); val py = (head.y + offset.y).coerceIn(2, gridHeight - 3)
            activePreys.add(Prey().apply { x = px; y = py; type = PreyType.FEAR_ORB })
        }
        addShake(15.0)
    }

    private fun handleBossEntrance() {
        if (activePreys.isNotEmpty()) {
            bossSnake!!.calculateBestMove(activePreys, gridWidth, gridHeight, currentLevel)
            bossSnake!!.move(gridWidth, gridHeight, currentLevel)
            val ate = activePreys.removeAll { it.x == bossSnake!!.body.first().x && it.y == bossSnake!!.body.first().y }
            if (ate) { AudioPlayer.play("eat"); addShake(5.0); bossSnake!!.growPending++ }
            if (activePreys.isEmpty()) standoffTicks = 40
        } else {
            if (standoffTicks > 0) {
                standoffTicks--; if (standoffTicks == 0) { isBossEntrance = false; triggerBossPhase() }
            }
        }
    }

    private fun triggerBossPhase() {
        isBossPhase = true; playerBossScore = 0; bossBossScore = 0; invincibleTicks = 180; activePreys.clear(); addShake(8.0)
        AudioPlayer.loop("bgm")
        repeat(5) { activePreys.add(Prey().apply { respawn(gridWidth, gridHeight, snake.body, currentLevel, PreyType.SOUL_ORB) }) }
    }

    private fun triggerBossDevourSequence() {
        isBossDevourSequence = true; activePreys.clear(); currentDelay = 40L; snake.sickTicks = 999; addShake(10.0)
    }

    private fun handleBossDevourSequence() {
        addShake(2.0)
        val target = snake.body.lastOrNull()
        if (target != null) {
            val dummyPrey = Prey().apply { x = target.x; y = target.y; type = PreyType.SMALL }
            bossSnake!!.calculateBestMove(listOf(dummyPrey), gridWidth, gridHeight, currentLevel)
            bossSnake!!.move(gridWidth, gridHeight, currentLevel)
            if (bossSnake!!.body.first().x == target.x && bossSnake!!.body.first().y == target.y) {
                AudioPlayer.play("eat"); snake.body.removeLast(); bossSnake!!.growPending++; addShake(4.0)
            }
        }
        if (snake.body.isEmpty()) {
            isBossDevourSequence = false; AudioPlayer.stopAll(); AudioPlayer.play("gameover"); state = GameState.GAME_OVER
        }
    }

    private fun triggerPlayerDevourSequence() {
        isPlayerDevourSequence = true; activePreys.clear(); bossSnake!!.sickTicks = 9999; addShake(8.0)
    }

    private fun endBossPhase(playerWon: Boolean) {
        isBossPhase = false; isPlayerDevourSequence = false; bossSnake = null
        if (playerWon) {
            player.addScore(300); addShake(5.0); spawnFloatingText(snake.body.first().x, snake.body.first().y, "+300", Color.YELLOW)
        } else {
            spawnHeartAnimation(); player.deductHeart(); addShake(12.0); AudioPlayer.play("damage")
        }
        bossScoreThreshold = player.score + 300; snake.reset(); currentDelay = 120L; lastLogicTick = System.currentTimeMillis(); spawnNormalPrey()
    }

    private fun triggerCrashSequence(cause: String) {
        crashStunTicks = 60; crashCause = cause; addShake(15.0); AudioPlayer.play("damage")
    }

    private fun resolveCrashSequence() {
        if (crashCause == "BOSS_HIT" || crashCause == "BOSS_RACE" || (crashCause == "WALL" && isBossPhase)) endBossPhase(false)
        else {
            spawnHeartAnimation(); player.deductHeart(); addShake(12.0)
            if (player.hearts <= 0) { AudioPlayer.stopAll(); AudioPlayer.play("gameover"); state = GameState.GAME_OVER }
            else { snake.reset(); currentDelay = 120L; lastLogicTick = System.currentTimeMillis() }
        }
    }

    private fun checkLogic() {
        if (snake.body.isEmpty() || bossSpawnTicks > 0) return
        val head = snake.body.first(); var hitWallOrSelf = false; var hitBossPhysical = false; var bossWonRace = false

        if (!isBossPhase && !isBossEntrance && player.score >= bossScoreThreshold) { triggerCinematicEntrance(); return }

        if (currentLevel == Level.WALLS && (head.x < 2 || head.y < 2 || head.x >= gridWidth - 2 || head.y >= gridHeight - 2)) hitWallOrSelf = true
        for (i in 1 until snake.body.size) { if (head.x == snake.body[i].x && head.y == snake.body[i].y) hitWallOrSelf = true }

        if (invincibleTicks == 0 && isBossPhase && !isPlayerDevourSequence && bossSnake?.body?.any { it.x == head.x && it.y == head.y } == true) hitBossPhysical = true

        if (isPlayerDevourSequence) {
            val ateBoss = bossSnake?.body?.removeAll { it.x == head.x && it.y == head.y } == true
            if (ateBoss) {
                AudioPlayer.play("eat"); player.addScore(15); snake.growPending++; addShake(1.5)
                spawnFloatingText(head.x, head.y, "+15", Color.CYAN)
            }
            if (bossSnake?.body?.isEmpty() == true) { endBossPhase(true); return }
        }

        val preysToRemove = mutableListOf<Prey>(); val preysToAdd = mutableListOf<Prey>(); var normalFoodEaten = false

        for (prey in activePreys) {
            if (head.x == prey.x && head.y == prey.y) {
                if (prey.type == PreyType.SOUL_ORB) {
                    AudioPlayer.play("eat"); playerBossScore++; snake.growPending++; addShake(2.0)
                    preysToAdd.add(Prey().apply { respawn(gridWidth, gridHeight, snake.body, currentLevel, PreyType.SOUL_ORB) })
                } else {
                    player.addScore(prey.type.points)
                    if (prey.type.isPoison) {
                        AudioPlayer.play("poison"); snake.sickTicks = 90; spawnHeartAnimation(); player.deductHeart(); addShake(10.0)
                        spawnFloatingText(prey.x, prey.y, "-10", prey.type.color)
                        if (player.hearts <= 0) { AudioPlayer.stopAll(); AudioPlayer.play("gameover"); state = GameState.GAME_OVER }
                    } else {
                        AudioPlayer.play("eat"); snake.growPending += if (prey.type == PreyType.BIG) 2 else 1
                        normalFoodEaten = true; addShake(1.5); spawnFloatingText(prey.x, prey.y, "+${prey.type.points}", prey.type.color)
                    }
                }
                preysToRemove.add(prey)
            }
            else if (isBossPhase && !isPlayerDevourSequence && bossSnake?.body?.firstOrNull()?.let { it.x == prey.x && it.y == prey.y } == true) {
                if (prey.type == PreyType.SOUL_ORB) {
                    AudioPlayer.play("eat"); bossBossScore++; bossSnake?.growPending?.plus(1); addShake(0.5)
                    preysToAdd.add(Prey().apply { respawn(gridWidth, gridHeight, snake.body, currentLevel, PreyType.SOUL_ORB) })
                    preysToRemove.add(prey)
                }
            }
        }

        activePreys.removeAll(preysToRemove); activePreys.addAll(preysToAdd)

        if (isBossPhase && !isPlayerDevourSequence) {
            if (playerBossScore >= orbsToWin) triggerPlayerDevourSequence()
            else if (bossBossScore >= orbsToWin) bossWonRace = true
        } else if (normalFoodEaten && !isBossPhase) {
            currentDelay = maxOf(60L, (120L - (player.score * 1.0)).toLong()); spawnNormalPrey()
        } else if (activePreys.isEmpty() && !isBossPhase) spawnNormalPrey()

        if (hitWallOrSelf) {
            if (isBossPhase) { if (player.hearts - 1 <= 0) { player.deductHeart(); triggerBossDevourSequence() } else triggerCrashSequence("WALL") }
            else triggerCrashSequence("WALL")
        } else if (hitBossPhysical) {
            if (player.hearts - 1 <= 0) { player.deductHeart(); triggerBossDevourSequence() } else triggerCrashSequence("BOSS_HIT")
        } else if (bossWonRace) {
            if (player.hearts - 1 <= 0) { player.deductHeart(); triggerBossDevourSequence() } else triggerCrashSequence("BOSS_RACE")
        }
    }

    private fun lerp(s: Int, e: Int, f: Double): Double = if (abs(e - s) > 1) e.toDouble() else s + (e - s) * f

    private fun spawnNormalPrey() {
        activePreys.clear(); activePreys.add(Prey().apply { respawn(gridWidth, gridHeight, snake.body, currentLevel) })
        if (Math.random() > 0.70) activePreys.add(Prey().apply { respawn(gridWidth, gridHeight, snake.body, currentLevel, PreyType.POISON) })
    }

    private fun spawnHeartAnimation() {
        heartAnimations.add(FallingHeart((gridWidth * cellSize - 150 + maxOf(0, player.hearts - 1) * 22).toDouble(), 26.0))
    }

    private fun startGame(level: Level) {
        currentLevel = level; player.reset(); snake.reset()
        isBossPhase = false; isBossDevourSequence = false; isPlayerDevourSequence = false; isBossEntrance = false
        bossSnake = null; screenShake = 0.0; bossSpawnTicks = 0; standoffTicks = 0; invincibleTicks = 0; crashStunTicks = 0
        bossScoreThreshold = 150; heartAnimations.clear(); floatingTexts.clear()

        AudioPlayer.stopAll(); AudioPlayer.loop("bgm"); spawnNormalPrey()
        currentDelay = 120L; isPaused = false; state = GameState.PLAYING; lastLogicTick = System.currentTimeMillis()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        when (state) {
            GameState.MENU -> { drawMenu(g2d); return }
            GameState.ABOUT -> { drawAbout(g2d); return }
            GameState.ABOUT_MICHAEL -> { drawAboutMichael(g2d); return }
            GameState.DONATE -> { drawDonate(g2d); return }
            else -> {} // Continue to game rendering
        }

        // --- DRAW UI LAYER ---
        g2d.color = Color(10, 11, 16); g2d.fillRect(0, 0, width, uiHeight)
        g2d.font = Font("SansSerif", Font.BOLD, 18); g2d.color = Color.WHITE; g2d.drawString("SCORE: ${player.score}", 20, 32)
        g2d.color = Color(255, 60, 100); val heartsStr = "❤ ".repeat(maxOf(0, player.hearts)); g2d.drawString("LIVES: $heartsStr", width - 150, 32)

        val originalComposite = g2d.composite
        heartAnimations.forEach { anim ->
            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, anim.alpha)
            g2d.drawString("❤", anim.x.toFloat(), anim.y.toFloat() + 6)
        }
        g2d.composite = originalComposite

        if (bossSpawnTicks > 0 || isBossEntrance) {
            g2d.font = Font("SansSerif", Font.BOLD, 22)
            g2d.color = if (tickCount % 10 < 5) Color.RED else Color.YELLOW
            g2d.drawString("WARNING: ANOMALY DETECTED", (width - g2d.fontMetrics.stringWidth("WARNING: ANOMALY DETECTED")) / 2, 34)
        } else if (isBossPhase) {
            g2d.font = Font("SansSerif", Font.BOLD, 22)
            g2d.color = Color(0, 255, 255)
            val vsStr = "ORBS: You [ $playerBossScore / $orbsToWin ] Boss [ $bossBossScore / $orbsToWin ]"
            g2d.drawString(vsStr, (width - g2d.fontMetrics.stringWidth(vsStr)) / 2, 34)
        }

        // --- SHIFT CAMERA FOR GAME GRID ---
        val sx = if (screenShake > 0) (Math.random() * screenShake - screenShake/2) else 0.0
        val sy = if (screenShake > 0) (Math.random() * screenShake - screenShake/2) else 0.0
        g2d.translate(sx, uiHeight + sy)

        if (crashStunTicks > 0) {
            g2d.color = Color(150, 0, 0, 120); g2d.fillRect(0, 0, width, gridHeight * cellSize)
        } else if (bossSpawnTicks > 0) {
            g2d.color = Color(minOf(200, (180 - bossSpawnTicks)), 10, 15); g2d.fillRect(0, 0, width, gridHeight * cellSize)
        } else if (isBossDevourSequence || isBossEntrance) {
            g2d.color = if (tickCount % 10 < 5) Color(40, 5, 10) else Color(25, 5, 8); g2d.fillRect(0, 0, width, gridHeight * cellSize)
        } else if (isPlayerDevourSequence) {
            g2d.color = Color(5, 25, 25); g2d.fillRect(0, 0, width, gridHeight * cellSize)
        } else if (isBossPhase) {
            g2d.color = Color(25, 10, 15); g2d.fillRect(0, 0, width, gridHeight * cellSize)
        }

        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                if (!isBossPhase && !isBossDevourSequence && !isPlayerDevourSequence && bossSpawnTicks == 0 && !isBossEntrance && crashStunTicks == 0) g2d.color = if ((x + y) % 2 == 0) Color(22, 24, 30) else Color(18, 20, 26)
                else if (isPlayerDevourSequence) g2d.color = if ((x + y) % 2 == 0) Color(10, 35, 35) else Color(8, 28, 28)
                else g2d.color = if ((x + y) % 2 == 0) Color(35, 15, 20) else Color(28, 12, 16)

                if (currentLevel == Level.WALLS && (y < 2 || y >= gridHeight - 2 || x < 2 || x >= gridWidth - 2)) {
                    g2d.color = if (crashStunTicks > 0) Color(100, 20, 30) else if (isBossDevourSequence || isBossPhase || bossSpawnTicks > 0 || isBossEntrance) Color(60, 20, 30) else if (isPlayerDevourSequence) Color(20, 60, 60) else Color(35, 40, 50)
                    g2d.fillRect(x * cellSize, y * cellSize, cellSize, cellSize)
                    g2d.color = Color(20, 25, 30); g2d.drawRect(x * cellSize, y * cellSize, cellSize, cellSize)
                } else g2d.fillRect(x * cellSize, y * cellSize, cellSize, cellSize)
            }
        }

        activePreys.forEach { prey ->
            val px = prey.x * cellSize; val py = (prey.y * cellSize) + (sin(tickCount * 0.1 + prey.visualOffset) * 2).toInt()
            val cSize = prey.type.baseSize; val offset = (cellSize - cSize) / 2

            g2d.color = Color(prey.type.color.red, prey.type.color.green, prey.type.color.blue, 50)
            g2d.fillOval(px + offset - 4, py + offset - 4, cSize + 8, cSize + 8)

            g2d.color = prey.type.color
            if (prey.type == PreyType.POISON || prey.type == PreyType.FEAR_ORB) g2d.fillRoundRect(px + offset, py + offset, cSize, cSize, 6, 6)
            else g2d.fillOval(px + offset, py + offset, cSize, cSize)

            g2d.color = Color(255, 255, 255, 160)
            g2d.fillOval(px + offset + cSize/5, py + offset + cSize/5, cSize/4, cSize/4)
        }

        val pFrac = if (crashStunTicks > 0) 1.0 else minOf(1.0, (System.currentTimeMillis() - lastLogicTick) / currentDelay.toDouble())
        val bFrac = if (crashStunTicks > 0) 1.0 else if (isBossEntrance) minOf(1.0, (System.currentTimeMillis() - lastBossLogicTick) / 20.0)
        else if (isBossPhase && !isBossDevourSequence && !isPlayerDevourSequence) minOf(1.0, (System.currentTimeMillis() - lastBossLogicTick) / bossDelay.toDouble()) else pFrac

        if (isPlayerDevourSequence) {
            bossSnake?.let { if (it.body.isNotEmpty()) drawSnake(g2d, it, bFrac, isPlayer = false) }
            if (snake.body.isNotEmpty()) drawSnake(g2d, snake, pFrac, isPlayer = true)
        } else {
            if (snake.body.isNotEmpty()) drawSnake(g2d, snake, pFrac, isPlayer = true)
            bossSnake?.let { if (it.body.isNotEmpty()) drawSnake(g2d, it, bFrac, isPlayer = false) }
        }

        floatingTexts.forEach { ft ->
            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ft.alpha)
            g2d.font = Font("SansSerif", Font.BOLD, 18); g2d.color = ft.color
            g2d.drawString(ft.text, ft.x.toFloat() + 4, ft.y.toFloat() + 10)
        }
        g2d.composite = originalComposite

        if (isBossPhase && !isBossEntrance && !isBossDevourSequence && !isPlayerDevourSequence && crashStunTicks == 0) {
            g2d.font = Font("SansSerif", Font.BOLD, 20)
            g2d.color = Color(0, 255, 255, 180)
            val objectiveMsg = "OBJECTIVE: Collect $orbsToWin Orbs to Devour the Boss!"
            g2d.drawString(objectiveMsg, (width - g2d.fontMetrics.stringWidth(objectiveMsg)) / 2, (gridHeight * cellSize) - 20)
        }

        g2d.translate(-sx, -(uiHeight + sy))

        if (crashStunTicks > 0) {
            g2d.font = Font("SansSerif", Font.BOLD, 55); g2d.color = Color.WHITE
            val msg = when (crashCause) { "BOSS_HIT" -> "BOSS COLLISION!"; "BOSS_RACE" -> "RACE LOST!"; else -> "CRASH!" }
            g2d.drawString(msg, (width - g2d.fontMetrics.stringWidth(msg)) / 2, height / 2)
        } else if (invincibleTicks > 100 && (tickCount / 5) % 2 == 0) {
            g2d.font = Font("SansSerif", Font.BOLD, 55); g2d.color = Color.WHITE
            g2d.drawString("SURVIVE!", (width - g2d.fontMetrics.stringWidth("SURVIVE!")) / 2, height / 2)
        } else if (isBossDevourSequence) {
            g2d.font = Font("SansSerif", Font.BOLD, 45); g2d.color = Color.RED
            g2d.drawString("CONSUMED!", (width - g2d.fontMetrics.stringWidth("CONSUMED!")) / 2, height / 2)
        } else if (isPlayerDevourSequence) {
            g2d.font = Font("SansSerif", Font.BOLD, 45); g2d.color = if (tickCount % 20 < 10) Color.CYAN else Color.WHITE
            g2d.drawString("DEVOUR THE BOSS!", (width - g2d.fontMetrics.stringWidth("DEVOUR THE BOSS!")) / 2, height / 2)
        }

        if (isPaused) drawOverlay(g2d, "PAUSED", "Press Space or P to Resume")
        if (state == GameState.GAME_OVER) drawOverlay(g2d, "GAME OVER", "Press 'R' for Menu", Color(255, 60, 100))
    }

    private fun drawSnake(g2d: Graphics2D, s: Snake, fraction: Double, isPlayer: Boolean) {
        val isInvincible = isPlayer && invincibleTicks > 0
        val isSick = s.sickTicks > 0 || (isPlayer && isBossEntrance)
        val shakeX = if (isSick) (Math.random() * 4 - 2) else 0.0
        val shakeY = if (isSick) (Math.random() * 4 - 2) else 0.0

        for (i in s.body.size - 1 downTo 1) {
            if (i >= s.body.size) continue
            val currPt = s.body[i]; val prevPt = if (i < s.prevBody.size) s.prevBody[i] else currPt
            val drawX = lerp(prevPt.x, currPt.x, fraction) * cellSize + shakeX
            val drawY = lerp(prevPt.y, currPt.y, fraction) * cellSize + shakeY

            val taper = if (i > s.body.size - 4) (i - (s.body.size - 4)) * 2 else 0
            val segSize = maxOf(cellSize - 6 - taper, 6); val segOffset = (cellSize - segSize) / 2

            if (isInvincible && (tickCount / 5) % 2 == 0) g2d.color = Color(255, 255, 255, 200)
            else if (isPlayer && isBossDevourSequence) g2d.color = Color(50, 50, 55)
            else if (!isPlayer && isPlayerDevourSequence) g2d.color = if (tickCount % 10 < 5) Color(0, 200, 255) else Color(0, 120, 200)
            else if (isSick && (tickCount / 5) % 2 == 0) g2d.color = if (isBossEntrance) Color(150, 150, 150) else Color(160, 40, 255)
            else if (isPlayer) g2d.color = if (i % 2 == 0) Color(20, 180, 80) else Color(30, 200, 90)
            else g2d.color = if (i % 2 == 0) Color(220, 10, 30, 200) else Color(180, 5, 20, 200)

            g2d.fillRoundRect((drawX + segOffset).toInt(), (drawY + segOffset).toInt(), segSize, segSize, 10, 10)

            if (!isPlayer && !isPlayerDevourSequence) {
                g2d.color = Color(255, 100, 100, 150); g2d.fillOval((drawX + cellSize/2 - 2).toInt(), (drawY + cellSize/2 - 2).toInt(), 4, 4)
            }
        }

        val head = s.body.firstOrNull() ?: return
        val prevHead = if (s.prevBody.isNotEmpty()) s.prevBody.first() else head
        val hx = lerp(prevHead.x, head.x, fraction) * cellSize + shakeX
        val hy = lerp(prevHead.y, head.y, fraction) * cellSize + shakeY

        if (isInvincible && (tickCount / 5) % 2 == 0) g2d.color = Color.WHITE
        else if (isPlayer && isBossDevourSequence) g2d.color = Color(80, 80, 85)
        else if (!isPlayer && isPlayerDevourSequence) g2d.color = Color(0, 255, 255)
        else g2d.color = if (isSick && (tickCount / 5) % 2 == 0) { if(isBossEntrance) Color.GRAY else Color(200, 80, 255) } else s.colorTheme

        g2d.fillRoundRect((hx + 1).toInt(), (hy + 1).toInt(), cellSize - 2, cellSize - 2, 12, 12)

        if (!isPlayer) {
            g2d.color = Color(50, 0, 0); val hxI = hx.toInt(); val hyI = hy.toInt()
            when (s.direction) {
                Direction.RIGHT -> { g2d.fillPolygon(intArrayOf(hxI+4, hxI, hxI-6), intArrayOf(hyI, hyI-4, hyI-8), 3); g2d.fillPolygon(intArrayOf(hxI+4, hxI, hxI-6), intArrayOf(hyI+cellSize, hyI+cellSize+4, hyI+cellSize+8), 3) }
                Direction.LEFT -> { g2d.fillPolygon(intArrayOf(hxI+cellSize-4, hxI+cellSize, hxI+cellSize+6), intArrayOf(hyI, hyI-4, hyI-8), 3); g2d.fillPolygon(intArrayOf(hxI+cellSize-4, hxI+cellSize, hxI+cellSize+6), intArrayOf(hyI+cellSize, hyI+cellSize+4, hyI+cellSize+8), 3) }
                Direction.UP -> { g2d.fillPolygon(intArrayOf(hxI, hxI-4, hxI-8), intArrayOf(hyI+cellSize-4, hyI+cellSize, hyI+cellSize+6), 3); g2d.fillPolygon(intArrayOf(hxI+cellSize, hxI+cellSize+4, hxI+cellSize+8), intArrayOf(hyI+cellSize-4, hyI+cellSize, hyI+cellSize+6), 3) }
                Direction.DOWN -> { g2d.fillPolygon(intArrayOf(hxI, hxI-4, hxI-8), intArrayOf(hyI+4, hyI, hyI-6), 3); g2d.fillPolygon(intArrayOf(hxI+cellSize, hxI+cellSize+4, hxI+cellSize+8), intArrayOf(hyI+4, hyI, hyI-6), 3) }
            }
        }

        g2d.color = if (isPlayer && isBossDevourSequence) Color.RED else if (isSick) Color(255, 255, 0) else if (!isPlayer && isPlayerDevourSequence) Color.BLUE else if (isPlayer) Color.WHITE else Color(255, 255, 100)
        val cx = hx.toInt(); val cy = hy.toInt()
        when (s.direction) {
            Direction.RIGHT -> { g2d.fillOval(cx+14, cy+4, 5, 5); g2d.fillOval(cx+14, cy+13, 5, 5) }
            Direction.LEFT -> { g2d.fillOval(cx+4, cy+4, 5, 5); g2d.fillOval(cx+4, cy+13, 5, 5) }
            Direction.UP -> { g2d.fillOval(cx+4, cy+4, 5, 5); g2d.fillOval(cx+13, cy+4, 5, 5) }
            Direction.DOWN -> { g2d.fillOval(cx+4, cy+13, 5, 5); g2d.fillOval(cx+13, cy+13, 5, 5) }
        }
    }

    // --- SUB MENUS ---

    private fun drawMenu(g2d: Graphics2D) {
        val w = width; val h = height
        g2d.color = Color(15, 16, 22); g2d.fillRect(0, 0, w, h)

        g2d.font = Font("SansSerif", Font.BOLD, 60)
        val title = "ANOMALY SNAKE"
        g2d.color = Color(10, 220, 100, 50); g2d.drawString(title, (w - g2d.fontMetrics.stringWidth(title)) / 2 + 4, 104)
        g2d.color = Color.WHITE; g2d.drawString(title, (w - g2d.fontMetrics.stringWidth(title)) / 2, 100)

        val c1 = Rectangle(w / 2 - 280, 160, 250, 220)
        drawMenuCard(g2d, c1, "LEVEL 1", "FREE ROAM", "Screen wrapping enabled.\nPure arcade survival.", menuSelection == 1, false)

        val c2 = Rectangle(w / 2 + 30, 160, 250, 220)
        drawMenuCard(g2d, c2, "LEVEL 2", "THE BOX", "Lethal borders.\nClaustrophobic terror.", menuSelection == 2, true)

        val aboutBtn = Rectangle(w - 110, h - 50, 90, 35)
        g2d.color = if (isHoveringAbout) Color(50, 55, 70) else Color(30, 32, 42)
        g2d.fillRoundRect(aboutBtn.x, aboutBtn.y, aboutBtn.width, aboutBtn.height, 15, 15)
        g2d.color = if (isHoveringAbout) Color.WHITE else Color.LIGHT_GRAY
        g2d.drawRoundRect(aboutBtn.x, aboutBtn.y, aboutBtn.width, aboutBtn.height, 15, 15)

        g2d.font = Font("SansSerif", Font.BOLD, 14)
        val aboutTxt = "Info [I]"
        g2d.drawString(aboutTxt, aboutBtn.x + (aboutBtn.width - g2d.fontMetrics.stringWidth(aboutTxt)) / 2, aboutBtn.y + 22)

        g2d.font = Font("SansSerif", Font.PLAIN, 16); g2d.color = Color.GRAY
        val footer = "Use Arrows/Mouse to Select • Press ENTER to Play"
        g2d.drawString(footer, (w - g2d.fontMetrics.stringWidth(footer)) / 2, h - 30)
    }

    private fun drawMenuCard(g2d: Graphics2D, rect: Rectangle, subtitle: String, title: String, desc: String, isSelected: Boolean, hasWalls: Boolean) {
        g2d.color = if (isSelected) Color(28, 30, 40) else Color(20, 22, 28)
        g2d.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 20, 20)

        g2d.color = if (isSelected) Color(10, 220, 100) else Color(50, 55, 70)
        g2d.stroke = BasicStroke(if (isSelected) 3f else 1f)
        g2d.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 20, 20)
        g2d.stroke = BasicStroke(1f)

        val illRect = Rectangle(rect.x + 20, rect.y + 20, rect.width - 40, 100)
        g2d.color = Color(10, 12, 16)
        g2d.fillRoundRect(illRect.x, illRect.y, illRect.width, illRect.height, 10, 10)

        val cx = illRect.x + illRect.width / 2; val cy = illRect.y + illRect.height / 2

        if (hasWalls) {
            g2d.color = Color(60, 20, 30); g2d.fillRect(illRect.x + 10, illRect.y + 10, illRect.width - 20, illRect.height - 20)
            g2d.color = Color(20, 25, 30); g2d.fillRect(illRect.x + 20, illRect.y + 20, illRect.width - 40, illRect.height - 40)

            g2d.color = Color(20, 180, 80)
            g2d.fillRoundRect(cx - 30, cy + 10, 12, 12, 4, 4); g2d.fillRoundRect(cx - 18, cy + 10, 12, 12, 4, 4)
            g2d.fillRoundRect(cx - 18, cy - 2, 12, 12, 4, 4); g2d.fillRoundRect(cx - 6, cy - 2, 12, 12, 4, 4)
            g2d.color = Color.WHITE; g2d.fillRect(cx - 2, cy, 3, 3)

            g2d.color = Color(255, 60, 100); g2d.fillOval(cx + 20, cy - 2, 12, 12)
        } else {
            g2d.color = Color(30, 40, 50)
            val dash = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1.0f, floatArrayOf(5f, 5f), 0f)
            g2d.stroke = dash; g2d.drawRect(illRect.x + 15, illRect.y + 15, illRect.width - 30, illRect.height - 30)
            g2d.stroke = BasicStroke(1f)

            g2d.color = Color(20, 180, 80)
            // Bug Fix: Loop segments cleanly across the illustration box
            val maxDist = illRect.width - 30
            for (i in 0..2) {
                val offset = (tickCount + i * 20) % maxDist
                g2d.fillRoundRect(illRect.x + 15 + offset, cy, 12, 12, 4, 4)
            }
        }

        g2d.font = Font("SansSerif", Font.BOLD, 14); g2d.color = if (isSelected) Color(10, 220, 100) else Color.GRAY
        g2d.drawString(subtitle, rect.x + 20, rect.y + 145)

        g2d.font = Font("SansSerif", Font.BOLD, 22); g2d.color = Color.WHITE
        g2d.drawString(title, rect.x + 20, rect.y + 170)

        g2d.font = Font("SansSerif", Font.PLAIN, 12); g2d.color = Color.LIGHT_GRAY
        val descLines = desc.split("\n"); var dY = rect.y + 190
        for (line in descLines) { g2d.drawString(line, rect.x + 20, dY); dY += 16 }
    }

    private fun drawAbout(g2d: Graphics2D) {
        val w = width; val h = height
        g2d.color = Color(15, 16, 22); g2d.fillRect(0, 0, w, h)

        g2d.color = Color(20, 22, 28); g2d.fillRoundRect(w / 2 - 300, 50, 600, h - 100, 20, 20)
        g2d.color = Color(50, 55, 70); g2d.drawRoundRect(w / 2 - 300, 50, 600, h - 100, 20, 20)

        g2d.font = Font("SansSerif", Font.BOLD, 30)
        g2d.color = Color(10, 220, 100)
        g2d.drawString("ABOUT & CONTROLS", w / 2 - 165, 100)

        // Reduced Font Size
        g2d.font = Font("SansSerif", Font.PLAIN, 14)
        val aboutText = arrayOf(
            "Welcome to Anomaly Snake, an ASMR-driven cinematic experience.",
            "Survive the neon grid, but beware the Crimson Anomaly...",
            "",
            "CONTROLS:",
            "• Arrow Keys / WASD : Navigate the Snake",
            "• SPACE / P : Pause the Game",
            "• ESC : Return to Menu",
            "",
            "MECHANICS:",
            "• Glowing Orbs give points. Purple Orbs steal lives.",
            "• Bosses spawn sequentially as your score grows.",
            "• If the Boss spawns, eat 5 Cyan Soul Orbs before it does.",
            "• Win the race to Devour the Boss!"
        )

        var textY = 140
        for (line in aboutText) {
            if (line.startsWith("•") || line.startsWith("CONTROLS") || line.startsWith("MECHANICS")) g2d.color = Color.LIGHT_GRAY
            else g2d.color = Color.WHITE
            g2d.drawString(line, w / 2 - 250, textY)
            textY += 20
        }

        val devBtn = Rectangle(w / 2 - 125, h - 130, 250, 45)
        g2d.color = if (isHoveringDevBtn) Color(10, 220, 100) else Color(30, 32, 42)
        g2d.fillRoundRect(devBtn.x, devBtn.y, devBtn.width, devBtn.height, 10, 10)

        g2d.font = Font("SansSerif", Font.BOLD, 16)
        g2d.color = if (isHoveringDevBtn) Color(15, 16, 22) else Color.WHITE
        val devTxt = "Developer Info"
        g2d.drawString(devTxt, devBtn.x + (devBtn.width - g2d.fontMetrics.stringWidth(devTxt)) / 2, devBtn.y + 28)
    }

    private fun drawAboutMichael(g2d: Graphics2D) {
        val w = width; val h = height
        g2d.color = Color(15, 16, 22); g2d.fillRect(0, 0, w, h)

        g2d.font = Font("SansSerif", Font.BOLD, 45)
        g2d.color = Color.WHITE
        g2d.drawString("MICHAEL WAYNE", w / 2 - g2d.fontMetrics.stringWidth("MICHAEL WAYNE") / 2, h / 2 - 40)

        g2d.font = Font("SansSerif", Font.ITALIC, 20)
        g2d.color = Color.LIGHT_GRAY
        g2d.drawString("Passionate software engineer", w / 2 - g2d.fontMetrics.stringWidth("Passionate software engineer") / 2, h / 2 - 10)

        val coffeeBtn = Rectangle(w / 2 - 140, h / 2 + 20, 280, 55)
        g2d.color = if (isHoveringCoffee) Color(255, 180, 50) else Color(255, 140, 0)
        g2d.fillRoundRect(coffeeBtn.x, coffeeBtn.y, coffeeBtn.width, coffeeBtn.height, 15, 15)

        // Draw Simple Vector Coffee Cup
        g2d.color = Color.WHITE
        val cx = coffeeBtn.x + 30; val cy = coffeeBtn.y + 15
        g2d.fillRoundRect(cx, cy, 20, 24, 8, 8)
        g2d.drawOval(cx + 12, cy + 4, 12, 14) // Handle
        g2d.color = if (isHoveringCoffee) Color(255, 180, 50) else Color(255, 140, 0)
        g2d.fillOval(cx + 14, cy + 6, 8, 10) // Cutout handle

        g2d.color = Color.WHITE
        val wave = (sin(tickCount * 0.1) * 2).toInt()
        g2d.drawLine(cx + 6, cy - 4 - wave, cx + 6, cy - 8 - wave) // Steam
        g2d.drawLine(cx + 14, cy - 2 + wave, cx + 14, cy - 6 + wave)

        g2d.font = Font("SansSerif", Font.BOLD, 20)
        val btnTxt = "Buy Me a Coffee"
        g2d.drawString(btnTxt, coffeeBtn.x + 65, coffeeBtn.y + 35)

        g2d.font = Font("SansSerif", Font.PLAIN, 14); g2d.color = Color.GRAY
        g2d.drawString("Press [ESC] to Return", w / 2 - g2d.fontMetrics.stringWidth("Press [ESC] to Return") / 2, h - 30)
    }

    private fun drawDonate(g2d: Graphics2D) {
        val w = width; val h = height
        g2d.color = Color(15, 16, 22); g2d.fillRect(0, 0, w, h)

        g2d.font = Font("SansSerif", Font.BOLD, 40)
        g2d.color = Color(255, 180, 50)
        g2d.drawString("SUPPORT THE DEV", w / 2 - g2d.fontMetrics.stringWidth("SUPPORT THE DEV") / 2, h / 2 - 80)

        // Tiers: 5, 20, 50
        val prices = arrayOf("$5", "$20", "$50")
        val descs = arrayOf("Small Coffee", "Lunch", "Hero Tier")

        for (i in 0..2) {
            val rect = Rectangle(w / 2 - 210 + (i * 150), h / 2 - 20, 120, 140)
            val isHovered = hoverDonateIdx == i

            g2d.color = if (isHovered) Color(40, 42, 50) else Color(20, 22, 28)
            g2d.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 15, 15)
            g2d.color = if (isHovered) Color(255, 180, 50) else Color(50, 55, 70)
            g2d.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 15, 15)

            g2d.font = Font("SansSerif", Font.BOLD, 35)
            g2d.color = Color.WHITE
            g2d.drawString(prices[i], rect.x + (rect.width - g2d.fontMetrics.stringWidth(prices[i])) / 2, rect.y + 60)

            g2d.font = Font("SansSerif", Font.PLAIN, 14)
            g2d.color = Color.LIGHT_GRAY
            g2d.drawString(descs[i], rect.x + (rect.width - g2d.fontMetrics.stringWidth(descs[i])) / 2, rect.y + 100)
        }

        g2d.font = Font("SansSerif", Font.PLAIN, 14); g2d.color = Color.GRAY
        g2d.drawString("Press [ESC] to Return", w / 2 - g2d.fontMetrics.stringWidth("Press [ESC] to Return") / 2, h - 30)
    }

    private fun drawOverlay(g2d: Graphics2D, title: String, sub: String, titleColor: Color = Color.WHITE) {
        g2d.color = Color(0, 0, 0, 180)
        g2d.fillRect(0, 0, width, height)
        g2d.color = titleColor
        g2d.font = Font("SansSerif", Font.BOLD, 50)
        g2d.drawString(title, (width - g2d.fontMetrics.stringWidth(title)) / 2, height / 2 - 20)
        g2d.color = Color.LIGHT_GRAY
        g2d.font = Font("SansSerif", Font.PLAIN, 20)
        g2d.drawString(sub, (width - g2d.fontMetrics.stringWidth(sub)) / 2, height / 2 + 30)
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("Anomaly Snake")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isResizable = false
        frame.add(SnakeGamePanel())
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}