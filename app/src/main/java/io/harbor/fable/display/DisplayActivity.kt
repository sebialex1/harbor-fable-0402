package io.harbor.fable.display

import android.app.Activity
import android.app.ActivityManager
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
import io.harbor.fable.data.DriverRepository
import io.harbor.fable.data.WineRuntime
import io.harbor.fable.data.models.VulkanSource
import io.harbor.fable.nativebridge.VulkanProbe
import java.io.File
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * the control preset and the performance HUD, a self-refreshing task manager (`wine tasklist` /
 * `taskkill`), pausing / resuming Wine (SIGSTOP / SIGCONT on the prefix's processes) and Stop Wine, which is
 * what Back used to do. The menu is a compact root with entries that open nested pages; Back steps
 * sub-page, root, closed.
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
    private var winePaused = false
    private var pauseItem: TextView? = null
    private var pauseIcon: ImageView? = null

    // Menu pages (see [MenuPage]): all built once, the current one visible.
    private val menuNav = MenuNavigator()
    private var menuPages: Map<MenuPage, View> = emptyMap()
    private var menuScroll: ScrollView? = null
    private var menuTitle: TextView? = null
    private var menuBackButton: View? = null
    private var menuRefreshButton: View? = null
    private var menuExportButton: View? = null
    private var menuExit: View? = null
    private var presetSummary: TextView? = null
    private var wineSummary: TextView? = null

    // Task manager page: refreshed every [TaskPollGate.INTERVAL_MS] while it is on screen.
    private var taskList: LinearLayout? = null
    private val taskGate = TaskPollGate()
    private var taskPoll: Job? = null
    private var resumed = false
    private var shownTasks: List<WindowsProcess>? = null
    private var taskDetailViews: List<TextView> = emptyList()
    private var taskNote: String? = null

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
    @Volatile private var hudSettings = HudSettings()
    private val cpuSampler = CpuSampler()
    private var cpuSamples = 0
    private val gpuSensors = GpuSensors()
    private val apiDetector = GraphicsApiDetector()
    private var processLogTail: LogTail? = null
    /** What the container is set up for (DXVK / VKD3D-Proton), shown until the real API is seen. */
    private var configuredApi: String? = null
    /** The Vulkan driver's name, looked up once per HUD start; null when unknown. */
    @Volatile private var hudDriver: String? = null
    @Volatile private var lastHudSample = HudSample()
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
        val record = containerId?.let { id ->
            runCatching { ContainerRepository.get(applicationContext).containers.value.firstOrNull { it.id == id } }.getOrNull()
        }
        hudSettings = record?.hud ?: HudSettings()
        configuredApi = record?.let { HudReadout.configuredApi(it.dxvkVersion, it.vkd3dVersion) }
        processLogTail = containerId?.let { id ->
            runCatching { LogTail(File(ContainerRepository.get(applicationContext).directory(id), WineRuntime.LAUNCH_LOG)) }.getOrNull()
        }
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
        resumed = true
        updateTaskPolling()
    }

    override fun onPause() {
        resumed = false
        updateTaskPolling()
        stopHud()
        view?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        taskPoll?.cancel()
        taskPoll = null
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
        shownTasks = null
        taskDetailViews = emptyList()
        taskNote = null
        menuPages = emptyMap()
        menuScroll = null
        menuTitle = null
        menuBackButton = null
        menuRefreshButton = null
        menuExportButton = null
        menuExit = null
        presetSummary = null
        wineSummary = null
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
            // Back opens the side menu, then steps sub-page -> root -> closed; it never reaches the
            // framework (which would finish the activity and stop Wine). Acting on UP avoids
            // re-triggering on key repeat.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        val handled = runCatching { server?.keyboard?.onKeyEvent(event) == true }.getOrDefault(false)
        return handled || super.dispatchKeyEvent(event)
    }

    // ---- Side menu ---------------------------------------------------------------------------

    /** One Back step: opens the menu, goes from a sub-page to the root, or closes the menu. */
    private fun handleBack() {
        when {
            !drawerOpen -> setDrawerOpen(true)
            menuNav.back() -> showPage(MenuPage.ROOT)
            else -> setDrawerOpen(false)
        }
    }

    private fun setDrawerOpen(open: Boolean) {
        val scrim = drawer ?: return
        val panel = drawerPanel ?: return
        if (open == drawerOpen) return
        drawerOpen = open
        // Always opens on the root; leaving the drawer (or the Tasks page) stops task polling.
        if (open) showPage(MenuPage.ROOT, animate = false)
        updateTaskPolling()
        // Slide distance: the panel's width plus its margin, once laid out; the screen's before.
        val offscreen = (panel.width.takeIf { it > 0 }?.plus(dpPx(DRAWER_MARGIN_DP)) ?: resources.displayMetrics.widthPixels).toFloat()
        scrim.animate().cancel()
        panel.animate().cancel()
        if (open) {
            // Fresh preset list each time: presets dropped into the import folders. The task list
            // refreshes itself once its page is opened.
            reloadPresets()
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
        wineSummary?.text = if (paused) "Paused" else "Running"
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
     * and no branding header. The panel is a small stack of pages ([MenuPage]) swapped in place:
     *
     *  - **Root**: a compact 2x2 grid of tiles — Overlays, Control preset, Wine, Tasks — with the
     *    glass Exit pill pinned at the bottom.
     *  - **Overlays**: on-screen controls, performance HUD, keyboard.
     *  - **Control preset**: every preset (tap to switch live); export is a small button in the
     *    header corner.
     *  - **Wine**: pause / resume.
     *  - **Tasks**: the container's Windows processes, refreshed by itself while the page is open
     *    ([updateTaskPolling]); a small refresh button sits in the header corner.
     *
     * Sub-pages show a small round back button in the top-left corner; the close button stays in
     * the top-right. Android Back goes sub-page, root, closed ([handleBack]). Hidden until Back
     * opens it ([setDrawerOpen]).
     *
     * Touch handling is unchanged: while the menu is closed the scrim is GONE, so every touch
     * reaches the X server view underneath; while it is open the scrim takes taps outside the
     * panel (closing the menu) and the panel swallows its own, so nothing leaks into the game.
     */
    private fun createDrawer(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        // Header: [back] title ... [page action] [close]. The back button and the page actions
        // only show where they apply (see [showPage]).
        val backButton = menuCornerButton(R.drawable.ic_menu_back, "Back to menu") { handleBack() }
        val title = TextView(this).apply {
            setTextColor(DRAWER_TEXT_DIM.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val refreshButton = menuCornerButton(R.drawable.ic_menu_refresh, "Refresh tasks now") { refreshTasks() }
        val exportButton = menuCornerButton(R.drawable.ic_menu_export, "Export the selected preset as .icp to Download/Fable/profiles") {
            exportSelectedPreset()
        }
        val closeButton = menuCornerButton(R.drawable.ic_menu_close, "Close menu") { setDrawerOpen(false) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(refreshButton)
            addView(exportButton)
            addView(closeButton)
        }

        // Root: tiles, not rows.
        fun tileRow(left: View, right: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(MENU_TILE_HEIGHT_DP), 1f).apply { marginEnd = dp(4f) })
            addView(right, LinearLayout.LayoutParams(0, dp(MENU_TILE_HEIGHT_DP), 1f).apply { marginStart = dp(4f) })
        }
        val overlaysTile = menuTile("Overlays", "Controls, HUD, keyboard", R.drawable.ic_menu_gamepad, TONE_BLUE) { showPage(MenuPage.OVERLAYS) }
        val presetsTile = menuTile("Control preset", selectedProfile?.profile?.name ?: "Loading presets…", R.drawable.ic_menu_gamepad, TONE_INDIGO) {
            showPage(MenuPage.PRESETS)
        }.also { presetSummary = it.findViewById(ROW_SUBTITLE_ID) }
        val wineTile = menuTile("Wine", if (winePaused) "Paused" else "Running", R.drawable.ic_menu_pause, TONE_TEAL) {
            showPage(MenuPage.WINE)
        }.also { wineSummary = it.findViewById(ROW_SUBTITLE_ID) }
        val tasksTile = menuTile("Tasks", "Running programs", R.drawable.ic_menu_end_task, TONE_SLATE) { showPage(MenuPage.TASKS) }
        val rootPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
            addView(tileRow(overlaysTile, presetsTile), LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8f) })
            addView(tileRow(wineTile, tasksTile))
        }

        val overlaysPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
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
        }

        // Control presets: one row per preset, tap to apply (see [renderPresets]).
        val presetsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
            addView(menuCard(
                LinearLayout(this@DisplayActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    presetList = this
                },
            ))
            addView(menuHint("Export (top right) saves the selected preset as .icp to Download/Fable/profiles."))
            addView(menuHint("Import: copy Winlator .icp files to Android/data/$packageName/files/profiles or Download/Winlator/profiles."))
        }

        val winePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
            addView(menuCard(
                menuItem(if (winePaused) "Resume Wine" else "Pause Wine", if (winePaused) R.drawable.ic_menu_play else R.drawable.ic_menu_pause, "Freeze every process", TONE_TEAL) {
                    setWinePaused(!winePaused)
                    setDrawerOpen(false)
                }.also {
                    pauseItem = it.findViewById(ROW_TITLE_ID)
                    pauseIcon = it.findViewById(ROW_ICON_ID)
                },
            ))
        }

        // Task manager: the container's Windows processes, each with an End button; the list
        // refreshes itself while this page is open (see [updateTaskPolling]).
        val tasksPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
            addView(LinearLayout(this@DisplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                taskList = this
            })
            addView(menuHint("Updates every ${TaskPollGate.INTERVAL_MS / 1000} seconds while this page is open."))
        }

        val pages = linkedMapOf(
            MenuPage.ROOT to rootPage as View,
            MenuPage.OVERLAYS to overlaysPage,
            MenuPage.PRESETS to presetsPage,
            MenuPage.WINE to winePage,
            MenuPage.TASKS to tasksPage,
        )
        val pageHost = FrameLayout(this).apply {
            pages.values.forEach { page ->
                page.visibility = View.GONE
                addView(page, FrameLayout.LayoutParams(match, wrap))
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(pageHost)
        }

        // Exit: the one big primary action, a neutral full-round glass pill (not a red slab).
        // The power glyph and the words say what it does. Root only.
        val exit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = MENU_EXIT_HEIGHT_DP / 2f)
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
        }

        menuPages = pages
        menuScroll = scroll
        menuTitle = title
        menuBackButton = backButton
        menuRefreshButton = refreshButton
        menuExportButton = exportButton
        menuExit = exit
        menuNav.reset()
        showPage(MenuPage.ROOT, animate = false)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(14f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(DRAWER_GLASS_TOP.toInt(), DRAWER_GLASS_BOTTOM.toInt()),
            ).apply {
                cornerRadius = DRAWER_RADIUS_DP * density
                setStroke(dp(1f).coerceAtLeast(1), DRAWER_RIM.toInt())
            }
            elevation = 0f
            isClickable = true
            addView(header, LinearLayout.LayoutParams(match, wrap))
            // Landscape screens can be shorter than the page. Keep Exit outside the scrollable
            // content so it never gets clipped or pushed off the bottom.
            addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            addView(exit, LinearLayout.LayoutParams(match, dp(MENU_EXIT_HEIGHT_DP)).apply { topMargin = dp(12f) })
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

    /**
     * Shows [page] in the side menu: swaps the visible page, restyles the header (a small caption
     * on the root; a back button, a title and the page's own corner actions on sub-pages), shows
     * Exit only on the root and (re)evaluates task polling.
     */
    private fun showPage(page: MenuPage, animate: Boolean = true) {
        menuNav.open(page)
        val root = page == MenuPage.ROOT
        menuPages.forEach { (p, v) -> v.visibility = if (p == page) View.VISIBLE else View.GONE }
        menuTitle?.apply {
            text = page.title
            isAllCaps = root
            textSize = if (root) 12f else 16f
            letterSpacing = if (root) 0.06f else 0f
            typeface = fableFont(R.font.inter_medium)
            setTextColor(if (root) DRAWER_TEXT_DIM.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dpPx(if (root) 4f else 8f), 0, 0, 0)
        }
        menuBackButton?.visibility = if (root) View.GONE else View.VISIBLE
        menuRefreshButton?.visibility = if (page == MenuPage.TASKS) View.VISIBLE else View.GONE
        menuExportButton?.visibility = if (page == MenuPage.PRESETS) View.VISIBLE else View.GONE
        menuExit?.visibility = if (root) View.VISIBLE else View.GONE
        menuScroll?.scrollTo(0, 0)
        menuPages[page]?.let { shown ->
            shown.animate().cancel()
            if (animate) {
                // A short slide from the side the page came from: sub-pages in from the right,
                // the root back in from the left.
                shown.alpha = 0f
                shown.translationX = dpPx(if (root) -14f else 14f).toFloat()
                shown.animate().alpha(1f).translationX(0f).setDuration(PAGE_ANIMATION_MS).setInterpolator(DRAWER_EASE_OUT).start()
            } else {
                shown.alpha = 1f
                shown.translationX = 0f
            }
        }
        updateTaskPolling()
    }

    /**
     * A small round icon button for a corner of the panel or of a card: a 30dp glass disc inside a
     * 40dp touch target. Secondary actions use these instead of full-width rows.
     */
    private fun menuCornerButton(iconRes: Int, description: String, onClick: () -> Unit): View {
        fun dp(v: Float) = dpPx(v)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(MENU_CORNER_TOUCH_DP), dp(MENU_CORNER_TOUCH_DP))
            contentDescription = description
            isClickable = true
            setOnClickListener { onClick() }
            addView(FrameLayout(this@DisplayActivity).apply {
                background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = MENU_CORNER_VISUAL_DP / 2f)
                // Lights up with the touch target it sits in.
                isDuplicateParentStateEnabled = true
                addView(ImageView(this@DisplayActivity).apply {
                    isDuplicateParentStateEnabled = true
                    setImageDrawable(menuIcon(iconRes, DRAWER_TEXT_SOFT.toInt()))
                }, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER))
            }, FrameLayout.LayoutParams(dp(MENU_CORNER_VISUAL_DP), dp(MENU_CORNER_VISUAL_DP), Gravity.CENTER))
        }
    }

    /**
     * A root entry: a glass tile with a round icon, title and one-line summary ([ROW_SUBTITLE_ID])
     * and a small chevron in the top corner saying it opens a page.
     */
    private fun menuTile(label: String, summary: String, iconRes: Int, tone: IntArray, onClick: () -> Unit): View {
        fun dp(v: Float) = dpPx(v)
        val density = resources.displayMetrics.density
        return FrameLayout(this).apply {
            background = glassPill(DRAWER_BUTTON_FILL, DRAWER_BUTTON_PRESSED, cornerDp = MENU_TILE_RADIUS_DP)
            contentDescription = label
            isClickable = true
            setOnClickListener { onClick() }
            addView(LinearLayout(this@DisplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(12f), dp(12f), dp(10f))
                addView(FrameLayout(this@DisplayActivity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, tone).apply {
                        shape = GradientDrawable.OVAL
                        setStroke((0.75f * density).toInt().coerceAtLeast(1), DRAWER_RIM.toInt())
                    }
                    addView(ImageView(this@DisplayActivity).apply {
                        setImageDrawable(menuIcon(iconRes))
                    }, FrameLayout.LayoutParams(dp(MENU_ICON_DP), dp(MENU_ICON_DP), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
                addView(View(this@DisplayActivity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(TextView(this@DisplayActivity).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    typeface = fableFont(R.font.inter_medium)
                    letterSpacing = -0.01f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = label
                })
                addView(TextView(this@DisplayActivity).apply {
                    id = ROW_SUBTITLE_ID
                    setTextColor(DRAWER_TEXT_DIM.toInt())
                    textSize = 11f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = summary
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ImageView(this@DisplayActivity).apply {
                setImageDrawable(menuIcon(R.drawable.ic_menu_chevron, DRAWER_TEXT_DIM.toInt()))
            }, FrameLayout.LayoutParams(dp(14f), dp(14f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12f)
                marginEnd = dp(10f)
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
            presetSummary?.text = chosen?.profile?.name ?: "No presets found"
            renderPresets()
        }
    }

    /** Switches the on-screen controls to [preset] right away and remembers it for this container. */
    private fun applyPreset(preset: StoredProfile) {
        selectedProfile = preset
        controlsOverlay?.setProfile(preset.profile)
        profileRepository.select(preset, containerId)
        controlsSubtitle?.text = preset.profile.name
        presetSummary?.text = preset.profile.name
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
        if (shown && hudSettings.isEmpty) hudSettings = hudSettings.allReadings()
        hudSettings = hudSettings.copy(enabled = shown)
        hudText?.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) startHud() else stopHud()
    }

    // ---- Mini task manager -------------------------------------------------------------------

    /**
     * Starts or stops the Tasks page's poll: it runs only while that page is on screen, the
     * drawer is open and the activity is resumed ([TaskPollGate.shouldPoll]); every other state
     * cancels it. [refreshTasks] skips a round while the previous query is still running.
     */
    private fun updateTaskPolling() {
        if (!TaskPollGate.shouldPoll(menuNav.current, drawerOpen, resumed)) {
            taskPoll?.cancel()
            taskPoll = null
            return
        }
        if (taskPoll?.isActive == true) return
        taskPoll = uiScope.launch {
            while (isActive) {
                refreshTasks()
                delay(TaskPollGate.INTERVAL_MS)
            }
        }
    }

    /**
     * Lists the container's Windows processes ([ContainerRepository.listWindowsProcesses]:
     * `wine tasklist`, or `/proc` as a fallback) into the Tasks page. The query runs on the IO
     * dispatcher; only one runs at a time ([taskGate]) and the list is updated in place, so the
     * page doesn't flicker or eat a tap on End when it refreshes.
     */
    private fun refreshTasks() {
        val list = taskList ?: return
        val id = containerId ?: return
        if (winePaused) {
            // A stopped wineserver can't answer tasklist.
            showTaskNote(list, "Resume Wine to list processes")
            return
        }
        if (!taskGate.tryBegin()) return
        if (shownTasks == null) showTaskNote(list, "Loading…")
        uiScope.launch {
            val processes = try {
                runCatching { ContainerRepository.get(applicationContext).listWindowsProcesses(id) }.getOrDefault(emptyList())
            } finally {
                taskGate.end()
            }
            renderTasks(processes)
        }
    }

    /** Replaces the task list with one dim line, unless it already shows exactly that. */
    private fun showTaskNote(list: LinearLayout, note: String) {
        if (taskNote == note) return
        list.removeAllViews()
        list.addView(menuInfoRow(note))
        taskNote = note
        shownTasks = null
        taskDetailViews = emptyList()
    }

    private fun renderTasks(processes: List<WindowsProcess>) {
        val list = taskList ?: return
        if (winePaused) return
        val previous = shownTasks
        if (previous != null && sameTaskRows(previous, processes)) {
            // Same programs: refresh their memory / PID text without rebuilding the rows.
            processes.forEachIndexed { index, process -> taskDetailViews.getOrNull(index)?.text = taskDetail(process) }
            shownTasks = processes
            return
        }
        list.removeAllViews()
        taskNote = null
        shownTasks = processes
        if (processes.isEmpty()) {
            taskDetailViews = emptyList()
            list.addView(menuInfoRow("No Windows processes found"))
            return
        }
        val details = ArrayList<TextView>(processes.size)
        processes.forEach { process ->
            val system = process.name.lowercase() in WindowsTasks.SYSTEM_PROCESSES
            val row = menuTaskRow(process, system)
            details += row.findViewById<TextView>(ROW_SUBTITLE_ID)
            list.addView(row)
        }
        taskDetailViews = details
    }

    private fun taskDetail(process: WindowsProcess): String = buildList {
        process.windowsPid?.let { add("PID $it") } ?: process.linuxPid?.let { add("pid $it") }
        process.memory?.let { add(it) }
        if (process.name.lowercase() in WindowsTasks.SYSTEM_PROCESSES) add("Wine")
    }.joinToString(" · ")

    /** One program: its own small card with name and details, End pinned in the corner. */
    private fun menuTaskRow(process: WindowsProcess, system: Boolean): View {
        fun dp(v: Float) = dpPx(v)
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6f) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(12f), dp(4f), dp(4f), dp(4f))
            background = GradientDrawable().apply {
                setColor(DRAWER_CARD.toInt())
                cornerRadius = dp(MENU_TASK_RADIUS_DP).toFloat()
                setStroke((0.75f * resources.displayMetrics.density).toInt().coerceAtLeast(1), DRAWER_CARD_RIM.toInt())
            }
            addView(LinearLayout(this@DisplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DisplayActivity).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = process.name
                })
                addView(TextView(this@DisplayActivity).apply {
                    id = ROW_SUBTITLE_ID
                    setTextColor(DRAWER_TEXT_DIM.toInt())
                    textSize = 11f
                    typeface = fableFont(R.font.inter_regular)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text = taskDetail(process)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(menuCornerButton(R.drawable.ic_menu_end_task, "End ${process.name}") { confirmEndTask(process, system) })
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
     * tick).
     */
    private fun menuTextRow(
        title: String,
        detail: String?,
        trailing: String? = null,
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
            lastHudSample = HudSample(configuredApi = configuredApi, resolution = xServer.screenInfo.toString(), cpuWarmedUp = false)
            text = hudText(lastHudSample)
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

    /**
     * A tap on the HUD switches its layout (stacked ↔ side by side), right away with the last
     * readings, and remembers it for the container.
     */
    private fun onHudTapped() {
        val layout = hudSettings.layout.next()
        hudSettings = hudSettings.copy(layout = layout)
        hudText?.text = hudText(lastHudSample)
        val id = containerId ?: return
        uiScope.launch {
            runCatching {
                val repository = ContainerRepository.get(applicationContext)
                val current = repository.containers.value.firstOrNull { it.id == id } ?: return@runCatching
                repository.update(current.copy(hud = current.hud.copy(layout = layout)))
            }.onFailure { Log.w(TAG, "Couldn't save the HUD layout", it) }
        }
    }

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

    /** [sample] as the HUD shows it in the current layout: labels dim, values bright. */
    private fun hudText(sample: HudSample): CharSequence {
        val settings = hudSettings
        val lines = HudReadout.lines(settings, sample)
        val separator = HudReadout.separator(settings.layout)
        val out = SpannableStringBuilder()
        lines.forEachIndexed { i, (label, value) ->
            if (i > 0) out.append(separator)
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
        hudHandler = Handler(thread.looper).also { handler ->
            if (hudSettings.showDriver && hudDriver == null) handler.post { hudDriver = lookUpDriver() }
            handler.postDelayed(hudUpdate, HUD_INTERVAL_MS)
        }
    }

    /**
     * The Vulkan driver's name: from the last Vulkan probe of it when there is one (the driver's
     * own name and version, e.g. "turnip Mesa 25.1.0"), else the installed package's name, else
     * the system driver. Never loads a driver itself (the game has one open).
     */
    private fun lookUpDriver(): String? = runCatching {
        val drivers = DriverRepository.get(applicationContext)
        val installed = drivers.installed.value
        val path = drivers.activeLibraryPath()
        val source = if (path != null) VulkanSource.INSTALLED_DRIVER else VulkanSource.SYSTEM
        val device = VulkanProbe.cached(source, path)?.primaryDevice
        val probed = device?.let { listOfNotNull(it.driverName, it.driverInfo).joinToString(" ").ifBlank { null } }
        probed ?: installed?.let { "${it.family.displayName} ${it.tag}" } ?: if (path == null) "System Vulkan" else null
    }.getOrNull()

    private fun stopHud() {
        hudHandler?.removeCallbacks(hudUpdate)
        hudHandler = null
        hudThread?.quitSafely()
        hudThread = null
    }

    /** Runs on the HUD thread: one second's readings, for what [hudSettings] shows. */
    private fun buildHudText(): CharSequence {
        val settings = hudSettings
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = (now - hudLastSampleMs).coerceAtLeast(1L)
        hudLastSampleMs = now
        val frames = view?.renderer?.takeFrameCount() ?: 0
        val cpu = if (settings.showCpu) cpuSampler.sample().also { cpuSamples++ } else null
        if (settings.showApi && !apiDetector.isFinal) {
            processLogTail?.readNewLines()?.forEach { apiDetector.feed(it) }
        }
        if (settings.showDriver && hudDriver == null) hudDriver = lookUpDriver()
        val memory = if (settings.showRam) {
            runCatching {
                val info = ActivityManager.MemoryInfo()
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                info.takeIf { it.totalMem > 0 }
            }.getOrNull()
        } else {
            null
        }
        val sample = HudSample(
            fps = Math.round(frames * 1000f / elapsedMs),
            // Average over the second; no frame drawn means no frame time to report.
            frameTimeMs = if (frames > 0) elapsedMs.toFloat() / frames else null,
            detectedApi = apiDetector.label,
            configuredApi = configuredApi,
            driver = hudDriver,
            gpuBusyPercent = if (settings.showGpuUsage) gpuSensors.busyPercent() else null,
            gpuTempC = if (settings.showGpuTemp) gpuSensors.temperatureC() else null,
            cpuPercent = cpu?.percent,
            cpuAppOnly = cpu?.systemWide == false,
            // The sampler needs two reads before it can report anything.
            cpuWarmedUp = cpuSamples >= 2,
            ramUsedBytes = memory?.let { it.totalMem - it.availMem },
            ramTotalBytes = memory?.totalMem,
            resolution = server?.screenInfo?.toString() ?: DisplayServer.resolution,
        )
        lastHudSample = sample
        return hudText(sample)
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
        private const val PAGE_ANIMATION_MS = 160L
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
        private const val MENU_TILE_RADIUS_DP = 18f
        private const val MENU_TASK_RADIUS_DP = 12f
        private const val MENU_EXIT_HEIGHT_DP = 46f
        private const val MENU_TILE_HEIGHT_DP = 96f
        /** Touch target of the small corner buttons; the drawn disc inside is [MENU_CORNER_VISUAL_DP]. */
        private const val MENU_CORNER_TOUCH_DP = 40f
        private const val MENU_CORNER_VISUAL_DP = 30f
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
