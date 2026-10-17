package app.twistcubee

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.twistcubee.data.GameSave
import app.twistcubee.data.Palettes
import app.twistcubee.data.SaveStore
import app.twistcubee.data.Settings
import app.twistcubee.data.Stats
import app.twistcubee.game.CubeGame
import app.twistcubee.model.Move
import app.twistcubee.solver.SolverService
import app.twistcubee.ui.ConfettiView
import app.twistcubee.ui.SoundFx
import app.twistcubee.ui.SwatchView
import app.twistcubee.ui.TimerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Single activity: the GL cube view stays alive the whole time while lightweight view layers
 * (menu, game HUD, solved card, panels) fade in and out on top of it. No fragments, no AndroidX.
 */
class MainActivity : Activity(), CubeGame.Listener {

    private lateinit var game: CubeGame
    private lateinit var cubeView: CubeView
    private lateinit var settings: Settings
    private lateinit var stats: Stats
    private lateinit var saves: SaveStore
    private lateinit var sound: SoundFx

    private val handler = Handler(Looper.getMainLooper())
    private val pendingEvents = AtomicInteger()
    private val eventRunnable = Runnable { handleEvents(pendingEvents.getAndSet(0)) }
    private val hud = CubeGame.Hud()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveCache = HashMap<Int, GameSave>()

    private lateinit var root: FrameLayout
    private lateinit var menuLayer: View
    private lateinit var menuColumn: LinearLayout
    private lateinit var menuSpacer: View
    private lateinit var menuTop: View
    private lateinit var menuTitle: TextView
    private lateinit var menuSubtitle: View
    private lateinit var menuBottom: View
    private lateinit var menuIcons: View
    private lateinit var gameLayer: View
    private lateinit var gameTop: View
    private lateinit var gameTopRow: View
    private lateinit var gameBottom: LinearLayout
    private lateinit var sizeLabel: TextView
    private lateinit var sizeName: TextView
    private lateinit var sizeInfo: TextView
    private lateinit var sizeScroll: HorizontalScrollView
    private lateinit var sizeStrip: LinearLayout
    private lateinit var btnPlay: Button
    private lateinit var btnNew: Button
    private lateinit var timerBox: View
    private lateinit var timerView: TimerView
    private lateinit var movesView: TextView
    private lateinit var statusView: TextView
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var btnScramble: View
    private lateinit var btnHint: View
    private lateinit var btnSolve: View
    private lateinit var btnLock: ImageButton
    private lateinit var solvedLayer: View
    private lateinit var solvedCard: View
    private lateinit var solvedTime: TextView
    private lateinit var solvedDetail: TextView
    private lateinit var solvedBadge: TextView
    private lateinit var confetti: ConfettiView
    private lateinit var panelLayer: View
    private lateinit var panelCard: View
    private lateinit var panelTitle: TextView
    private lateinit var panelContent: LinearLayout

    private var size = 3
    private var landscape = false
    private var inGame = false
    private var panelOpen = false
    private var solvedShowing = false
    private var hapticsOn = true
    private var tipSeen = false
    private var timerTicking = false
    private var transientText: String? = null
    private var transientUntil = 0L
    private var backCallback: Any? = null
    private var density = 1f

