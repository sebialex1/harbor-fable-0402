package io.harbor.fable.display

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.winlator.widget.XServerView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer

/**
 * Shows the X server's screen (Wine's virtual desktop) full screen, like Winlator's
 * `XServerDisplayActivity`: an [XServerView] (GLSurfaceView + Winlator's GLRenderer) attached to
 * the running [DisplayServer].
 *
 * Input is deliberately minimal (Winlator's TouchpadView / input-controls overlay are not
 * vendored): a finger acts as the mouse at the touched point with the left button held, a
 * second finger is a right click, and hardware keyboard events go through Winlator's
 * `Keyboard.onKeyEvent`. Back leaves the screen; Wine keeps running.
 */
class DisplayActivity : Activity() {
    private var view: XServerView? = null
    private var server: XServer? = null
    private var leftDown = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContentView(FrameLayout(this).apply { addView(created) })
        view = created
        server = xServer
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        view?.onResume()
    }

    override fun onPause() {
        view?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        view?.renderer?.release()
        view = null
        server = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val handled = runCatching { server?.keyboard?.onKeyEvent(event) == true }.getOrDefault(false)
        return handled || super.dispatchKeyEvent(event)
    }

    private fun onTouch(xServer: XServer, view: XServerView, event: MotionEvent): Boolean {
        val t = view.renderer.viewTransformation
        if (t.aspect <= 0f) return true
        val x = ((event.x - t.viewOffsetX) / t.aspect).toInt().coerceIn(0, xServer.screenInfo.width - 1)
        val y = ((event.y - t.viewOffsetY) / t.aspect).toInt().coerceIn(0, xServer.screenInfo.height - 1)
        runCatching {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xServer.injectPointerMove(x, y)
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                    leftDown = true
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) xServer.injectPointerMove(x, y)
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (leftDown) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        leftDown = false
                    }
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (leftDown) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                    leftDown = false
                }
            }
        }
        return true
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
        fun open(context: Context) {
            if (DisplayServer.xServer == null) return
            context.startActivity(
                Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
