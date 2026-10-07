package io.harbor.fable.display

import android.annotation.SuppressLint
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
import android.text.TextUtils
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
import io.harbor.fable.R
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.WineFailure
import io.harbor.fable.data.models.HudPosition
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
import kotlin.math.hypot

/**
 * Shows the X server's screen (Wine's virtual desktop) full screen, like Winlator's
 * `XServerDisplayActivity`: an [XServerView] (GLSurfaceView + Winlator's GLRenderer) attached to
 * the running [DisplayServer].
 *
 * Input is deliberately minimal (Winlator's TouchpadView / input-controls overlay are not
 * vendored): the screen works like a laptop trackpad — dragging a finger moves the cursor
 * relatively, a quick tap is a left click, a second finger is a right click, and hardware keyboard events go through Winlator's
 * `Keyboard.onKeyEvent`. The activity finishing (for any reason) stops the container: its Wine
 * processes and the X server.
 *
 * Back doesn't finish the activity any more — a game that catches Escape on a phone's back
 * gesture would otherwise be killed by every accidental swipe. It opens a side menu (see
 * [createDrawer]) that slides in from the right edge over the X screen: on-screen controls
 * (a d-pad and four action buttons that inject X key events), the Android soft keyboard,
 * pausing / resuming Wine (SIGSTOP / SIGCONT on the prefix's processes) and Stop Wine, which is
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
    private var leftDown = false
    private var containerId: String? = null

    // Side menu (Back opens it) and the overlays it toggles.
    private var root: FrameLayout? = null
    private var drawer: View? = null
    private var drawerPanel: View? = null
    private var drawerOpen = false
    private var controlsOverlay: View? = null
    private var winePaused = false
    private var pauseItem: TextView? = null
    private var pauseIcon: ImageView? = null

    // Trackpad state (screen pixels).
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var carryX = 0f
    private var carryY = 0f
    private var tapCandidate = false
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

    @SuppressLint("ClickableViewAccessibility")
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
        created.setOnTouchListener { _, event -> onTouch(xServer, created, event) }
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
            addView(hud)
            addView(status)
            addView(controls)
            addView(menu)
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
        controlsOverlay = null
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
        controlsOverlay?.visibility = if (shown) View.VISIBLE else View.GONE
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
     * and no branding header. A compact close button sits at the top, then two short sections
     * (Overlays, Wine) on glass cards, and a neutral glass Exit button pinned at the bottom.
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
                menuSwitch("On-screen controls", R.drawable.ic_menu_gamepad, "D-pad and action keys", TONE_BLUE) { setControlsShown(it) },
                menuDivider(),
                menuItem("Keyboard", R.drawable.ic_menu_keyboard, "Type into Wine", TONE_INDIGO) {
                    setDrawerOpen(false)
                    showKeyboard()
                }.also { it.contentDescription = "Open Android's keyboard to type into Wine" },
            ))

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

    /** A menu row with a switch on the right; [onChange] gets the new state. */
    private fun menuSwitch(label: String, iconRes: Int, subtitle: String?, tone: IntArray, onChange: (Boolean) -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = Switch(this).apply {
            // Fable's switch colours: blue track when on, a faint glass track when off.
            thumbTintList = ColorStateList(checkedState, intArrayOf(0xFFFFFFFF.toInt(), 0xFFD1D1D6.toInt()))
            trackTintList = ColorStateList(checkedState, intArrayOf(SWITCH_ON_TRACK.toInt(), SWITCH_OFF_TRACK.toInt()))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
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
     * A translucent d-pad (bottom left) and four action buttons (bottom right) that inject X key
     * presses while held — arrows and Enter / Escape / Space / Shift, what most games bind by
     * default — so a keyboard-driven game is playable without a controller. The overlay itself
     * is not clickable: touches between the buttons fall through to the trackpad underneath.
     * Hidden until the side menu turns it on.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createControlsOverlay(xServer: XServer): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val size = dp(PAD_BUTTON_DP)
        val gap = dp(4f)

        fun padButton(label: String, keycode: XKeycode): View = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            // Two-line labels ("A" over the key it sends) need the smaller size to fit the circle.
            textSize = if ('\n' in label) 11f else 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = label
            background = GradientDrawable().apply {
                setColor(PAD_BUTTON_COLOR.toInt())
                setStroke(dp(1.5f), PAD_BUTTON_STROKE.toInt())
                cornerRadius = size / 2f
            }
            isClickable = true
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.6f
                        runCatching { xServer.injectKeyPress(keycode) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        runCatching { xServer.injectKeyRelease(keycode) }
                    }
                }
                true
            }
        }

        // Places a button in a 3x3 grid (corners stay empty): up / left / right / down.
        fun cell(col: Int, row: Int, button: View) = button.also {
            it.layoutParams = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = col * (size + gap)
                topMargin = row * (size + gap)
            }
        }
        val dpad = FrameLayout(this).apply {
            addView(cell(1, 0, padButton("\u25B2", XKeycode.KEY_UP)))
            addView(cell(0, 1, padButton("\u25C0", XKeycode.KEY_LEFT)))
            addView(cell(2, 1, padButton("\u25B6", XKeycode.KEY_RIGHT)))
            addView(cell(1, 2, padButton("\u25BC", XKeycode.KEY_DOWN)))
        }
        // Diamond, like a controller's face buttons: Y top, X left, B right, A bottom.
        val actions = FrameLayout(this).apply {
            addView(cell(1, 0, padButton("Y\nShift", XKeycode.KEY_SHIFT_L)))
            addView(cell(0, 1, padButton("X\nSpace", XKeycode.KEY_SPACE)))
            addView(cell(2, 1, padButton("B\nEsc", XKeycode.KEY_ESC)))
            addView(cell(1, 2, padButton("A\nEnter", XKeycode.KEY_ENTER)))
        }
        val clusterSize = 3 * size + 2 * gap
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            addView(dpad, FrameLayout.LayoutParams(clusterSize, clusterSize, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(24f), 0, 0, dp(24f))
            })
            addView(actions, FrameLayout.LayoutParams(clusterSize, clusterSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(24f), dp(24f))
            })
        }
    }

    private fun onTouch(xServer: XServer, view: XServerView, event: MotionEvent): Boolean {
        val t = view.renderer.viewTransformation
        if (t.aspect <= 0f) return true
        runCatching {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Trackpad: remember where the finger landed; the cursor stays put.
                    activePointerId = event.getPointerId(0)
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    carryX = 0f
                    carryY = 0f
                    tapCandidate = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activePointerId)
                    if (event.pointerCount == 1 && index >= 0) {
                        val px = event.getX(index)
                        val py = event.getY(index)
                        if (tapCandidate && hypot(px - downX, py - downY) > touchSlopPx) tapCandidate = false
                        // Screen pixels -> X server pixels, keeping sub-pixel remainders so slow
                        // drags still move the cursor.
                        carryX += (px - lastX) / t.aspect * SENSITIVITY
                        carryY += (py - lastY) / t.aspect * SENSITIVITY
                        lastX = px
                        lastY = py
                        val dx = carryX.toInt()
                        val dy = carryY.toInt()
                        if (dx != 0 || dy != 0) {
                            carryX -= dx
                            carryY -= dy
                            xServer.injectPointerMoveDelta(dx, dy)
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    tapCandidate = false
                    if (leftDown) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        leftDown = false
                    }
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep tracking whichever finger remains without a jump.
                    val upIndex = event.actionIndex
                    if (event.getPointerId(upIndex) == activePointerId) {
                        val newIndex = if (upIndex == 0) 1 else 0
                        activePointerId = event.getPointerId(newIndex)
                        lastX = event.getX(newIndex)
                        lastY = event.getY(newIndex)
                    } else {
                        val index = event.findPointerIndex(activePointerId)
                        if (index >= 0) {
                            lastX = event.getX(index)
                            lastY = event.getY(index)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val isTap = tapCandidate &&
                        event.eventTime - downTime < TAP_TIMEOUT_MS &&
                        hypot(event.x - downX, event.y - downY) <= touchSlopPx
                    if (leftDown) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        leftDown = false
                    }
                    if (isTap) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                        leftDown = true
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        leftDown = false
                    }
                    tapCandidate = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (leftDown) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        leftDown = false
                    }
                    tapCandidate = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
        }
        return true
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
     * The performance HUD: a small glass chip in the corner [hudSettings] picks, carrying only the
     * lines the user turned on (frame rate, X screen resolution, CPU usage). Doesn't take touches.
     * Hidden entirely when the overlay is off or every line is.
     */
    private fun createHud(xServer: XServer): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val gravity = when (hudSettings.position) {
            HudPosition.TOP_START -> Gravity.TOP or Gravity.START
            HudPosition.TOP_END -> Gravity.TOP or Gravity.END
            HudPosition.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
            HudPosition.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
        }
        return TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity,
            ).apply { setMargins(dp(10f), dp(10f), dp(10f), dp(10f)) }
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(1f).toFloat(), 1f)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xA61C1C1E.toInt(), 0x8C0B0B0D.toInt()),
            ).apply {
                cornerRadius = 8f * density
                setStroke((0.75f * density).toInt().coerceAtLeast(1), DRAWER_RIM.toInt())
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (hudSettings.enabled && !hudSettings.isEmpty) View.VISIBLE else View.GONE
            text = buildString {
                if (hudSettings.showFps) append("FPS: --")
                if (hudSettings.showResolution) {
                    if (isNotEmpty()) append('\n')
                    append(xServer.screenInfo)
                }
            }
        }
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
    private fun buildHudText(): String {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = (now - hudLastSampleMs).coerceAtLeast(1L)
        hudLastSampleMs = now
        val frames = view?.renderer?.takeFrameCount() ?: 0
        val fps = Math.round(frames * 1000f / elapsedMs)
        val res = server?.screenInfo?.toString() ?: DisplayServer.resolution ?: "?"
        val cpu = if (hudSettings.showCpu) {
            cpuSampler.sample()?.let { usage ->
                if (usage.systemWide) "CPU: ${usage.percent}%" else "CPU (app): ${usage.percent}%"
            }
        } else {
            null
        }
        return listOfNotNull(
            "FPS: $fps".takeIf { hudSettings.showFps },
            res.takeIf { hudSettings.showResolution },
            cpu,
        ).joinToString("\n")
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
        private val ROW_ICON_ID = View.generateViewId()

        // On-screen controls.
        private const val PAD_BUTTON_DP = 52f
        private const val PAD_BUTTON_COLOR = 0x66000000L
        private const val PAD_BUTTON_STROKE = 0x99FFFFFFL

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