    /** Drives the stopwatch (and the inspection countdown) once per display frame while running. */
    private val timerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!timerTicking) return
            val inspectLeft = game.tickInspection()
            if (inspectLeft >= 0) {
                timerView.setSeconds(((inspectLeft + 999) / 1000).toInt())
            } else {
                timerView.setMillis(game.elapsedMs())
            }
            if (inspectLeft >= 0 || game.isTimerRunning()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                timerTicking = false
            }
        }
    }

    private val clearTransient = Runnable { updateStatus() }

    /** Debounced background save shortly after the cube stops moving (survives crashes/kills). */
    private val autosave: Runnable = object : Runnable {
        override fun run() {
            val snap = game.snapshotIfIdle()
            if (snap == null) {
                handler.postDelayed(this, 500)
                return
            }
            saveCache[snap.n] = snap
            io.execute { saves.save(snap) }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(R.layout.activity_main)
        density = resources.displayMetrics.density
        settings = Settings(this)
        stats = Stats(this)
        saves = SaveStore(filesDir)
        sound = SoundFx(this)
        tipSeen = settings.tipSeen

        game = CubeGame(this)
        game.setDensity(density)
        bindViews()
        applyOrientation()
        cubeView.init(game)
        applySettings()
        size = settings.lastSize
        game.load(size, loadSave(size))
        setupInsets()
        showMenu(animate = false)
        // Build the 3x3 solver tables in the background while the player looks at the menu.
        handler.postDelayed({
            SolverService.prewarm(3)
            SolverService.prewarm(size)
        }, 1200)
    }

    override fun onResume() {
        super.onResume()
        cubeView.onResume()
        if (inGame) game.resumeTimer()
        refreshHud()
        cubeView.requestRender()
    }

    override fun onPause() {
        handler.removeCallbacks(autosave)
        game.pauseTimer()
        val snap = game.snapshot()
        saveCache[snap.n] = snap
        // Tiny file (a few KB); written synchronously so nothing is lost if the process dies.
        saves.save(snap)
        cubeView.onPause()
        timerTicking = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sound.release()
        io.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    /** Pre-Android 13 back handling; API 33+ goes through [BackApi33] (predictive back). */
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ setup

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT < 35) {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        cubeView = findViewById(R.id.cube_view)
        menuLayer = findViewById(R.id.menu_layer)
        menuColumn = findViewById(R.id.menu_column)
        menuSpacer = findViewById(R.id.menu_spacer)
        menuTop = findViewById(R.id.menu_top)
        menuTitle = findViewById(R.id.menu_title)
        menuSubtitle = findViewById(R.id.menu_subtitle)
        menuBottom = findViewById(R.id.menu_bottom)
        menuIcons = findViewById(R.id.menu_icons)
        gameLayer = findViewById(R.id.game_layer)
        gameTop = findViewById(R.id.game_top)
        gameTopRow = findViewById(R.id.game_top_row)
        gameBottom = findViewById(R.id.game_bottom)
        sizeLabel = findViewById(R.id.size_label)
        sizeName = findViewById(R.id.size_name)
        sizeInfo = findViewById(R.id.size_info)
        sizeScroll = findViewById(R.id.size_scroll)
        sizeStrip = findViewById(R.id.size_strip)
        btnPlay = findViewById(R.id.btn_play)
        btnNew = findViewById(R.id.btn_new)
        timerBox = findViewById(R.id.timer_box)
        timerView = findViewById(R.id.timer)
        movesView = findViewById(R.id.moves)
        statusView = findViewById(R.id.status)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnScramble = findViewById(R.id.btn_scramble)
        btnHint = findViewById(R.id.btn_hint)
        btnSolve = findViewById(R.id.btn_solve)
        btnLock = findViewById(R.id.btn_lock)
        solvedLayer = findViewById(R.id.solved_layer)
        solvedCard = findViewById(R.id.solved_card)
        solvedTime = findViewById(R.id.solved_time)
        solvedDetail = findViewById(R.id.solved_detail)
        solvedBadge = findViewById(R.id.solved_badge)
        confetti = findViewById(R.id.confetti)
        panelLayer = findViewById(R.id.panel_layer)
        panelCard = findViewById(R.id.panel_card)
        panelTitle = findViewById(R.id.panel_title)
        panelContent = findViewById(R.id.panel_content)

        buildSizeStrip()
        btnPlay.setOnClickListener { enterGame(newGame = !game.hasProgress()) }
        btnNew.setOnClickListener { enterGame(newGame = game.hasProgress()) }
        findViewById<View>(R.id.btn_stats).setOnClickListener { openPanel(getString(R.string.stats), ::buildStats) }
        findViewById<View>(R.id.btn_settings).setOnClickListener { openPanel(getString(R.string.settings), ::buildSettings) }
        findViewById<View>(R.id.btn_help).setOnClickListener { openPanel(getString(R.string.help), ::buildHelp) }
        findViewById<View>(R.id.btn_about).setOnClickListener { openPanel(getString(R.string.about), ::buildAbout) }

        findViewById<View>(R.id.btn_back).setOnClickListener { showMenu(animate = true) }
        findViewById<View>(R.id.btn_view).setOnClickListener {
            game.resetView()
            cubeView.requestRender()
        }
        btnLock.setOnClickListener {
            val locked = game.toggleLock()
            showTransient(getString(if (locked) R.string.status_locked else R.string.status_unlocked))
        }
        btnUndo.setOnClickListener { game.undo(); cubeView.requestRender() }
        btnRedo.setOnClickListener { game.redo(); cubeView.requestRender() }
        btnScramble.setOnClickListener { confirmScramble() }
        btnHint.setOnClickListener { game.requestHint(); cubeView.requestRender() }
        btnSolve.setOnClickListener { confirmSolve() }

        findViewById<View>(R.id.solved_close).setOnClickListener { hideSolved(immediate = false) }
        findViewById<View>(R.id.solved_new).setOnClickListener {
            hideSolved(immediate = false)
            game.newGame()
            cubeView.requestRender()
        }
        findViewById<View>(R.id.panel_close).setOnClickListener { closePanel() }
        panelLayer.setOnClickListener { closePanel() }

        val insetListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) updateCubeInsets(animate = true)
        }
        for (v in arrayOf(menuColumn, menuTop, menuBottom, gameTop, gameBottom)) v.addOnLayoutChangeListener(insetListener)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) updateCubeInsets(animate = false)
        }
    }

    /** One chip per cube size so any size is a single tap away. */
    private fun buildSizeStrip() {
        val chip = (44 * density).toInt()
        val gap = (8 * density).toInt()
        for (n in CubeGame.MIN_SIZE..CubeGame.MAX_SIZE) {
            val tv = TextView(this, null, 0, R.style.SizeChip).apply {
                text = "%d".format(n)
                contentDescription = getString(R.string.choose_size, n)
                setOnClickListener { selectSize(n) }
            }
            sizeStrip.addView(tv, LinearLayout.LayoutParams(chip, chip).apply { marginEnd = gap })
        }
    }

    private fun setupInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            var l: Int
            var t: Int
            var r: Int
            var b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = insets.systemWindowInsetLeft
                    t = insets.systemWindowInsetTop
                    r = insets.systemWindowInsetRight
                    b = insets.systemWindowInsetBottom
                }
            }
            for (v in arrayOf(menuLayer, gameLayer, solvedLayer, panelLayer)) v.setPadding(l, t, r, b)
            insets
        }
        root.requestApplyInsets()
    }

    /** Portrait: stacked menu and a bottom toolbar. Landscape: side menu column and a side rail. */
    private fun applyOrientation() {
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dp = density
        val col = menuColumn.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            col.width = (400 * dp).toInt()
            col.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            menuColumn.gravity = Gravity.CENTER
            menuSpacer.visibility = View.GONE
            menuTop.setPadding(0, 0, 0, (4 * dp).toInt())
            menuBottom.setPadding(0, 0, 0, 0)
        } else {
            col.width = ViewGroup.LayoutParams.MATCH_PARENT
            col.gravity = Gravity.NO_GRAVITY
            menuColumn.gravity = Gravity.CENTER_HORIZONTAL
            menuSpacer.visibility = View.VISIBLE
            menuTop.setPadding(0, (28 * dp).toInt(), 0, (8 * dp).toInt())
            menuBottom.setPadding(0, 0, 0, (16 * dp).toInt())
        }
        menuColumn.layoutParams = col
        // Landscape phones are short: drop the subtitle and tighten the spacing.
        menuSubtitle.visibility = if (landscape) View.GONE else View.VISIBLE
        menuTitle.textSize = if (landscape) 24f else 30f
        (sizeInfo.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = ((if (landscape) 6 else 10) * dp).toInt()
            bottomMargin = ((if (landscape) 8 else 16) * dp).toInt()
        }
        (menuIcons.layoutParams as LinearLayout.LayoutParams).topMargin = ((if (landscape) 4 else 14) * dp).toInt()

        val bar = gameBottom.layoutParams as FrameLayout.LayoutParams
        val m = (12 * dp).toInt()
        if (landscape) {
            gameBottom.orientation = LinearLayout.VERTICAL
            bar.width = ViewGroup.LayoutParams.WRAP_CONTENT
            bar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            bar.setMargins(m, m, m, m)
        } else {
            gameBottom.orientation = LinearLayout.HORIZONTAL
            bar.width = ViewGroup.LayoutParams.MATCH_PARENT
            bar.gravity = Gravity.BOTTOM
            bar.setMargins(m, 0, m, m)
        }
        gameBottom.layoutParams = bar
        for (i in 0 until gameBottom.childCount) {
            val child = gameBottom.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            if (landscape) {
                lp.width = (72 * dp).toInt()
                lp.weight = 0f
            } else {
                lp.width = 0
                lp.weight = 1f
            }
            child.layoutParams = lp
        }
    }

    private fun applySettings() {
        game.setStyle(Palettes.SCHEMES[settings.palette], Palettes.BODIES[settings.body])
        game.setTileStyle(settings.tiles)
        game.setBackground(Palettes.BACKGROUNDS[settings.background])
        game.setAnimSpeed(settings.speed)
        game.setInspection(settings.inspection)
        sound.enabled = settings.sound
        hapticsOn = settings.haptics
        timerBox.visibility = if (settings.showTimer) View.VISIBLE else View.INVISIBLE
        cubeView.requestRender()
    }

    // ------------------------------------------------------------------ game events

    override fun onGameEvents(events: Int) {
        while (true) {
            val old = pendingEvents.get()
            if (pendingEvents.compareAndSet(old, old or events)) {
                if (old == 0) handler.post(eventRunnable)
                return
            }
        }
    }

    override fun requestRender() {
        cubeView.requestRender()
    }

    private fun handleEvents(ev: Int) {
        if (ev and (CubeGame.EV_MOVE_USER or CubeGame.EV_MOVE_AUTO or CubeGame.EV_SCRAMBLED) != 0) {
            handler.removeCallbacks(autosave)
            handler.postDelayed(autosave, 1500)
        }
        if (ev and CubeGame.EV_MOVE_USER != 0) {
            sound.turn(true)
            if (hapticsOn) cubeView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (!tipSeen) {
                tipSeen = true
                settings.tipSeen = true
            }
        } else if (ev and CubeGame.EV_MOVE_AUTO != 0) {
            sound.turn(false)
        }
        if (ev and CubeGame.EV_SOLVED != 0) onSolved()
        if (ev and (CubeGame.EV_HUD or CubeGame.EV_SCRAMBLED or CubeGame.EV_MOVE_USER) != 0) refreshHud()
    }

    private fun refreshHud() {
        game.hud(hud)
        if (!inGame) return
        movesView.text = resources.getQuantityString(R.plurals.moves_count, hud.moves, hud.moves)
        setEnabled(btnUndo, hud.canUndo)
        setEnabled(btnRedo, hud.canRedo)
        val solverSize = SolverService.supports(hud.n)
        btnHint.visibility = if (solverSize) View.VISIBLE else View.GONE
        val busy = hud.status == CubeGame.ST_SCRAMBLING || hud.autoSolving
        setEnabled(btnHint, solverSize && !hud.solved && !busy)
        setEnabled(btnSolve, !hud.solved && !busy && !hud.solverBusy)
        btnLock.isActivated = hud.locked
        btnLock.setImageResource(if (hud.locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        updateStatus()
        updateTimer()
    }

    private fun updateTimer() {
        val running = inGame && game.isTimerRunning()
        val inspecting = inGame && hud.inspecting
        timerView.setColor(
            when {
                inspecting -> getColor(R.color.accent)
                running -> Color.WHITE
                hud.status == CubeGame.ST_FREE && hud.solved && game.elapsedMs() > 0 -> getColor(R.color.accent)
                else -> 0xB3FFFFFF.toInt()
            },
        )
        if (running || inspecting) {
            if (inspecting) {
                val left = game.tickInspection()
                if (left >= 0) timerView.setSeconds(((left + 999) / 1000).toInt())
            }
            if (!timerTicking) {
                timerTicking = true
                Choreographer.getInstance().removeFrameCallback(timerCallback)
                Choreographer.getInstance().postFrameCallback(timerCallback)
            }
        } else {
            timerTicking = false
            timerView.setMillis(game.elapsedMs())
        }
    }

    private fun updateStatus() {
        val text: String? = when {
            transientText != null && System.currentTimeMillis() < transientUntil -> transientText
            hud.solverFailed -> getString(R.string.status_solver_failed)
            hud.solverBusy -> getString(if (SolverService.isReady(hud.n)) R.string.status_thinking else R.string.status_preparing)
            hud.status == CubeGame.ST_SCRAMBLING -> getString(R.string.status_scrambling)
            hud.autoSolving -> getString(
                if (SolverService.supports(hud.n)) R.string.status_solving else R.string.status_rewinding,
                hud.queueDone,
                hud.queueTotal,
            )
            hud.hintMove >= 0 -> hintText()
            hud.inspecting -> getString(R.string.status_inspecting)
            !tipSeen && hud.moves == 0 -> getString(R.string.status_tip)
            hud.status == CubeGame.ST_READY -> getString(R.string.status_ready)
            else -> null
        }
        if (text == null) {
            statusView.visibility = View.INVISIBLE
        } else {
            statusView.text = text
            statusView.visibility = View.VISIBLE
        }
    }

    /** "Turn the green face clockwise" on a 3x3 (centers name the faces), notation otherwise. */
    private fun hintText(): String {
        if (hud.hintFaceColor < 0) return getString(R.string.status_hint, Move.notation(hud.hintMove, hud.n))
        val names = resources.getStringArray(
            if (settings.palette == Palettes.COLOR_BLIND_SCHEME) R.array.color_names_cb else R.array.color_names,
        )
        val dir = getString(
            when (hud.hintDir) {
                1 -> R.string.dir_clockwise
                2 -> R.string.dir_half
                else -> R.string.dir_counter_clockwise
            },
        )
        return getString(R.string.status_hint_face, names[hud.hintFaceColor], dir)
    }

    private fun showTransient(text: String) {
        transientText = text
        transientUntil = System.currentTimeMillis() + 1600
        handler.removeCallbacks(clearTransient)
        handler.postDelayed(clearTransient, 1650)
        updateStatus()
    }

    private fun onSolved() {
        val timeMs = game.lastSolveMs
        val moves = game.lastSolveMoves
        val timed = game.lastSolveTimed
        val assisted = game.lastSolveAssisted
        val ranked = timed && !assisted
        val newBest = ranked && stats.record(size, timeMs, moves)
        sound.solved()
        if (hapticsOn) cubeView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        saveAsync()
        if (!inGame) return

        val movesText = resources.getQuantityString(R.plurals.moves_count, moves, moves)
        val sizeText = getString(R.string.size_format, size)
        if (timed) {
            solvedTime.text = TimerView.format(timeMs)
            solvedDetail.text = getString(R.string.solved_detail, sizeText, movesText)
        } else {
            solvedTime.text = movesText
            solvedDetail.text = sizeText
        }
        solvedBadge.text = when {
            newBest -> getString(R.string.solved_new_best)
            assisted -> getString(R.string.solved_assisted)
            !timed -> getString(R.string.solved_free)
            else -> getString(R.string.solved_personal_best, TimerView.format(stats.bestMs(size)))
        }
        confetti.burst(Palettes.SCHEMES[settings.palette])
        solvedShowing = true
        updateBack()
        handler.postDelayed({
            if (!solvedShowing) return@postDelayed
            solvedLayer.visibility = View.VISIBLE
            solvedCard.animate().cancel()
            solvedCard.alpha = 0f
            solvedCard.translationY = 60f * density
            solvedCard.animate().alpha(1f).translationY(0f).setDuration(320)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            fade(gameBottom, show = false)
        }, 650)
    }

    private fun hideSolved(immediate: Boolean) {
        if (!solvedShowing && solvedLayer.visibility != View.VISIBLE) return
        solvedShowing = false
        updateBack()
        if (immediate) {
            solvedLayer.visibility = View.GONE
            confetti.stop()
        } else {
            solvedCard.animate().cancel()
            solvedCard.animate().alpha(0f).translationY(40f * density).setDuration(200)
                .withEndAction { solvedLayer.visibility = View.GONE }.start()
        }
        if (inGame) fade(gameBottom, show = true)
    }

    // ------------------------------------------------------------------ navigation

    private fun enterGame(newGame: Boolean) {
        hideSolved(immediate = true)
        inGame = true
        game.setScreen(CubeGame.SCREEN_GAME)
        if (newGame) game.newGame()
        fade(menuLayer, show = false)
        fade(gameLayer, show = true)
        gameBottom.alpha = 1f
        gameBottom.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SolverService.prewarm(size)
        refreshHud()
        updateCubeInsets(animate = true)
        updateBack()
        cubeView.requestRender()
    }

    private fun showMenu(animate: Boolean) {
        inGame = false
        hideSolved(immediate = true)
        if (panelOpen) closePanel()
        game.setScreen(CubeGame.SCREEN_MENU)
        timerTicking = false
        if (animate) {
            fade(gameLayer, show = false)
            fade(menuLayer, show = true)
        } else {
            gameLayer.visibility = View.GONE
            menuLayer.visibility = View.VISIBLE
            menuLayer.alpha = 1f
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveAsync()
        updateMenu()
        updateCubeInsets(animate)
        updateBack()
        cubeView.requestRender()
    }

    private fun handleBack(): Boolean {
        when {
            panelOpen -> closePanel()
            solvedShowing -> hideSolved(immediate = false)
            inGame -> showMenu(animate = true)
            else -> return false
        }
        return true
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = inGame || panelOpen || solvedShowing
        val current = backCallback
        if (needed && current == null) {
            backCallback = BackApi33.register(this) { handleBack() }
        } else if (!needed && current != null) {
            BackApi33.unregister(this, current)
            backCallback = null
        }
    }

    private fun selectSize(n: Int) {
        if (n == size) return
        saveAsync()
        size = n
        settings.lastSize = n
        game.load(n, loadSave(n))
        SolverService.prewarm(n)
        updateMenu()
        cubeView.requestRender()
    }

    private fun updateMenu() {
        game.hud(hud)
        sizeLabel.text = getString(R.string.size_format, size)
        sizeName.text = sizeTitle(size)
        val progress = game.hasProgress()
        val best = stats.bestMs(size)
        sizeInfo.text = when {
            progress -> resources.getQuantityString(R.plurals.continue_format, hud.moves, hud.moves)
            best > 0 -> stats.solves(size).let { resources.getQuantityString(R.plurals.best_format, it, TimerView.format(best), it) }
            else -> getString(R.string.no_solves)
        }
        btnPlay.setText(if (progress) R.string.continue_game else R.string.play)
        btnNew.setText(if (progress) R.string.new_game else R.string.free_play)
        for (i in 0 until sizeStrip.childCount) {
            val chip = sizeStrip.getChildAt(i)
            chip.isSelected = i + CubeGame.MIN_SIZE == size
        }
        // Keep the selected chip centered once the strip has been laid out.
        sizeScroll.post {
            val chip = sizeStrip.getChildAt(size - CubeGame.MIN_SIZE) ?: return@post
            val target = chip.left + chip.width / 2 - sizeScroll.width / 2
            sizeScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun sizeTitle(n: Int): String = getString(
        when (n) {
            2 -> R.string.tier_mini
            3 -> R.string.tier_classic
            4, 5 -> R.string.tier_advanced
            6, 7 -> R.string.tier_expert
            in 8..10 -> R.string.tier_master
            in 11..14 -> R.string.tier_grandmaster
            else -> R.string.tier_legend
        },
    )

    /** Tells the camera which part of the screen is covered by UI so the cube fits the rest. */
    private fun updateCubeInsets(animate: Boolean) {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        var left = 0f
        var right = 0f
        val top: Float
        val bottom: Float
        if (inGame) {
            if (gameBottom.height == 0) return
            // In landscape the status pill may overlap the cube's top edge; height is precious.
            top = if (landscape) (gameTop.top + gameTopRow.bottom).toFloat() else gameTop.bottom.toFloat()
            if (landscape) {
                left = gameLayer.paddingLeft.toFloat()
                right = (w - gameBottom.left).toFloat()
                bottom = gameLayer.paddingBottom.toFloat()
            } else {
                bottom = (h - gameBottom.top).toFloat()
            }
        } else {
            if (menuBottom.height == 0) return
            if (landscape) {
                left = menuLayer.paddingLeft.toFloat()
                right = (w - menuColumn.left).toFloat()
                top = menuLayer.paddingTop.toFloat()
                bottom = menuLayer.paddingBottom.toFloat()
            } else {
                top = (menuColumn.top + menuTop.bottom).toFloat()
                bottom = (h - menuColumn.top - menuBottom.top).toFloat()
            }
        }
        game.setInsets(left, top, right, bottom, animate)
        cubeView.requestRender()
    }

    // ------------------------------------------------------------------ dialogs & panels

    private fun confirmSolve() {
        game.hud(hud)
        val timedAttempt = hud.status == CubeGame.ST_SOLVING && hud.moves > 0
        if (timedAttempt) {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_solve_title)
                .setMessage(R.string.confirm_solve_message)
                .setPositiveButton(R.string.confirm_solve_ok) { _, _ -> doSolve() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            doSolve()
        }
    }

    private fun doSolve() {
        if (!game.autoSolve()) showTransient(getString(R.string.status_already_solved))
        cubeView.requestRender()
    }

    private fun confirmScramble() {
        game.hud(hud)
        if (hud.status == CubeGame.ST_SOLVING && hud.moves > 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_scramble_title)
                .setMessage(R.string.confirm_scramble_message)
                .setPositiveButton(R.string.confirm_scramble_ok) { _, _ -> scramble() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            scramble()
        }
    }

    private fun scramble() {
        hideSolved(immediate = true)
        game.newGame()
        cubeView.requestRender()
    }

    private fun openPanel(title: String, build: (LinearLayout) -> Unit) {
        panelTitle.text = title
        panelContent.removeAllViews()
        build(panelContent)
        val lp = panelCard.layoutParams
        lp.width = min(root.width - (32 * density).toInt(), (460 * density).toInt())
        panelCard.layoutParams = lp
        panelOpen = true
        panelLayer.animate().cancel()
        panelLayer.alpha = 0f
        panelLayer.visibility = View.VISIBLE
        panelLayer.animate().alpha(1f).setDuration(180).start()
        panelCard.scaleX = 0.94f
        panelCard.scaleY = 0.94f
        panelCard.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        updateBack()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        panelLayer.animate().cancel()
        panelLayer.animate().alpha(0f).setDuration(160).withEndAction { panelLayer.visibility = View.GONE }.start()
        updateBack()
        if (!inGame) updateMenu()
    }

    private fun buildHelp(c: LinearLayout) {
        c.addView(TextView(this).apply {
            setTextAppearance(R.style.BodyText)
            text = getString(R.string.help_text)
        })
        c.addView(privacyNote())
    }

    /** About page: app info, the developer's profiles, source code, privacy and credits. */
    private fun buildAbout(c: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams((60 * density).toInt(), (60 * density).toInt()))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), 0, 0, 0)
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = getString(R.string.about_version, versionName())
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 12f
            })
        })
        c.addView(header)
        c.addView(TextView(this).apply {
            setTextAppearance(R.style.BodyText)
            text = getString(R.string.about_tagline)
            setPadding(0, (14 * density).toInt(), 0, 0)
        })

        val github = getString(R.string.about_github_user)
        val x = getString(R.string.about_x_user)
        val repo = getString(R.string.about_repo_name)
        c.addView(sectionLabel(R.string.about_developer))
        c.addView(smallText(getString(R.string.about_made_by, getString(R.string.about_author))))
        c.addView(linkRow(R.drawable.ic_github, getString(R.string.about_github), "github.com/$github", "https://github.com/$github"))
        c.addView(linkRow(R.drawable.ic_x, getString(R.string.about_x), "@$x", "https://x.com/$x"))

        c.addView(sectionLabel(R.string.about_source))
        c.addView(linkRow(R.drawable.ic_code, "github.com/$github/$repo", getString(R.string.about_source_detail), "https://github.com/$github/$repo"))

        c.addView(sectionLabel(R.string.about_privacy))
        c.addView(smallText(getString(R.string.privacy_note)))
        c.addView(smallText(getString(R.string.about_links_note)).apply { setPadding(0, (8 * density).toInt(), 0, 0) })

        c.addView(sectionLabel(R.string.about_credits))
        c.addView(smallText(getString(R.string.about_credits_text)))
    }

    /** Read from the installed package (no generated BuildConfig class, which keeps builds reproducible). */
    private fun versionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun smallText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(getColor(R.color.text_secondary))
        textSize = 13f
        setLineSpacing(0f, 1.15f)
    }

    /** Tappable row with an icon, a title, a detail line and an "opens externally" marker. */
    private fun linkRow(icon: Int, title: String, detail: String, url: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_link_row)
            val h = (14 * density).toInt()
            val v = (11 * density).toInt()
            setPadding(h, v, h, v)
            minimumHeight = (56 * density).toInt()
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $detail"
            setOnClickListener { openLink(url) }
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), 0, (8 * density).toInt(), 0)
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
            })
            addView(TextView(context).apply {
                text = detail
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_open_in_new)
            alpha = 0.55f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * density).toInt()
        }
        return row
    }

    /** Hands the link to the user's browser; the app itself has no internet permission. */
    private fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun privacyNote(): View = TextView(this).apply {
        text = getString(R.string.privacy_note)
        setTextColor(getColor(R.color.text_tertiary))
        textSize = 12f
        setPadding(0, (18 * density).toInt(), 0, 0)
    }

    private fun buildStats(c: LinearLayout) {
        var any = false
        for (n in CubeGame.MIN_SIZE..CubeGame.MAX_SIZE) {
            val s = stats.summary(n) ?: continue
            any = true
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_stat_card)
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            header.addView(TextView(this).apply {
                text = getString(R.string.size_format, n)
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(this).apply {
                text = resources.getQuantityString(R.plurals.solves_count, s.solves, s.solves)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 13f
            })
            card.addView(header)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * density).toInt(), 0, 0)
            }
            row.addView(statCell(TimerView.format(s.bestMs), getString(R.string.stats_best)), cellParams())
            row.addView(statCell(TimerView.format(s.averageMs), getString(R.string.stats_average)), cellParams())
            row.addView(statCell(if (s.ao5Ms >= 0) TimerView.format(s.ao5Ms) else "–", getString(R.string.stats_ao5)), cellParams())
            row.addView(statCell(if (s.fewestMoves >= 0) s.fewestMoves.toString() else "–", getString(R.string.stats_fewest)), cellParams())
            card.addView(row)
            val recent = stats.recent(n).take(5)
            if (recent.size > 1) {
                card.addView(TextView(this).apply {
                    text = getString(R.string.stats_recent, recent.joinToString("  ·  ") { TimerView.format(it) })
                    setTextColor(getColor(R.color.text_tertiary))
                    textSize = 12f
                    fontFeatureSettings = "tnum"
                    setPadding(0, (10 * density).toInt(), 0, 0)
                })
            }
            c.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            })
        }
        if (!any) {
            c.addView(TextView(this).apply {
                setTextAppearance(R.style.BodyText)
                text = getString(R.string.stats_empty)
                gravity = Gravity.CENTER
                val p = (24 * density).toInt()
                setPadding(0, p, 0, p)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun statCell(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 15f
            fontFeatureSettings = "tnum"
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(getColor(R.color.text_tertiary))
            textSize = 11f
        })
    }

    private fun cellParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun buildSettings(c: LinearLayout) {
        c.addView(sectionLabel(R.string.settings_colors))
        c.addView(previewRow(Palettes.SCHEMES.size, settings.palette, { Palettes.SCHEME_NAMES[it] }, {
            SwatchView(this, Palettes.SCHEMES[it], Palettes.BODIES[settings.body])
        }) {
            settings.palette = it
            applySettings()
        })

        c.addView(sectionLabel(R.string.settings_tiles))
        c.addView(chipRow(listOf(getString(R.string.tiles_stickerless), getString(R.string.tiles_stickers)), settings.tiles) {
            settings.tiles = it
            applySettings()
        })

        c.addView(sectionLabel(R.string.settings_body))
        c.addView(chipRow(Palettes.BODY_NAMES.toList(), settings.body) {
            settings.body = it
            applySettings()
        })

        c.addView(sectionLabel(R.string.settings_background))
        c.addView(previewRow(Palettes.BACKGROUNDS.size, settings.background, { Palettes.BACKGROUND_NAMES[it] }, {
            gradientPreview(Palettes.BACKGROUNDS[it])
        }) {
            settings.background = it
            applySettings()
        })

        c.addView(sectionLabel(R.string.settings_speed))
        val speeds = listOf(
            getString(R.string.speed_slow), getString(R.string.speed_normal),
            getString(R.string.speed_fast), getString(R.string.speed_instant),
        )
        c.addView(chipRow(speeds, settings.speed) {
            settings.speed = it
            applySettings()
        })

        c.addView(sectionLabel(0))
        c.addView(switchRow(R.string.settings_inspection, settings.inspection) {
            settings.inspection = it
            applySettings()
        })
        c.addView(switchRow(R.string.settings_sound, settings.sound) {
            settings.sound = it
            applySettings()
        })
        c.addView(switchRow(R.string.settings_haptics, settings.haptics) {
            settings.haptics = it
            applySettings()
        })
        c.addView(switchRow(R.string.settings_timer, settings.showTimer) {
            settings.showTimer = it
            applySettings()
        })

        c.addView(Button(this).apply {
            setText(R.string.settings_reset_stats)
            setTextAppearance(R.style.SecondaryButton)
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_button_secondary)
            setTextColor(Color.WHITE)
            stateListAnimator = null
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.confirm_reset_title)
                    .setMessage(R.string.confirm_reset_message)
                    .setPositiveButton(R.string.confirm_reset_ok) { _, _ -> stats.reset() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
            topMargin = (20 * density).toInt()
        })
        c.addView(privacyNote())
    }

    private fun sectionLabel(res: Int): View = TextView(this).apply {
        setTextAppearance(R.style.SectionLabel)
        if (res != 0) text = getString(res)
        setPadding(0, (18 * density).toInt(), 0, (8 * density).toInt())
    }

    /** Row of selectable cards, each with a small preview and a label. */
    private fun previewRow(
        count: Int,
        selected: Int,
        label: (Int) -> String,
        preview: (Int) -> View,
        onSelect: (Int) -> Unit,
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until count) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.bg_chip)
                val p = (8 * density).toInt()
                setPadding(p / 2, p, p / 2, p)
                isSelected = selected == i
                addView(preview(i))
                addView(TextView(context).apply {
                    text = label(i)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * density).toInt(), 0, 0)
                    maxLines = 1
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            item.setOnClickListener {
                selectOnly(row, item)
                onSelect(i)
            }
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = (8 * density).toInt()
            })
        }
        return row
    }

    private fun gradientPreview(colors: IntArray): View = View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF000000.toInt() or colors[0], 0xFF000000.toInt() or colors[1]),
        ).apply { cornerRadius = 6 * density }
        layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), (38 * density).toInt())
    }

    private fun chipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                setBackgroundResource(R.drawable.bg_chip)
                minHeight = (40 * density).toInt()
                isSelected = i == selected
                maxLines = 1
            }
            chip.setOnClickListener {
                selectOnly(row, chip)
                onSelect(i)
            }
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = (8 * density).toInt()
            })
        }
        return row
    }

    private fun selectOnly(group: LinearLayout, selected: View) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isSelected = child === selected
        }
    }

    private fun switchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * density).toInt()
        }
        row.addView(TextView(this).apply {
            setText(label)
            setTextColor(Color.WHITE)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    // ------------------------------------------------------------------ helpers

    private fun loadSave(n: Int): GameSave? = saveCache[n] ?: saves.load(n)

    private fun saveAsync() {
        val snap = game.snapshot()
        saveCache[snap.n] = snap
        io.execute { saves.save(snap) }
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    private fun fade(v: View, show: Boolean) {
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).setDuration(220).start()
        } else {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    /** API 33+ predictive-back registration, isolated so older devices never load these classes. */
    @SuppressLint("UseRequiresApi") // androidx.annotation is deliberately not a dependency
    @TargetApi(33)
    private object BackApi33 {
        fun register(activity: Activity, onBack: () -> Unit): Any {
            val cb = OnBackInvokedCallback { onBack() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            return cb
        }

        fun unregister(activity: Activity, cb: Any) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb as OnBackInvokedCallback)
        }
    }
}
