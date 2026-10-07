package io.harbor.fable.display

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.winlator.widget.XServerView
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.WineFailure
import com.winlator.xserver.Pointer
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager as XWindowManager
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
 * `Keyboard.onKeyEvent`. Leaving the screen (Back, or the activity finishing for any other
 * reason) stops the container: its Wine processes and the X server.
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
        setContentView(FrameLayout(this).apply {
            addView(created)
            addView(hud)
            addView(status)
        })
        view = created
        hudText = hud
        statusText = status
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
        // Only a real exit stops Wine; a recreate (config change not covered by the manifest)
        // comes straight back to the same display.
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val handled = runCatching { server?.keyboard?.onKeyEvent(event) == true }.getOrDefault(false)
        return handled || super.dispatchKeyEvent(event)
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
