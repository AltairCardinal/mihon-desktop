package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pager implementation that listens for tap, long tap, and swipe gestures, supporting
 * E-Ink water ripple wipe transitions and drag suppression.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    enum class SwipeDirection {
        LEFT,
        RIGHT,
        UP,
        DOWN,
    }

    enum class RippleDirection {
        RIGHT_TO_LEFT,
        LEFT_TO_RIGHT,
        BOTTOM_TO_TOP,
        TOP_TO_BOTTOM,
    }

    /**
     * Whether E-Ink water ripple page turn animation and swipe gesture direct-turning are enabled.
     */
    var pageTurnRippleEnabled: Boolean = false

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /**
     * Swipe listener function to execute when a swipe gesture is detected.
     */
    var swipeListener: ((SwipeDirection) -> Unit)? = null

    internal var currentRippleBitmap: Bitmap? = null
    internal var rippleDirection: RippleDirection = RippleDirection.RIGHT_TO_LEFT
    internal var rippleProgress: Float = 1f
    private var rippleRunnable: Runnable? = null

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipeDispatched = false

    private val waveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val waveShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Gesture listener that implements tap, long tap, and fling gestures.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            if (swipeDispatched) return true
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            if (swipeDispatched) return
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (pageTurnRippleEnabled && !swipeDispatched) {
                val startX = e1?.x ?: downX
                val startY = e1?.y ?: downY
                val dx = e2.x - startX
                val dy = e2.y - startY
                val minFlingDistance = touchSlop * 2
                if (isHorizontal) {
                    if (abs(dx) >= minFlingDistance && abs(dx) > abs(dy)) {
                        swipeDispatched = true
                        val direction = if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                        swipeListener?.invoke(direction)
                        return true
                    }
                } else {
                    if (abs(dy) >= minFlingDistance && abs(dy) > abs(dx)) {
                        swipeDispatched = true
                        val direction = if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
                        swipeListener?.invoke(direction)
                        return true
                    }
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipeDispatched = false
            }
            MotionEvent.ACTION_UP -> {
                if (pageTurnRippleEnabled && !swipeDispatched) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val minSwipeDistance = touchSlop * 3
                    if (isHorizontal) {
                        if (abs(dx) >= minSwipeDistance && abs(dx) > abs(dy)) {
                            swipeDispatched = true
                            val direction = if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                            swipeListener?.invoke(direction)
                        }
                    } else {
                        if (abs(dy) >= minSwipeDistance && abs(dy) > abs(dx)) {
                            swipeDispatched = true
                            val direction = if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
                            swipeListener?.invoke(direction)
                        }
                    }
                }
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. When [pageTurnRippleEnabled] is true,
     * dragging is suppressed so pages do not track the user's finger.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pageTurnRippleEnabled) {
            return false
        }
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** Start the pager's distance/velocity history where a zoomed direct child yields its drag. */
    internal fun rebaseDragFromChild(child: View, event: MotionEvent) {
        require(child.parent === this)
        val down = MotionEvent.obtain(event)
        try {
            down.action = MotionEvent.ACTION_DOWN
            down.offsetLocation((child.left - scrollX).toFloat(), (child.top - scrollY).toFloat())
            super.onInterceptTouchEvent(down)
        } finally {
            down.recycle()
        }
    }

    /**
     * Handles a touch event. When [pageTurnRippleEnabled] is true, touches are consumed directly
     * without calling ViewPager's dragging implementation.
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pageTurnRippleEnabled) {
            return true
        }
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }

    /**
     * Navigates to [targetItem] with a water ripple wipe animation.
     */
    fun setCurrentItemWithRipple(targetItem: Int, direction: RippleDirection) {
        if (targetItem == currentItem || targetItem < 0 || targetItem >= (adapter?.count ?: 0)) {
            return
        }
        if (width <= 0 || height <= 0) {
            setCurrentItem(targetItem, false)
            return
        }

        cancelRipple()

        val snapshot = try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
            canvas.restore()
            bitmap
        } catch (e: Throwable) {
            null
        }

        if (snapshot == null) {
            setCurrentItem(targetItem, false)
            return
        }

        currentRippleBitmap = snapshot
        rippleDirection = direction
        rippleProgress = 0f

        setCurrentItem(targetItem, false)

        startRippleAnimation()
    }

    private fun startRippleAnimation() {
        val totalSteps = 8
        val stepDurationMs = 40L // ~320ms total duration, 8 discrete wipe steps (KOReader default)
        var currentStep = 0

        val runnable = object : Runnable {
            override fun run() {
                currentStep++
                if (currentStep >= totalSteps) {
                    cleanupRipple()
                } else {
                    rippleProgress = currentStep.toFloat() / totalSteps.toFloat()
                    invalidate()
                    postDelayed(this, stepDurationMs)
                }
            }
        }
        rippleRunnable = runnable
        postDelayed(runnable, stepDurationMs)
    }

    fun cancelRipple() {
        rippleRunnable?.let(::removeCallbacks)
        rippleRunnable = null
        cleanupRipple()
    }

    private fun cleanupRipple() {
        rippleRunnable?.let(::removeCallbacks)
        rippleRunnable = null
        currentRippleBitmap?.recycle()
        currentRippleBitmap = null
        rippleProgress = 1f
        invalidate()
    }

    public override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        val bitmap = currentRippleBitmap
        if (bitmap != null && !bitmap.isRecycled && rippleProgress < 1f) {
            val w = width.toFloat()
            val h = height.toFloat()
            val sx = scrollX.toFloat()
            val sy = scrollY.toFloat()
            val p = rippleProgress
            val shadowWidth = 16f * density

            canvas.save()
            canvas.translate(sx, sy)

            when (rippleDirection) {
                RippleDirection.RIGHT_TO_LEFT -> {
                    val splitX = w * (1f - p)
                    canvas.save()
                    canvas.clipRect(0f, 0f, splitX, h)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)

                    if (splitX > 0f) {
                        waveShadowPaint.shader = LinearGradient(
                            max(0f, splitX - shadowWidth),
                            0f,
                            splitX,
                            0f,
                            Color.TRANSPARENT,
                            0x55000000.toInt(),
                            Shader.TileMode.CLAMP,
                        )
                        canvas.drawRect(max(0f, splitX - shadowWidth), 0f, splitX, h, waveShadowPaint)
                    }
                    canvas.restore()

                    canvas.drawLine(splitX, 0f, splitX, h, waveLinePaint)
                }
                RippleDirection.LEFT_TO_RIGHT -> {
                    val splitX = w * p
                    canvas.save()
                    canvas.clipRect(splitX, 0f, w, h)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)

                    if (splitX < w) {
                        waveShadowPaint.shader = LinearGradient(
                            splitX,
                            0f,
                            min(w, splitX + shadowWidth),
                            0f,
                            0x55000000.toInt(),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP,
                        )
                        canvas.drawRect(splitX, 0f, min(w, splitX + shadowWidth), h, waveShadowPaint)
                    }
                    canvas.restore()

                    canvas.drawLine(splitX, 0f, splitX, h, waveLinePaint)
                }
                RippleDirection.BOTTOM_TO_TOP -> {
                    val splitY = h * (1f - p)
                    canvas.save()
                    canvas.clipRect(0f, 0f, w, splitY)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)

                    if (splitY > 0f) {
                        waveShadowPaint.shader = LinearGradient(
                            0f,
                            max(0f, splitY - shadowWidth),
                            0f,
                            splitY,
                            Color.TRANSPARENT,
                            0x55000000.toInt(),
                            Shader.TileMode.CLAMP,
                        )
                        canvas.drawRect(0f, max(0f, splitY - shadowWidth), w, splitY, waveShadowPaint)
                    }
                    canvas.restore()

                    canvas.drawLine(0f, splitY, w, splitY, waveLinePaint)
                }
                RippleDirection.TOP_TO_BOTTOM -> {
                    val splitY = h * p
                    canvas.save()
                    canvas.clipRect(0f, splitY, w, h)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)

                    if (splitY < h) {
                        waveShadowPaint.shader = LinearGradient(
                            0f,
                            splitY,
                            0f,
                            min(h, splitY + shadowWidth),
                            0x55000000.toInt(),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP,
                        )
                        canvas.drawRect(0f, splitY, w, min(h, splitY + shadowWidth), waveShadowPaint)
                    }
                    canvas.restore()

                    canvas.drawLine(0f, splitY, w, splitY, waveLinePaint)
                }
            }

            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRipple()
    }
}
