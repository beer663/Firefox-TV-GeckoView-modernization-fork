/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.browser.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.GeckoView

/** One pointer for the homepage, browser controls and Gecko page. */
class TvCursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), Choreographer.FrameCallback {

    var scrollPage: ((Float, Float, Int, Int) -> Boolean)? = null

    private val motion = RemoteCursorMotion()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val consumed = mutableSetOf<Int>()

    private var framePending = false
    private var previousFrame = 0L
    private var gesture = false
    private var downTime = 0L
    private var lastInput = 0L

    private val hide = Runnable { invalidate() }

    fun keyboardVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(this)

        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            return true
        }

        val frame = Rect()
        rootView.getWindowVisibleDisplayFrame(frame)

        return rootView.height - frame.height() >
            maxOf(
                (120 * resources.displayMetrics.density).toInt(),
                rootView.height / 5
            )
    }

    fun handleRemoteKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteCursorMotion.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteCursorMotion.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> RemoteCursorMotion.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteCursorMotion.DOWN
            else -> 0
        }

        val select =
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (direction == 0 && !select) return false

        // Consume the matching release even if its click opened the keyboard.
        if (event.action == KeyEvent.ACTION_UP &&
            consumed.remove(event.keyCode)
        ) {
            if (direction != 0) {
                motion.key(direction, false)
            }

            if (select && gesture) {
                sendTouch(
                    if (event.isCanceled) {
                        MotionEvent.ACTION_CANCEL
                    } else {
                        MotionEvent.ACTION_UP
                    }
                )
                gesture = false
            }

            reveal()
            return true
        }

        val accessibility =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as AccessibilityManager

        val typing =
            event.device?.keyboardType ==
                InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            findFocus()?.onCheckIsTextEditor() == true

        if (!hasWindowFocus() ||
            keyboardVisible() ||
            accessibility.isTouchExplorationEnabled ||
            typing
        ) {
            pausePointer()
            return false
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        consumed.add(event.keyCode)
        reveal()

        if (direction != 0) {
            motion.key(direction, true)
            scheduleFrame()
        } else if (event.repeatCount == 0 && !gesture) {
            downTime = SystemClock.uptimeMillis()
            gesture = sendTouch(MotionEvent.ACTION_DOWN)
        }

        return true
    }

    private fun reveal() {
        lastInput = SystemClock.uptimeMillis()
        removeCallbacks(hide)
        postDelayed(hide, 3000)
        invalidate()
    }

    private fun scheduleFrame() {
        if (!framePending && motion.moving()) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false

        if (!hasWindowFocus() || keyboardVisible()) {
            pausePointer()
            return
        }

        val dt = if (previousFrame == 0L) {
            1f / 60
        } else {
            (frameTimeNanos - previousFrame) / 1_000_000_000f
        }

        previousFrame = frameTimeNanos
        motion.step(dt)

        if (gesture) {
            sendTouch(MotionEvent.ACTION_MOVE)
        } else {
            val dx = motion.scrollX.toInt()
            val dy = motion.scrollY.toInt()

            if (dx != 0 || dy != 0) {
                if (!scrollBrowser(dx, dy)) {
                    scrollNative(
                        this,
                        motion.x,
                        motion.y,
                        dx,
                        dy
                    )
                }
            }
        }

        reveal()

        if (motion.moving()) {
            scheduleFrame()
        } else {
            previousFrame = 0
        }
    }

    private fun scrollBrowser(dx: Int, dy: Int): Boolean {
        val scroll = scrollPage ?: return false
        if (scroll(motion.x, motion.y, dx, dy)) return true

        // Up reaches the window edge above the toolbar, outside the webpage.
        // Keep the pointer free to reach the toolbar, but send the scroll from
        // a point inside the visible GeckoView so the browser accepts it.
        val page = findVisiblePage(this) ?: return false
        val bounds = Rect()

        if (!page.getLocalVisibleRect(bounds) ||
            bounds.width() < 3 ||
            bounds.height() < 3
        ) {
            return false
        }

        val pageOrigin = IntArray(2)
        val rootOrigin = IntArray(2)

        page.getLocationInWindow(pageOrigin)
        getLocationInWindow(rootOrigin)

        bounds.offset(
            pageOrigin[0] - rootOrigin[0],
            pageOrigin[1] - rootOrigin[1]
        )

        return scroll(
            motion.x.coerceIn(bounds.left + 1f, bounds.right - 1f),
            motion.y.coerceIn(bounds.top + 1f, bounds.bottom - 1f),
            dx,
            dy
        )
    }

    private fun findVisiblePage(view: View): GeckoView? {
        if (!view.isShown) return null
        if (view is GeckoView) return view

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                findVisiblePage(view.getChildAt(i))?.let {
                    return it
                }
            }
        }

        return null
    }

    fun dispatchSelectClick(): Boolean {
        pausePointer()
        downTime = SystemClock.uptimeMillis()

        val handled = sendTouch(MotionEvent.ACTION_DOWN)
        sendTouch(MotionEvent.ACTION_UP)
        reveal()

        return handled
    }

    private fun sendTouch(action: Int): Boolean {
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            motion.x,
            motion.y,
            0
        )

        event.source = InputDevice.SOURCE_TOUCHSCREEN

        return try {
            super.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun scrollNative(
        view: View,
        x: Float,
        y: Float,
        dx: Int,
        dy: Int
    ): Boolean {
        if (!view.isShown ||
            x < 0 ||
            y < 0 ||
            x >= view.width ||
            y >= view.height
        ) {
            return false
        }

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)

                if (scrollNative(
                        child,
                        x + view.scrollX - child.left,
                        y + view.scrollY - child.top,
                        dx,
                        dy
                    )
                ) {
                    return true
                }
            }
        }

        if ((dy != 0 &&
                view.canScrollVertically(if (dy > 0) 1 else -1)) ||
            (dx != 0 &&
                view.canScrollHorizontally(if (dx > 0) 1 else -1))
        ) {
            view.scrollBy(dx, dy)
            return true
        }

        return false
    }

    fun pausePointer() {
        if (gesture) {
            sendTouch(MotionEvent.ACTION_CANCEL)
        }

        gesture = false
        motion.stop()

        Choreographer.getInstance().removeFrameCallback(this)

        framePending = false
        previousFrame = 0
        lastInput = 0

        removeCallbacks(hide)
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (!hasWindowFocus) {
            pausePointer()
        }
    }

    override fun onDetachedFromWindow() {
        pausePointer()
        consumed.clear()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        motion.resize(w.toFloat(), h.toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (lastInput == 0L ||
            SystemClock.uptimeMillis() - lastInput >= 3000
        ) {
            return
        }

        val radius = 8 * resources.displayMetrics.density

        paint.style = Paint.Style.FILL
        paint.color = if (gesture) {
            Color.rgb(66, 165, 245)
        } else {
            Color.rgb(35, 38, 44)
        }

        canvas.drawCircle(
            motion.x,
            motion.y,
            radius,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        paint.color = Color.WHITE

        canvas.drawCircle(
            motion.x,
            motion.y,
            radius,
            paint
        )
    }
}
