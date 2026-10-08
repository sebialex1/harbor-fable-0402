package io.harbor.fable.display

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.winlator.widget.XServerView
import io.harbor.fable.display.controls.ControlInput
import io.harbor.fable.display.controls.ControlProfileRepository
import io.harbor.fable.display.controls.InputControlsView
import io.harbor.fable.display.controls.InputTarget
import io.harbor.fable.display.controls.StoredProfile
import io.harbor.fable.display.controls.TouchpadController
import io.harbor.fable.R
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.WindowsProcess
import io.harbor.fable.data.WindowsTasks
import io.harbor.fable.data.WineFailure
import io.harbor.fable.data.models.HudSettings
import com.winlator.xserver.Pointer
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager as XWindowManager
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the X server's screen (Wine's virtual desktop) full screen, like Winlator's
 * `XServerDisplayActivity`: an [XServerView] (GLSurfaceView + Winlator's GLRenderer) attached to
 * the running [DisplayServer].
 *
 * Touch input goes through one full-screen [InputControlsView]: the screen works like a laptop
 * trackpad — dragging a finger moves the cursor relatively, a quick tap is a left click, a second
 * trackpad finger is a right click — and, when shown, the on-screen controls take the fingers
 * that land on them (see [createControlsOverlay]). Hardware keyboard events go through
 * Winlator's `Keyboard.onKeyEvent`. The activity finishing (for any reason) stops the container: its Wine
 * processes and the X server.
 *
 * Back doesn't finish the activity any more — a game that catches Escape on a phone's back
 * gesture would otherwise be killed by every accidental swipe. It opens a side menu (see
 * [createDrawer]) that slides in from the right edge over the X screen: on-screen controls
 * (Winlator-compatible control presets — buttons, d-pads, sticks, trackpads — that inject X
 * key and mouse events, see [createControlsOverlay]), the Android soft keyboard,
 * the control preset and the performance HUD, a mini task manager (`wine tasklist` /
 * `taskkill`), pausing / resuming Wine (SIGSTOP / SIGCONT on the prefix's processes) and Stop Wine, which is
 * what Back used to do. A second Back closes the menu.
 *
 * A small performance HUD in the top-left corner shows the display's frame rate (frames the
 * renderer actually drew — it renders on demand, so an idle desktop reads low), the X screen
 * resolution and, when `/proc` allows it, CPU usage.
 *
 * Until Wine maps its first window a "Starting Wine…" label is shown. If the Wine process dies
 * meanwhile (or later) with a failure status, [ContainerRepository.wineFailures] reports why and
 * the screen shows that reason — overlay plus dialog with a Close button — instead of staying
 * black with only the cursor.
 */
class DisplayActivity : Activity() {
    @Volatile private var view: XServerView? = null
    @Volatile private var server: XServer? = null
    private var containerId: String? = null

    // Side menu (Back opens it) and the overlays it toggles.
    private var root: FrameLayout? = null
    private var drawer: View? = null
    private var drawerPanel: View? = null
    private var drawerOpen = false
    private var controlsOverlay: InputControlsView? = null
    private var controlProfiles: List<StoredProfile> = emptyList()
    private var selectedProfile: StoredProfile? = null
    private var controlsSubtitle: TextView? = null
    private var controlsSwitch: Switch? = null
    private var presetList: LinearLayout? = null
    private var presetsLoading = false
    private var taskList: LinearLayout? = null
    private var tasksLoading = false
    private var winePaused = false
    private var pauseItem: TextView? = null
    private var pauseIcon: ImageView? = null

    private val touchSlopPx by lazy { TOUCH_SLOP_DP * resources.displayMetrics.density }

    // Startup status and failure reporting.
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusText: TextView? = null
    private var errorPanel: View? = null
    private var failureDialog: AlertDialog? = null
    private var shownFailure: WineFailure? = null
    private var windowListener: XWindowManager.OnWindowModificationListener? = null

    // Performance HUD. Sampling (frame counter, /proc reads) runs on its own thread; the text
    // is posted back to the main thread.
    private var hudText: TextView? = null
    private var hudThread: HandlerThread? = null
    private var hudHandler: Handler? = null
    private var hudLastSampleMs = 0L
    /** This container's overlay settings (container screen → Settings → Performance Overlay). */
    private var hudSettings = HudSettings()
    private val cpuSampler = CpuSampler()
    private val hudUpdate = object : Runnable {
        override fun run() {
            val text = runCatching { buildHudText() }.getOrNull()
            if (text != null) hudText?.let { hud -> hud.post { hud.text = text } }
            hudHandler?.postDelayed(this, HUD_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        containerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
        val xServer = DisplayServer.xServer
        if (xServer == null) {
            Toast.makeText(this, "Display isn't running", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Textures of a previous display screen died with its GL context.
        runCatching {
            xServer.lock(XServer.Lockable.DRAWABLE_MANAGER).use { xServer.drawableManager.invalidateTextures() }
        }
        val created = runCatching { XServerView(this, xServer) }.getOrElse {
            Toast.makeText(this, "Display couldn't start: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        xServer.renderer = created.renderer
        hudSettings = containerId?.let { id ->
            runCatching { ContainerRepository.get(applicationContext).containers.value.firstOrNull { it.id == id }?.hud }.getOrNull()
        } ?: HudSettings()
        val hud = createHud(xServer)
        val status = createStatusText()
        val controls = createControlsOverlay(xServer)
        val menu = createDrawer()
        val content = FrameLayout(this).apply {
            // Takes focus so the soft keyboard has a target; its key events come in through
            // dispatchKeyEvent like a hardware keyboard's.
            isFocusable = true
            isFocusableInTouchMode = true
            addView(created)
            addView(status)
            addView(controls)
            // Above the controls overlay: a finger that lands on the HUD drags (or taps) it,
            // every other finger still reaches the overlay (touchpad and controls). Split
            // touch dispatch (on by default, set explicitly here) gives each view its own
            // fingers, so dragging the HUD and steering the cursor work at the same time.
            addView(hud)
            addView(menu)
            isMotionEventSplittingEnabled = true
            // Re-place the HUD when the screen changes size (rotation, multi-window).
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) placeHud()
            }
        }
        setContentView(content)
        root = content
        view = created
        hudText = hud
        statusText = status
        controlsOverlay = controls
        drawer = menu
        server = xServer
        hideSystemBars()
        watchFirstWindow(xServer)
        observeWineFailures()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        view?.onResume()
        startHud()
    }

    override fun onPause() {
        stopHud()
        view?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        failureDialog?.dismiss()
        failureDialog = null
        server?.let { xServer -> windowListener?.let { listener -> removeWindowListener(xServer, listener) } }
        windowListener = null
        view?.renderer?.release()
        view = null
        server = null
        hudText = null
        statusText = null
        errorPanel = null
        root = null
        drawer = null
        drawerPanel = null
        controlsOverlay?.releaseAll()
        controlsOverlay = null
        controlsSubtitle = null
        controlsSwitch = null
        presetList = null
        taskList = null
        pauseItem = null
        pauseIcon = null
        // Only a real exit stops Wine; a recreate (config change not covered by the manifest)
        // comes straight back to the same display. A paused Wine is killed just the same
        // (SIGKILL is delivered to stopped processes).
        if (isFinishing && !isChangingConfigurations) stopContainer()
        super.onDestroy()
    }

    /** Ends the container's Wine processes and the X server, off the main thread. */
    private fun stopContainer() {
        val id = containerId
        if (id == null) {
            // Nothing to attribute the processes to; still tear the display down.
            runCatching { DisplayServer.stop() }
            return
        }
        containerId = null
        runCatching { ContainerRepository.get(applicationContext).stopContainerInBackground(id) }
            .onFailure { Log.e(TAG, "Couldn't stop container $id", it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Back toggles the side menu and never reaches the framework (which would finish the
            // activity and stop Wine). Acting on UP avoids re-triggering on key repeat.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) setDrawerOpen(!drawerOpen)
            return true
        }
        val handled = runCatching { server?.keyboard?.onKeyEvent(event) == true }.getOrDefault(false)
        return handled || super.dispatchKeyEvent(event)
    }

    // ---- Side menu ---------------------------------------------------------------------------

    private fun setDrawerOpen(open: Boolean) {
        val scrim = drawer ?: return
        val panel = drawerPanel ?: return
        if (open == drawerOpen) return
        drawerOpen = open
        // Slide distance: the panel's width plus its margin, once laid out; the screen's before.
        val offscreen = (panel.width.takeIf { it > 0 }?.plus(dpPx(DRAWER_MARGIN_DP)) ?: resources.displayMetrics.widthPixels).toFloat()
        scrim.animate().cancel()
        panel.animate().cancel()
        if (open) {
            // Fresh lists each time: presets dropped into the import folders, programs started or ended.
            reloadPresets()
            refreshTasks()
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(DRAWER_ANIMATION_MS).setInterpolator(DRAWER_EASE_OUT).start()
            // Glides in from the right edge while it fades up and settles from a slight scale,
            // like a glass sheet sliding over the game.
            panel.translationX = offscreen
            panel.alpha = 0.4f
            panel.scaleX = 0.97f
            panel.scaleY = 0.97f
            panel.animate()
                .translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(DRAWER_ANIMATION_MS)
                .setInterpolator(DRAWER_EASE_OUT)
                .start()
        } else {
            panel.animate()
                .translationX(offscreen).alpha(0.4f).scaleX(0.97f).scaleY(0.97f)
                .setDuration(DRAWER_CLOSE_MS)
                .setInterpolator(DRAWER_EASE_IN)
                .start()
            scrim.animate().alpha(0f).setDuration(DRAWER_CLOSE_MS).setInterpolator(DRAWER_EASE_IN)
                .withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }
                .start()
        }
    }

    private fun dpPx(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun setControlsShown(shown: Boolean) {
        controlsOverlay?.controlsShown = shown
    }

    /**
     * Opens Android's soft keyboard over the X screen; Android Back dismisses it. The content view has no
     * InputConnection, so the IME falls back to sending plain key events, which
     * [dispatchKeyEvent] forwards to the X server like a hardware keyboard's. (Winlator does the
     * same with `toggleSoftInput`.)
     */
    private fun showKeyboard() {
        val target = root ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        target.requestFocus()
        @Suppress("DEPRECATION")
        imm.showSoftInput(target, InputMethodManager.SHOW_FORCED)
    }

    /** Freezes / thaws the container's Wine processes; the status label says so while paused. */
    private fun setWinePaused(paused: Boolean) {
        val id = containerId ?: return
        val repository = runCatching { ContainerRepository.get(applicationContext) }.getOrNull() ?: return
        winePaused = paused
        repository.setPausedInBackground(id, paused)
        pauseItem?.text = if (paused) "Resume Wine" else "Pause Wine"
        pauseIcon?.setImageDrawable(menuIcon(if (paused) R.drawable.ic_menu_play else R.drawable.ic_menu_pause))
        statusText?.let { label ->
            if (paused) {
                label.text = "Paused"
                label.visibility = View.VISIBLE
            } else {
                label.visibility = View.GONE
            }
        }
    }

    /**
     * The side menu: a light tap-to-close scrim over the whole screen with a floating glass panel
     * on the right — translucent so the game stays visible behind it, with a light-catching rim
     * and no branding header. A compact close button sits at the top, then short sections on
     * glass cards — Overlays (on-screen controls, performance HUD, keyboard), Control preset
     * (every preset, tap to switch live; export), Wine (pause) and Tasks (the container's Windows
     * processes with an End button each, refreshed whenever the menu opens) — and a neutral
     * glass Exit button pinned at the bottom.
     * Hidden until Back opens it ([setDrawerOpen]).
     *
     * Touch handling is unchanged: while the menu is closed the scrim is GONE, so every touch
     * reaches the X server view underneath; while it is open the scrim takes taps outside the
     * panel (closing the menu) and the panel swallows its own, so nothing leaks into the game.
     */
    private fun createDrawer(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        // Top row: a small caption on the left, close on the right. No app name.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@DisplayActivity).apply {
                setTextColor(DRAWER_TEXT_DIM.toInt())
                textSize = 12f
                typeface = fableFont(R.font.inter_medium)
                letterSpacing = 0.06f
                isAllCaps = true
                text = "Menu"
                setPadding(dp(4f), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(FrameLayout(this@DisplayActivity).apply {
                background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = 16f)
                contentDescription = "Close menu"
                isClickable = true
                setOnClickListener { setDrawerOpen(false) }
                addView(ImageView(this@DisplayActivity).apply {
                    setImageDrawable(menuIcon(R.drawable.ic_menu_close, DRAWER_TEXT_SOFT.toInt()))
                }, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        }

        val sections = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuSectionLabel("Overlays", first = true))
            addView(menuCard(
                menuSwitch("On-screen controls", R.drawable.ic_menu_gamepad, selectedProfile?.profile?.name ?: "Loading presets…", TONE_BLUE) {
                    setControlsShown(it)
                }.also {
                    controlsSubtitle = it.findViewById(ROW_SUBTITLE_ID)
                    controlsSwitch = it.findViewById(ROW_SWITCH_ID)
                },
                menuDivider(),
                menuSwitch(
                    "Performance HUD", R.drawable.ic_menu_hud, "FPS, resolution and CPU", TONE_TEAL,
                    checked = hudSettings.enabled && !hudSettings.isEmpty,
                ) { setHudShown(it) },
                menuDivider(),
                menuItem("Keyboard", R.drawable.ic_menu_keyboard, "Type into Wine", TONE_INDIGO) {
                    setDrawerOpen(false)
                    showKeyboard()
                }.also { it.contentDescription = "Open Android's keyboard to type into Wine" },
            ))

            // Control presets: one row per preset, tap to apply (see [renderPresets]).
            addView(menuSectionLabel("Control preset"))
            addView(menuCard(
                LinearLayout(this@DisplayActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    presetList = this
                },
                menuDivider(),
                menuItem("Export preset", R.drawable.ic_menu_gamepad, "Save as .icp to Download/Fable/profiles", TONE_SLATE) {
                    exportSelectedPreset()
                },
            ))
            addView(menuHint("Import: copy Winlator .icp files to Android/data/$packageName/files/profiles or Download/Winlator/profiles."))

            addView(menuSectionLabel("Wine"))
            addView(menuCard(
                menuItem("Pause Wine", R.drawable.ic_menu_pause, "Freeze every process", TONE_TEAL) {
                    setWinePaused(!winePaused)
                    setDrawerOpen(false)
                }.also {
                    pauseItem = it.findViewById(ROW_TITLE_ID)
                    pauseIcon = it.findViewById(ROW_ICON_ID)
                },
            ))

            // Mini task manager: the container's Windows processes, each with an End button.
            addView(menuSectionLabel("Tasks"))
            addView(menuCard(
                menuItem("Refresh", R.drawable.ic_menu_refresh, "Running Windows programs", TONE_INDIGO) { refreshTasks() },
                LinearLayout(this@DisplayActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    taskList = this
                },
            ))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(DRAWER_GLASS_TOP.toInt(), DRAWER_GLASS_BOTTOM.toInt()),
            ).apply {
                cornerRadius = DRAWER_RADIUS_DP * density
                setStroke(dp(1f).coerceAtLeast(1), DRAWER_RIM.toInt())
            }
            elevation = 0f
            isClickable = true
            addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Landscape screens can be shorter than the grouped rows. Keep Exit outside the
            // scrollable content so it never gets clipped or pushed off the bottom.
            addView(ScrollView(this@DisplayActivity).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(sections)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Exit: a neutral glass button, not a red slab. The power glyph and the words say
            // what it does.
            addView(LinearLayout(this@DisplayActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = MENU_CARD_RADIUS_DP)
                isClickable = true
                contentDescription = "Exit container and stop all Wine processes"
                // onDestroy calls stopContainerInBackground, including for paused Wine.
                setOnClickListener { finish() }
                addView(ImageView(this@DisplayActivity).apply {
                    setImageDrawable(menuIcon(R.drawable.ic_menu_power, DRAWER_TEXT_SOFT.toInt()))
                }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) })
                addView(TextView(this@DisplayActivity).apply {
                    text = "Exit Container"
                    textSize = 15f
                    typeface = fableFont(R.font.inter_medium)
                    setTextColor(0xFFFFFFFF.toInt())
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)).apply {
                topMargin = dp(12f)
            })
        }
        drawerPanel = panel
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // A soft edge shade rather than a flat dim: darker behind the panel, clear on the left,
            // so the game stays readable while the menu is up.
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(DRAWER_SCRIM_CLEAR.toInt(), DRAWER_SCRIM.toInt()),
            )
            visibility = View.GONE
            // Swallows touches so they don't reach the X server; a tap outside the panel closes.
            isClickable = true
            setOnClickListener { setDrawerOpen(false) }
            addView(panel, FrameLayout.LayoutParams(dp(DRAWER_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                val m = dp(DRAWER_MARGIN_DP)
                setMargins(m, m, m, m)
            })
        }
    }

    /** Inter from `res/font`, the same face the Compose screens use; the system face if it fails. */
    private fun fableFont(resId: Int): Typeface =
        runCatching { resources.getFont(resId) }.getOrNull() ?: Typeface.DEFAULT

    private fun menuIcon(resId: Int, tint: Int = 0xFFFFFFFF.toInt()): Drawable? {
        val density = resources.displayMetrics.density
        return runCatching {
            getDrawable(resId)?.mutate()?.apply {
                setTint(tint)
                val size = (MENU_ICON_DP * density).toInt()
                setBounds(0, 0, size, size)
            }
        }.getOrNull()
    }

    /** Translucent glass fill with a hairline rim that brightens while pressed. */
    private fun glassPill(fill: Long, pressedFill: Long, cornerDp: Float): Drawable {
        val density = resources.displayMetrics.density
        fun shape(color: Long) = GradientDrawable().apply {
            setColor(color.toInt())
            cornerRadius = cornerDp * density
            setStroke((0.75f * density).toInt().coerceAtLeast(1), DRAWER_RIM.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedFill))
            addState(intArrayOf(), shape(fill))
        }
    }

    /** Small grey uppercase caption above a card, like the Compose `SectionLabel`. */
    private fun menuSectionLabel(label: String, first: Boolean = false): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(DRAWER_TEXT_DIM.toInt())
            textSize = 12f
            typeface = fableFont(R.font.inter_medium)
            letterSpacing = 0.04f
            isAllCaps = true
            text = label
            setPadding(dp(12f), dp(if (first) 14f else 18f), dp(12f), dp(6f))
        }
    }

    /** A grouped card: a translucent glass surface with rounded corners holding the given rows. */
    private fun menuCard(vararg rows: View): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(DRAWER_CARD.toInt())
                cornerRadius = MENU_CARD_RADIUS_DP * density
                setStroke((0.75f * density).toInt().coerceAtLeast(1), DRAWER_CARD_RIM.toInt())
            }
            clipToOutline = true
            rows.forEach { addView(it) }
        }
    }

    /**
     * Gradient icon tile + title (+ subtitle) shared by action and switch rows. [tone] is the
     * tile's two gradient stops, the same deep tones the Compose tiles use (TileTone).
     */
    private fun menuRowContent(label: String, subtitle: String?, iconRes: Int, tone: IntArray): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val tile = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, tone).apply {
                cornerRadius = 9f * density
                setStroke((0.75f * density).toInt().coerceAtLeast(1), DRAWER_RIM.toInt())
            }
            addView(
                ImageView(this@DisplayActivity).apply {
                    id = ROW_ICON_ID
                    setImageDrawable(menuIcon(iconRes))
                },
                FrameLayout.LayoutParams(dp(MENU_ICON_DP), dp(MENU_ICON_DP), Gravity.CENTER),
            )
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DisplayActivity).apply {
                id = ROW_TITLE_ID
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                typeface = fableFont(R.font.inter_regular)
                letterSpacing = -0.01f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                text = label
            })
            if (subtitle != null) {
                addView(TextView(this@DisplayActivity).apply {
                    setTextColor(DRAWER_TEXT_DIM.toInt())
                    textSize = 12f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    id = ROW_SUBTITLE_ID
                    text = subtitle
                })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tile, LinearLayout.LayoutParams(dp(MENU_TILE_DP), dp(MENU_TILE_DP)).apply { marginEnd = dp(12f) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** A menu row: icon tile + label, highlighted while pressed. Its title view has [ROW_TITLE_ID]. */
    private fun menuItem(label: String, iconRes: Int, subtitle: String?, tone: IntArray, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_MIN_DP)
            setPadding(dp(10f), dp(8f), dp(12f), dp(8f))
            background = menuRowBackground()
            addView(menuRowContent(label, subtitle, iconRes, tone), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }
    }

    /** A menu row with a switch ([ROW_SWITCH_ID]) on the right, starting at [checked]; [onChange] gets the new state. */
    private fun menuSwitch(
        label: String,
        iconRes: Int,
        subtitle: String?,
        tone: IntArray,
        checked: Boolean = false,
        onChange: (Boolean) -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = Switch(this).apply {
            // Fable's switch colours: blue track when on, a faint glass track when off.
            thumbTintList = ColorStateList(checkedState, intArrayOf(0xFFFFFFFF.toInt(), 0xFFD1D1D6.toInt()))
            trackTintList = ColorStateList(checkedState, intArrayOf(SWITCH_ON_TRACK.toInt(), SWITCH_OFF_TRACK.toInt()))
            id = ROW_SWITCH_ID
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_MIN_DP)
            setPadding(dp(10f), dp(8f), dp(8f), dp(8f))
            background = menuRowBackground()
            addView(menuRowContent(label, subtitle, iconRes, tone), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
            // The whole row flips the switch, not just the small knob.
            setOnClickListener { toggle.toggle() }
        }
    }

    /** Hairline between rows of a card, inset past the icon tile like a grouped list's. */
    private fun menuDivider(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (0.75f * density).toInt().coerceAtLeast(1)).apply {
                setMargins(dp(10f + MENU_TILE_DP + 12f), 0, 0, 0)
            }
            setBackgroundColor(DRAWER_DIVIDER.toInt())
        }
    }

    private fun menuRowBackground(): Drawable {
        val pressed = ColorDrawable(DRAWER_ROW_PRESSED.toInt())
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), ColorDrawable(0))
        }
    }

    // ---- On-screen controls ------------------------------------------------------------------

    /**
     * The on-screen controls: an [InputControlsView] showing the chosen control preset (a
     * Winlator-format profile, see [ControlProfileRepository]). Fable's own pad — a d-pad for
     * the arrow keys and Enter / Escape / Space / Shift face buttons — is the default preset.
     *
     * The view covers the whole X screen and owns all touch input: each finger goes to the
     * control element it lands on, or else to the [TouchpadController] (cursor moves, tap =
     * left click, second trackpad finger = right click). Fingers on controls never reach the
     * trackpad and vice versa, so buttons and the mouse work at the same time. The controls
     * are hidden until the side menu turns them on; presets load off the main thread.
     */
    private fun createControlsOverlay(xServer: XServer): InputControlsView {
        val target = object : InputTarget {
            override fun keyDown(key: XKeycode) { runCatching { xServer.injectKeyPress(key) } }
            override fun keyUp(key: XKeycode) { runCatching { xServer.injectKeyRelease(key) } }
            override fun buttonDown(button: Pointer.Button) { runCatching { xServer.injectPointerButtonPress(button) } }
            override fun buttonUp(button: Pointer.Button) { runCatching { xServer.injectPointerButtonRelease(button) } }
            override fun moveBy(dx: Int, dy: Int) { runCatching { xServer.injectPointerMoveDelta(dx, dy) } }
        }
        val overlay = InputControlsView(this, ControlInput(target)).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Always up: it routes every finger, to a control or to the trackpad.
            controlsShown = false
            touchpad = TouchpadController(target, touchSlopPx, TAP_TIMEOUT_MS) {
                // Screen pixels -> X server pixels.
                val aspect = view?.renderer?.viewTransformation?.aspect ?: 0f
                if (aspect > 0f) SENSITIVITY / aspect else 0f
            }
        }
        controlsOverlay = overlay
        reloadPresets()
        return overlay
    }

    private val profileRepository by lazy { ControlProfileRepository(applicationContext) }

    /**
     * Re-reads the presets (built-ins, saved, newly imported) off the main thread and shows the
     * chosen one. The overlay only changes when the chosen preset's content did.
     */
    private fun reloadPresets() {
        if (presetsLoading) return
        presetsLoading = true
        uiScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { profileRepository.load() }.getOrDefault(emptyList()) }
            presetsLoading = false
            controlProfiles = loaded
            val chosen = profileRepository.selected(loaded, containerId)
            if (chosen?.profile != controlsOverlay?.currentProfile) controlsOverlay?.setProfile(chosen?.profile)
            selectedProfile = chosen
            controlsSubtitle?.text = chosen?.profile?.name ?: "No presets found"
            renderPresets()
        }
    }

    /** Switches the on-screen controls to [preset] right away and remembers it for this container. */
    private fun applyPreset(preset: StoredProfile) {
        selectedProfile = preset
        controlsOverlay?.setProfile(preset.profile)
        profileRepository.select(preset, containerId)
        controlsSubtitle?.text = preset.profile.name
        // Picking a preset means wanting to see it.
        controlsSwitch?.let { if (!it.isChecked) it.isChecked = true }
        renderPresets()
    }

    /** One row per preset in the side menu, the active one ticked. */
    private fun renderPresets() {
        val list = presetList ?: return
        list.removeAllViews()
        if (controlProfiles.isEmpty()) {
            list.addView(menuInfoRow(if (presetsLoading) "Loading presets…" else "No presets found"))
            return
        }
        controlProfiles.forEachIndexed { index, preset ->
            if (index > 0) list.addView(menuDivider())
            val active = preset.file == selectedProfile?.file
            val elements = preset.profile.elements.count { it.isSupported }
            val detail = buildString {
                append(if (preset.builtin) "Built-in" else "Imported")
                append(" · ").append(elements).append(if (elements == 1) " control" else " controls")
            }
            list.addView(menuTextRow(preset.profile.name, detail, trailing = if (active) "\u2713" else null) { applyPreset(preset) })
        }
    }

    private fun exportSelectedPreset() {
        val preset = selectedProfile ?: return
        uiScope.launch {
            val file = withContext(Dispatchers.IO) { profileRepository.export(preset) }
            Toast.makeText(
                this@DisplayActivity,
                if (file != null) "Saved ${file.absolutePath}" else "Couldn't export ${preset.profile.name}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ---- Performance HUD toggle --------------------------------------------------------------

    /**
     * Shows or hides the performance HUD for this session (the container's saved setting is
     * unchanged). Turning it on when the container's HUD shows nothing turns every reading on.
     */
    private fun setHudShown(shown: Boolean) {
        if (shown && hudSettings.isEmpty) hudSettings = hudSettings.copy(showFps = true, showResolution = true, showCpu = true)
        hudSettings = hudSettings.copy(enabled = shown)
        hudText?.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) startHud() else stopHud()
    }

    // ---- Mini task manager -------------------------------------------------------------------

    /**
     * Lists the container's Windows processes ([ContainerRepository.listWindowsProcesses]:
     * `wine tasklist`, or `/proc` as a fallback) into the side menu.
     */
    private fun refreshTasks() {
        val list = taskList ?: return
        val id = containerId ?: return
        if (tasksLoading) return
        list.removeAllViews()
        if (winePaused) {
            // A stopped wineserver can't answer tasklist.
            list.addView(menuInfoRow("Resume Wine to list processes"))
            return
        }
        list.addView(menuInfoRow("Loading…"))
        tasksLoading = true
        uiScope.launch {
            val processes = runCatching { ContainerRepository.get(applicationContext).listWindowsProcesses(id) }.getOrDefault(emptyList())
            tasksLoading = false
            renderTasks(processes)
        }
    }

    private fun renderTasks(processes: List<WindowsProcess>) {
        val list = taskList ?: return
        list.removeAllViews()
        if (processes.isEmpty()) {
            list.addView(menuInfoRow("No Windows processes found"))
            return
        }
        processes.forEach { process ->
            list.addView(menuDivider())
            val system = process.name.lowercase() in WindowsTasks.SYSTEM_PROCESSES
            val detail = buildList {
                process.windowsPid?.let { add("PID $it") } ?: process.linuxPid?.let { add("pid $it") }
                process.memory?.let { add(it) }
                if (system) add("Wine")
            }.joinToString(" · ")
            list.addView(menuTextRow(process.name, detail, endAction = { confirmEndTask(process, system) }))
        }
    }

    private fun confirmEndTask(process: WindowsProcess, system: Boolean) {
        if (!system) {
            endTask(process)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("End ${process.name}?")
            .setMessage("It is part of Wine; ending it can close the desktop or stop the game.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("End") { _, _ -> endTask(process) }
            .show()
    }

    private fun endTask(process: WindowsProcess) {
        val id = containerId ?: return
        uiScope.launch {
            val ended = runCatching { ContainerRepository.get(applicationContext).endWindowsProcess(id, process) }.getOrDefault(false)
            if (!ended) Toast.makeText(this@DisplayActivity, "Couldn't end ${process.name}", Toast.LENGTH_SHORT).show()
            refreshTasks()
        }
    }

    // ---- Side menu building blocks for the lists ---------------------------------------------

    /** A dim one-line note inside a card ("Loading…", "No presets found"). */
    private fun menuInfoRow(text: String): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(this).apply {
            setTextColor(DRAWER_TEXT_DIM.toInt())
            textSize = 13f
            typeface = fableFont(R.font.inter_regular)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            this.text = text
        }
    }

    /** Small explanatory text under a card. */
    private fun menuHint(text: String): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(this).apply {
            setTextColor(DRAWER_TEXT_DIM.toInt())
            textSize = 11f
            typeface = fableFont(R.font.inter_regular)
            setPadding(dp(12f), dp(6f), dp(12f), 0)
            this.text = text
        }
    }

    /**
     * A text-only list row: title and detail, optionally a [trailing] mark (the active preset's
     * tick) and an end button ([endAction], the task manager's End).
     */
    private fun menuTextRow(
        title: String,
        detail: String?,
        trailing: String? = null,
        endAction: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            setPadding(dp(14f), dp(6f), dp(10f), dp(6f))
            addView(LinearLayout(this@DisplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DisplayActivity).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = title
                })
                if (!detail.isNullOrBlank()) addView(TextView(this@DisplayActivity).apply {
                    setTextColor(DRAWER_TEXT_DIM.toInt())
                    textSize = 11f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = detail
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(TextView(this@DisplayActivity).apply {
                setTextColor(SWITCH_ON_TRACK.toInt())
                textSize = 16f
                typeface = fableFont(R.font.inter_medium)
                text = trailing
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
            if (endAction != null) addView(FrameLayout(this@DisplayActivity).apply {
                background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = 14f)
                contentDescription = "End $title"
                isClickable = true
                setOnClickListener { endAction() }
                addView(ImageView(this@DisplayActivity).apply {
                    setImageDrawable(menuIcon(R.drawable.ic_menu_end_task, DRAWER_TEXT_SOFT.toInt()))
                }, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(8f) })
            if (onClick != null) {
                background = menuRowBackground()
                setOnClickListener { onClick() }
            }
        }
    }

    /** Centered "Starting Wine…" label, hidden once Wine maps a window (see [watchFirstWindow]). */
    private fun createStatusText(): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 14f
            val pad = (8f * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            isClickable = false
            isFocusable = false
            text = "Starting Wine\u2026"
        }
    }

    /** Hides the startup label as soon as any top-level X window is mapped (Wine's desktop). */
    private fun watchFirstWindow(xServer: XServer) {
        val alreadyMapped = runCatching {
            xServer.lock(XServer.Lockable.WINDOW_MANAGER).use {
                xServer.windowManager.rootWindow.children.any { it.attributes.isMapped }
            }
        }.getOrDefault(false)
        if (alreadyMapped) {
            statusText?.visibility = View.GONE
            return
        }
        val listener = object : XWindowManager.OnWindowModificationListener {
            override fun onMapWindow(window: Window) {
                statusText?.let { label -> label.post { label.visibility = View.GONE } }
            }
        }
        windowListener = listener
        runCatching {
            xServer.lock(XServer.Lockable.WINDOW_MANAGER).use { xServer.windowManager.addOnWindowModificationListener(listener) }
        }.onFailure { Log.w(TAG, "Couldn't watch for Wine's first window", it) }
    }

    private fun removeWindowListener(xServer: XServer, listener: XWindowManager.OnWindowModificationListener) {
        runCatching {
            xServer.lock(XServer.Lockable.WINDOW_MANAGER).use { xServer.windowManager.removeOnWindowModificationListener(listener) }
        }
    }

    /** Shows [ContainerRepository.wineFailures] for this container as soon as one is reported. */
    private fun observeWineFailures() {
        val id = containerId ?: return
        val repository = runCatching { ContainerRepository.get(applicationContext) }.getOrNull() ?: return
        uiScope.launch {
            repository.wineFailures
                .map { failures -> failures[id] }
                .distinctUntilChanged()
                .collect { failure -> if (failure != null) showFailure(failure) }
        }
    }

    /**
     * Replaces the black screen with why Wine stopped: an on-screen panel (stays up) and a dialog
     * whose Close button leaves the display, which stops the container.
     */
    private fun showFailure(failure: WineFailure) {
        if (isFinishing || isDestroyed || shownFailure == failure) return
        shownFailure = failure
        Log.w(TAG, "Wine failed for ${failure.containerId}: ${failure.reason} (missing: ${failure.missingLibraries})")
        statusText?.visibility = View.GONE
        val title = failureTitle(failure)
        val message = failureMessage(failure)
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? FrameLayout
        errorPanel?.let { root?.removeView(it) }
        val panel = createErrorPanel(title, message)
        root?.addView(panel)
        errorPanel = panel
        failureDialog?.dismiss()
        failureDialog = runCatching {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> closeAfterFailure(failure) }
                .setNegativeButton("Keep screen") { dialog, _ -> dialog.dismiss() }
                .show()
        }.onFailure { Log.w(TAG, "Couldn't show the failure dialog", it) }.getOrNull()
    }

    private fun closeAfterFailure(failure: WineFailure) {
        Toast.makeText(applicationContext, "${failureTitle(failure)}: ${failure.reason}".take(200), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun failureTitle(failure: WineFailure): String =
        if (failure.duringStartup) "${failure.label} couldn't start" else "${failure.label} stopped"

    private fun failureMessage(failure: WineFailure): String = buildString {
        append(failure.reason)
        if (failure.missingLibraries.isNotEmpty()) {
            append("\n\nMissing native libraries: ").append(failure.missingLibraries.joinToString())
        }
        append("\n\nWine ran for ").append(String.format(java.util.Locale.US, "%.1f", failure.uptimeMs / 1000f)).append(" s.")
        if (failure.logPath != null) append(" The full launch log is in Settings.")
    }

    /** Opaque, touch-consuming panel over the X server view with the failure and a Close button. */
    private fun createErrorPanel(title: String, message: String): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(12f))
            background = GradientDrawable().apply {
                setColor(0xEE202124.toInt())
                cornerRadius = 12f * density
            }
            addView(TextView(this@DisplayActivity).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                text = title
            })
            addView(TextView(this@DisplayActivity).apply {
                setTextColor(0xDDFFFFFF.toInt())
                textSize = 14f
                setPadding(0, dp(8f), 0, dp(8f))
                setTextIsSelectable(true)
                text = message
            })
            addView(Button(this@DisplayActivity).apply {
                text = "Close"
                setOnClickListener { shownFailure?.let(::closeAfterFailure) ?: finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xCC000000.toInt())
            // Swallow touches so they don't reach the X server underneath.
            isClickable = true
            isFocusable = true
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { setMargins(dp(32f), dp(24f), dp(32f), dp(24f)) })
        }
    }

    /**
     * The performance HUD: a small, quiet chip (translucent black, no rim, dim labels and bright
     * values) carrying only the readings the user turned on. It sits in the corner [hudSettings]
     * picks or wherever it was last dragged ([HudAnchor]); dragging it moves it and remembers the
     * spot for the container. Hidden entirely when the overlay is off or every reading is.
     */
    private fun createHud(xServer: XServer): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(this).apply {
            // Placed by translation from the top-left so it can go anywhere (placeHud()).
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
            setTextColor(HUD_VALUE.toInt())
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(dp(7f), dp(4f), dp(7f), dp(4f))
            background = GradientDrawable().apply {
                setColor(HUD_FILL.toInt())
                cornerRadius = 6f * density
            }
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (hudSettings.enabled && !hudSettings.isEmpty) View.VISIBLE else View.GONE
            alpha = 0f
            text = hudLines(
                listOfNotNull(
                    ("FPS" to "--").takeIf { hudSettings.showFps },
                    ("RES" to xServer.screenInfo.toString()).takeIf { hudSettings.showResolution },
                    ("CPU" to "--").takeIf { hudSettings.showCpu },
                ),
            )
            // Its width follows the text (FPS 9 → 120): keep it on its anchor as it changes.
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) placeHud()
            }
            setOnTouchListener(::onHudTouch)
        }
    }

    private val hudDrag by lazy { HudDragTracker(touchSlopPx) }
    private val hudMarginPx by lazy { dpPx(HUD_MARGIN_DP) }

    /** Puts the HUD on its anchor (unless a finger is dragging it) and fades it in the first time. */
    private fun placeHud() {
        val hud = hudText ?: return
        val parent = root ?: return
        if (hudDrag.dragging || hud.width == 0 || parent.width == 0) return
        val (x, y) = HudAnchor.of(hudSettings).toPixels(hud.width, hud.height, parent.width, parent.height, hudMarginPx)
        hud.translationX = x
        hud.translationY = y
        if (hud.alpha == 0f) hud.animate().alpha(1f).setDuration(HUD_FADE_MS).start()
    }

    /**
     * A finger on the HUD: past the touch slop it drags the HUD (kept on screen), a short touch
     * is a tap. Only touches that land on the HUD come here, so the touchpad and the on-screen
     * controls never lose a finger to it.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onHudTouch(v: View, event: MotionEvent): Boolean {
        val parent = root ?: return false
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hudDrag.down(event.getPointerId(0), event.rawX, event.rawY, v.translationX, v.translationY)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val (rawX, rawY) = rawPoint(event, i)
                    val moved = hudDrag.move(id, rawX, rawY) ?: continue
                    if (v.scaleX == 1f) v.animate().scaleX(HUD_DRAG_SCALE).scaleY(HUD_DRAG_SCALE).alpha(HUD_DRAG_ALPHA).setDuration(HUD_FADE_MS).start()
                    v.translationX = moved.first.coerceIn(0f, (parent.width - v.width).coerceAtLeast(0).toFloat())
                    v.translationY = moved.second.coerceIn(0f, (parent.height - v.height).coerceAtLeast(0).toFloat())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val (rawX, rawY) = rawPoint(event, index)
                when (val result = hudDrag.up(event.getPointerId(index), rawX, rawY)) {
                    is HudDragTracker.Result.Moved -> {
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(HUD_FADE_MS).start()
                        saveHudAnchor(HudAnchor.fromPixels(result.left, result.top, v.width, v.height, parent.width, parent.height, hudMarginPx))
                    }
                    HudDragTracker.Result.Tap -> onHudTapped()
                    HudDragTracker.Result.None -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                hudDrag.cancel()
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(HUD_FADE_MS).start()
                placeHud()
            }
        }
        return true
    }

    /** Screen coordinates of pointer [index] (MotionEvent.getRawX(int) is API 29+). */
    private fun rawPoint(event: MotionEvent, index: Int): Pair<Float, Float> {
        val offsetX = event.rawX - event.x
        val offsetY = event.rawY - event.y
        return (event.getX(index) + offsetX) to (event.getY(index) + offsetY)
    }

    /** A tap on the HUD. Nothing yet beyond not reaching the touchpad. */
    private fun onHudTapped() = Unit

    /** Snaps the HUD to [anchor] and stores it on the container (only the position fields). */
    private fun saveHudAnchor(anchor: HudAnchor) {
        hudSettings = hudSettings.copy(customX = anchor.x, customY = anchor.y)
        placeHud()
        val id = containerId ?: return
        uiScope.launch {
            runCatching {
                val repository = ContainerRepository.get(applicationContext)
                val current = repository.containers.value.firstOrNull { it.id == id } ?: return@runCatching
                repository.update(current.copy(hud = current.hud.copy(customX = anchor.x, customY = anchor.y)))
            }.onFailure { Log.w(TAG, "Couldn't save the HUD position", it) }
        }
    }

    /** "FPS 60" lines: labels dim, values bright. */
    private fun hudLines(lines: List<Pair<String, String>>): CharSequence {
        val out = SpannableStringBuilder()
        lines.forEachIndexed { i, (label, value) ->
            if (i > 0) out.append('\n')
            val start = out.length
            out.append(label)
            out.setSpan(ForegroundColorSpan(HUD_LABEL.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(' ').append(value)
        }
        return out
    }

    private fun startHud() {
        // Nothing to sample when the overlay is off.
        if (!hudSettings.enabled || hudSettings.isEmpty) return
        if (hudText == null || hudThread != null) return
        val thread = HandlerThread("PerformanceHud").also { it.start() }
        hudThread = thread
        // Drop frames counted while paused so the first reading is accurate.
        view?.renderer?.takeFrameCount()
        hudLastSampleMs = SystemClock.elapsedRealtime()
        hudHandler = Handler(thread.looper).also { it.postDelayed(hudUpdate, HUD_INTERVAL_MS) }
    }

    private fun stopHud() {
        hudHandler?.removeCallbacks(hudUpdate)
        hudHandler = null
        hudThread?.quitSafely()
        hudThread = null
    }

    /** Runs on the HUD thread. */
    private fun buildHudText(): CharSequence {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = (now - hudLastSampleMs).coerceAtLeast(1L)
        hudLastSampleMs = now
        val frames = view?.renderer?.takeFrameCount() ?: 0
        val fps = Math.round(frames * 1000f / elapsedMs)
        val res = server?.screenInfo?.toString() ?: DisplayServer.resolution ?: "?"
        val cpu = if (hudSettings.showCpu) {
            cpuSampler.sample()?.let { usage ->
                ("CPU" to "${usage.percent}%${if (usage.systemWide) "" else " app"}")
            } ?: ("CPU" to "--")
        } else {
            null
        }
        return hudLines(
            listOfNotNull(
                ("FPS" to fps.toString()).takeIf { hudSettings.showFps },
                ("RES" to res).takeIf { hudSettings.showResolution },
                cpu,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    companion object {
        private const val TAG = "DisplayActivity"
        private const val EXTRA_CONTAINER_ID = "container_id"
        private const val SENSITIVITY = 1.5f
        private const val TOUCH_SLOP_DP = 10f
        private const val TAP_TIMEOUT_MS = 200L
        private const val HUD_INTERVAL_MS = 1000L
        private const val HUD_MARGIN_DP = 8f
        private const val HUD_FADE_MS = 150L
        private const val HUD_DRAG_SCALE = 1.06f
        private const val HUD_DRAG_ALPHA = 0.85f
        private const val HUD_FILL = 0x8C000000L
        private const val HUD_LABEL = 0x99FFFFFFL
        private const val HUD_VALUE = 0xF2FFFFFFL

        // Side menu: Fable's glass language (ui/theme/Color.kt) over the game. The panel is a
        // translucent dark sheet with a light rim, cards are a faint white wash on it, and the
        // icon tiles carry the same deep gradient tones as the Compose tiles (TileTone).
        private const val DRAWER_WIDTH_DP = 300f
        private const val DRAWER_MARGIN_DP = 12f
        private const val DRAWER_RADIUS_DP = 22f
        private const val DRAWER_ANIMATION_MS = 260L
        private const val DRAWER_CLOSE_MS = 200L
        private val DRAWER_EASE_OUT = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        private val DRAWER_EASE_IN = PathInterpolator(0.7f, 0f, 0.84f, 0f)
        private const val DRAWER_GLASS_TOP = 0xB81C1C1EL
        private const val DRAWER_GLASS_BOTTOM = 0x99101012L
        private const val DRAWER_RIM = 0x33FFFFFFL
        private const val DRAWER_SCRIM = 0x59000000L
        private const val DRAWER_SCRIM_CLEAR = 0x0D000000L
        private const val DRAWER_CARD = 0x14FFFFFFL
        private const val DRAWER_CARD_RIM = 0x14FFFFFFL
        private const val DRAWER_BUTTON_FILL = 0x1FFFFFFFL
        private const val DRAWER_BUTTON_PRESSED = 0x38FFFFFFL
        private const val DRAWER_DIVIDER = 0x1FFFFFFFL
        private const val DRAWER_ROW_PRESSED = 0x1AFFFFFFL
        private const val DRAWER_TEXT_DIM = 0xFF9A9AA0L
        private const val DRAWER_TEXT_SOFT = 0xFFE5E5EAL
        private const val SWITCH_ON_TRACK = 0xFF4A9EFFL
        private const val SWITCH_OFF_TRACK = 0x4DFFFFFFL
        private const val MENU_CARD_RADIUS_DP = 14f
        private const val MENU_ROW_MIN_DP = 50f
        private const val MENU_TILE_DP = 30f
        private const val MENU_ICON_DP = 18f

        /** Icon tile gradients, matching TileTone.Blue / Indigo / Teal. */
        private val TONE_BLUE = intArrayOf(0xFF1D4E8F.toInt(), 0xFF0E2340.toInt())
        private val TONE_INDIGO = intArrayOf(0xFF3B3A8C.toInt(), 0xFF17163D.toInt())
        private val TONE_TEAL = intArrayOf(0xFF14636A.toInt(), 0xFF072A2E.toInt())
        private val ROW_TITLE_ID = View.generateViewId()
        private val ROW_SUBTITLE_ID = View.generateViewId()
        private val ROW_SWITCH_ID = View.generateViewId()
        private val TONE_SLATE = intArrayOf(0xFF3A3F4A.toInt(), 0xFF16181D.toInt())
        private val ROW_ICON_ID = View.generateViewId()

        /** Opens the display for [containerId], which is stopped when the user leaves the screen. */
        fun open(context: Context, containerId: String) {
            if (DisplayServer.xServer == null) return
            context.startActivity(
                Intent(context, DisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_CONTAINER_ID, containerId),
            )
        }
    }
}
