package io.harbor.fable.display

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.winlator.widget.XServerView
import io.harbor.fable.R
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.WineFailure
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
    private var keyboardShown = false
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
        if (open) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(DRAWER_ANIMATION_MS).start()
            // Slide in from the right edge; the panel's width is known once it has been laid out,
            // so start from its measured width or, before the first layout, the screen's.
            val from = (panel.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
            panel.translationX = from
            panel.animate().translationX(0f).setDuration(DRAWER_ANIMATION_MS).start()
        } else {
            val to = (panel.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
            panel.animate().translationX(to).setDuration(DRAWER_ANIMATION_MS).start()
            scrim.animate().alpha(0f).setDuration(DRAWER_ANIMATION_MS)
                .withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }
                .start()
        }
    }

    private fun setControlsShown(shown: Boolean) {
        controlsOverlay?.visibility = if (shown) View.VISIBLE else View.GONE
    }

    /**
     * Shows or hides Android's soft keyboard over the X screen. The content view has no
     * InputConnection, so the IME falls back to sending plain key events, which
     * [dispatchKeyEvent] forwards to the X server like a hardware keyboard's. (Winlator does the
     * same with `toggleSoftInput`.)
     */
    private fun setKeyboardShown(shown: Boolean) {
        val target = root ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        keyboardShown = shown
        if (shown) {
            target.requestFocus()
            @Suppress("DEPRECATION")
            imm.showSoftInput(target, InputMethodManager.SHOW_FORCED)
        } else {
            imm.hideSoftInputFromWindow(target.windowToken, 0)
        }
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
     * The side menu: a tap-to-close scrim over the whole screen with an opaque, rounded panel on
     * the right edge, in the app's grouped-list idiom — a header, a section of switches and a
     * section of actions, each on its own card. Hidden until Back opens it ([setDrawerOpen]).
     */
    private fun createDrawer(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                setColor(DRAWER_BACKGROUND.toInt())
                val r = DRAWER_RADIUS_DP * density
                // Rounded on the screen side only; the right edge is flush with the display's.
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            // Taps on the panel itself must not fall through to the scrim (which closes).
            isClickable = true

            // Header: the app name and what Back does now.
            addView(TextView(this@DisplayActivity).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 22f
                typeface = fableFont(R.font.inter_medium)
                letterSpacing = -0.02f
                text = "Fable"
                setPadding(dp(4f), 0, dp(4f), 0)
            })
            addView(TextView(this@DisplayActivity).apply {
                setTextColor(DRAWER_TEXT_DIM.toInt())
                textSize = 13f
                typeface = fableFont(R.font.inter_regular)
                text = "Back closes this menu"
                setPadding(dp(4f), dp(2f), dp(4f), 0)
            })

            addView(menuSectionLabel("Overlays"))
            addView(menuCard(
                menuSwitch("On-screen controls", R.drawable.ic_menu_gamepad, "D-pad and Enter / Esc / Space / Shift") { setControlsShown(it) },
                menuDivider(),
                menuSwitch("On-screen keyboard", R.drawable.ic_menu_keyboard, "Android's keyboard, typed into Wine") { setKeyboardShown(it) },
            ))

            addView(menuSectionLabel("Wine"))
            addView(menuCard(
                menuItem("Pause Wine", R.drawable.ic_menu_pause, "Freezes every process of the container") {
                    setWinePaused(!winePaused)
                    setDrawerOpen(false)
                }.also {
                    pauseItem = it.findViewById(ROW_TITLE_ID)
                    pauseIcon = it.findViewById(ROW_ICON_ID)
                },
                menuDivider(),
                menuItem("Stop Wine", R.drawable.ic_menu_power, "Ends the session and returns to the app") {
                    // Same as the old Back: finishing stops the container in onDestroy.
                    finish()
                },
            ))

            // Spacer pushes Close to the bottom, where the thumb is on a landscape phone.
            addView(View(this@DisplayActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(menuCard(menuItem("Close menu", R.drawable.ic_menu_close, null) { setDrawerOpen(false) }))
        }
        drawerPanel = panel
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(DRAWER_SCRIM.toInt())
            visibility = View.GONE
            // Swallows touches so they don't reach the X server; a tap outside the panel closes.
            isClickable = true
            setOnClickListener { setDrawerOpen(false) }
            addView(panel, FrameLayout.LayoutParams(dp(DRAWER_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
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

    /** Small grey uppercase caption above a card, like the Compose `SectionLabel`. */
    private fun menuSectionLabel(label: String): TextView {
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
            setPadding(dp(16f), dp(20f), dp(16f), dp(6f))
        }
    }

    /** A grouped card: an opaque raised surface with rounded corners holding the given rows. */
    private fun menuCard(vararg rows: View): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(DRAWER_CARD.toInt())
                cornerRadius = MENU_CARD_RADIUS_DP * density
            }
            clipToOutline = true
            rows.forEach { addView(it) }
        }
    }

    /** Icon tile + title (+ subtitle) shared by action and switch rows. */
    private fun menuRowContent(label: String, subtitle: String?, iconRes: Int): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val tile = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(DRAWER_ICON_TILE.toInt())
                cornerRadius = 7f * density
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
                textSize = 16f
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
    private fun menuItem(label: String, iconRes: Int, subtitle: String?, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_MIN_DP)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = menuRowBackground()
            addView(menuRowContent(label, subtitle, iconRes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }
    }

    /** A menu row with a switch on the right; [onChange] gets the new state. */
    private fun menuSwitch(label: String, iconRes: Int, subtitle: String?, onChange: (Boolean) -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val toggle = Switch(this).apply {
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_MIN_DP)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = menuRowBackground()
            addView(menuRowContent(label, subtitle, iconRes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                setMargins(dp(12f + MENU_TILE_DP + 12f), 0, 0, 0)
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

    /** Small semi-transparent label for the top-left corner; doesn't take touches. */
    private fun createHud(xServer: XServer): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(dp(8f), dp(8f), 0, 0) }
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadius = 6f * density
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = "FPS: --\n${xServer.screenInfo}"
        }
    }

    private fun startHud() {
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
        val cpu = cpuSampler.sample()?.let { usage ->
            if (usage.systemWide) "CPU: ${usage.percent}%" else "CPU (app): ${usage.percent}%"
        }
        return buildString {
            append("FPS: ").append(fps).append('\n').append(res)
            if (cpu != null) append('\n').append(cpu)
        }
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

        // Side menu: Fable's grouped-list palette (ui/theme/Color.kt) over the game. The panel
        // is near-black and opaque enough to read on any frame; cards are one grey up, icon
        // tiles one more, like FableCard / IconTile.
        private const val DRAWER_WIDTH_DP = 320f
        private const val DRAWER_RADIUS_DP = 24f
        private const val DRAWER_ANIMATION_MS = 180L
        private const val DRAWER_BACKGROUND = 0xF2000000L
        private const val DRAWER_SCRIM = 0x66000000L
        private const val DRAWER_CARD = 0xFF1C1C1EL
        private const val DRAWER_ICON_TILE = 0xFF2C2C2EL
        private const val DRAWER_DIVIDER = 0xFF38383AL
        private const val DRAWER_ROW_PRESSED = 0x1AFFFFFFL
        private const val DRAWER_TEXT_DIM = 0xFF8E8E93L
        private const val MENU_CARD_RADIUS_DP = 12f
        private const val MENU_ROW_MIN_DP = 52f
        private const val MENU_TILE_DP = 32f
        private const val MENU_ICON_DP = 20f
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
