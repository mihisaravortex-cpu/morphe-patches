
package app.morphe.patches.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityManager
import kotlin.math.abs
import kotlin.math.sin

class BouncingBuddyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val DEFAULT_ANGRY_DELAY = 3000L
    }

    enum class BuddyState {
        IDLE, BUFFERING, DISCONNECTED
    }

    var state = BuddyState.IDLE
        private set

    private var bufferStartTime = 0L

    var angryDelay = DEFAULT_ANGRY_DELAY
        set(value) {
            field = value.coerceAtLeast(0L)
            invalidate()
        }

    var buddySize = 0.48f
        set(value) {
            field = value.coerceIn(0.2f, 0.8f)
            invalidate()
        }

    var animationEnabled = true
        set(value) {
            field = value
            if (!value) {
                removeCallbacks(frameRunnable)
            } else {
                scheduleFrame()
            }
            invalidate()
        }

    var normalBounceSpeed = 0.006
        set(value) {
            field = value.coerceIn(0.002, 0.02)
        }

    var angryBounceSpeed = 0.012
        set(value) {
            field = value.coerceIn(0.004, 0.04)
        }

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFC928.toInt()
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF442B22.toInt()
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF292020.toInt()
    }

    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF442020.toInt()
    }

    private val skullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF4F0E8.toInt()
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF4F0E8.toInt()
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (state == BuddyState.BUFFERING &&
                animationEnabled &&
                isAttachedToWindow &&
                visibility == VISIBLE
            ) {
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    fun startBuffering() {
        if (state == BuddyState.BUFFERING) return

        state = BuddyState.BUFFERING
        bufferStartTime = SystemClock.uptimeMillis()

        visibility = VISIBLE
        removeCallbacks(frameRunnable)

        invalidate()
        scheduleFrame()
    }

    fun setNetworkDisconnected(disconnected: Boolean) {
        if (disconnected) {
            state = BuddyState.DISCONNECTED
            removeCallbacks(frameRunnable)
            visibility = VISIBLE
        } else if (state == BuddyState.DISCONNECTED) {
            state = BuddyState.IDLE
            visibility = GONE
        }

        invalidate()
    }

    fun stopBuffering() {
        state = BuddyState.IDLE
        removeCallbacks(frameRunnable)
        visibility = GONE
        invalidate()
    }

    private fun scheduleFrame() {
        if (state == BuddyState.BUFFERING &&
            animationEnabled &&
            isAttachedToWindow &&
            visibility == VISIBLE
        ) {
            removeCallbacks(frameRunnable)
            postOnAnimation(frameRunnable)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (state == BuddyState.IDLE || width == 0 || height == 0) {
            return
        }

        val disconnected = state == BuddyState.DISCONNECTED
        val angry = !disconnected &&
            SystemClock.uptimeMillis() - bufferStartTime >= angryDelay

        val reducedMotion =
            accessibilityManager?.isEnabled == true &&
            accessibilityManager.isTouchExplorationEnabled

        val now = SystemClock.uptimeMillis()

        val frequency = if (angry) angryBounceSpeed
                        else normalBounceSpeed

        val amplitude = minOf(width, height) *
            if (angry) 0.12f else 0.065f

        val jump = if (disconnected || !animationEnabled || reducedMotion) {
            0f
        } else {
            abs(sin(now * frequency)).toFloat()
        }

        val bounceY = -jump * amplitude

        val squash = jump * 0.08f
        val stretch = jump * 0.10f

        val scaleX = 1f + squash - stretch
        val scaleY = 1f - squash + stretch

        val centerX = width / 2f
        val centerY = height / 2f

        val radius = minOf(width, height) * buddySize * 0.5f

        canvas.save()
        canvas.translate(centerX, centerY + bounceY)
        canvas.scale(scaleX, scaleY)

        if (disconnected) {
            drawDeadBuddy(canvas, radius)
        } else {
            drawAngryBuddy(canvas, radius)
        }

        canvas.restore()

        if (state == BuddyState.BUFFERING) {
            // Ensure the angry expression updates at the threshold.
            if (!angry) {
                val remaining = angryDelay -
                    (SystemClock.uptimeMillis() - bufferStartTime)

                if (remaining > 0) {
                    postInvalidateDelayed(remaining)
                }
            }

            scheduleFrame()
        }
    }

    private fun drawAngryBuddy(canvas: Canvas, r: Float) {
        outlinePaint.strokeWidth = r * 0.055f
        mouthPaint.strokeWidth = r * 0.085f

        // Arms.
        val arms = Path().apply {
            moveTo(-r * 0.78f, r * 0.05f)
            cubicTo(
                -r * 1.05f, -r * 0.10f,
                -r * 1.15f, r * 0.25f,
                -r * 1.08f, r * 0.38f
            )

            moveTo(r * 0.78f, r * 0.05f)
            cubicTo(
                r * 1.05f, -r * 0.10f,
                r * 1.15f, r * 0.25f,
                r * 1.08f, r * 0.38f
            )
        }

        canvas.drawPath(arms, outlinePaint)

        // Legs.
        canvas.drawLine(
            -r * 0.35f, r * 0.85f,
            -r * 0.42f, r * 1.12f,
            outlinePaint
        )

        canvas.drawLine(
            r * 0.35f, r * 0.85f,
            r * 0.42f, r * 1.12f,
            outlinePaint
        )

        // Main round face.
        canvas.drawCircle(0f, 0f, r, facePaint)
        canvas.drawCircle(0f, 0f, r, outlinePaint)

        // Angry eyebrows.
        val brows = Path().apply {
            moveTo(-r * 0.65f, -r * 0.40f)
            lineTo(-r * 0.20f, -r * 0.24f)

            moveTo(r * 0.65f, -r * 0.40f)
            lineTo(r * 0.20f, -r * 0.24f)
        }

        canvas.drawPath(brows, mouthPaint)

        // Eyes.
        canvas.drawOval(
            RectF(-r * 0.52f, -r * 0.22f,
                  -r * 0.20f, r * 0.20f),
            eyePaint
        )

        canvas.drawOval(
            RectF(r * 0.20f, -r * 0.22f,
                  r * 0.52f, r * 0.20f),
            eyePaint
        )

        // Angry mouth.
        val mouth = Path().apply {
            moveTo(-r * 0.32f, r * 0.48f)
            quadraticTo(0f, r * 0.24f, r * 0.32f, r * 0.48f)
        }

        canvas.drawPath(mouth, mouthPaint)
    }

    private fun drawDeadBuddy(canvas: Canvas, r: Float) {
        // Plain skull only: no arms, no legs.
        val skullRect = RectF(
            -r * 0.78f,
            -r,
            r * 0.78f,
            r * 0.65f
        )

        canvas.drawOval(skullRect, skullPaint)

        // Jaw.
        canvas.drawRoundRect(
            RectF(-r * 0.52f, r * 0.38f,
                  r * 0.52f, r * 0.90f),
            r * 0.15f,
            r * 0.15f,
            skullPaint
        )

        // Eye sockets.
        val socketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF292020.toInt()
        }

        canvas.drawOval(
            RectF(-r * 0.57f, -r * 0.20f,
                  -r * 0.12f, r * 0.30f),
            socketPaint
        )

        canvas.drawOval(
            RectF(r * 0.12f, -r * 0.20f,
                  r * 0.57f, r * 0.30f),
            socketPaint
        )

        // Nose.
        val nose = Path().apply {
            moveTo(0f, r * 0.10f)
            lineTo(-r * 0.10f, r * 0.42f)
            lineTo(r * 0.10f, r * 0.42f)
            close()
        }

        canvas.drawPath(nose, socketPaint)

        // Teeth.
        bonePaint.strokeWidth = r * 0.055f

        canvas.drawLine(
            -r * 0.38f, r * 0.65f,
            r * 0.38f, r * 0.65f,
            bonePaint
        )

        canvas.drawLine(
            -r * 0.12f, r * 0.65f,
            -r * 0.12f, r * 0.85f,
            bonePaint
        )

        canvas.drawLine(
            r * 0.12f, r * 0.65f,
            r * 0.12f, r * 0.85f,
            bonePaint
        )
    }
}
