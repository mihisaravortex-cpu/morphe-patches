
package app.morphe.patches

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.sin

/**
 * BOUNCING BUDDY
 *
 * Custom YouTube loading animation for Morphe.
 *
 * States:
 * - Buffering: Bouncing Buddy
 * - Extended buffering: Angry Buddy
 * - Confirmed network disconnection: Dead Buddy
 * - Playback resumed: Buddy disappears
 */

// ============================================================
// 1. BOUNCING BUDDY VIEW
// ============================================================

class BouncingBuddyView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val DEFAULT_ANGRY_DELAY = 3000L
    }

    enum class BuddyState {
        IDLE,
        BUFFERING,
        DISCONNECTED
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
            removeCallbacks(frameRunnable)

            if (value) {
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
        color = 0xFF292020.toInt()
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (
                state == BuddyState.BUFFERING &&
                animationEnabled &&
                isAttachedToWindow &&
                visibility == VISIBLE
            ) {
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    private fun scheduleFrame() {
        if (
            state == BuddyState.BUFFERING &&
            animationEnabled &&
            isAttachedToWindow &&
            visibility == VISIBLE
        ) {
            removeCallbacks(frameRunnable)
            postOnAnimation(frameRunnable)
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

        if (
            state == BuddyState.IDLE ||
            width == 0 ||
            height == 0
        ) return

        val disconnected = state == BuddyState.DISCONNECTED

        val elapsed =
            SystemClock.uptimeMillis() - bufferStartTime

        val angry = !disconnected && elapsed >= angryDelay

        val reducedMotion =
            accessibilityManager?.isEnabled == true &&
            accessibilityManager.isTouchExplorationEnabled

        val now = SystemClock.uptimeMillis()

        val frequency =
            if (angry) angryBounceSpeed else normalBounceSpeed

        val amplitude =
            minOf(width, height) *
            if (angry) 0.12f else 0.065f

        val jump =
            if (disconnected || !animationEnabled || reducedMotion) {
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

        // Schedule a redraw at the angry-state threshold.
        if (!angry && !disconnected) {
            val remaining = angryDelay - elapsed

            if (remaining > 0L) {
                postInvalidateDelayed(remaining)
            }
        }

        scheduleFrame()
    }

    // --------------------------------------------------------
    // ANGRY BUDDY
    // --------------------------------------------------------

    private fun drawAngryBuddy(
        canvas: Canvas,
        r: Float
    ) {
        outlinePaint.strokeWidth = r * 0.055f
        mouthPaint.strokeWidth = r * 0.085f

        // Arms
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

        // Legs
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

        // Round face
        canvas.drawCircle(0f, 0f, r, facePaint)
        canvas.drawCircle(0f, 0f, r, outlinePaint)

        // Angry eyebrows
        val brows = Path().apply {
            moveTo(-r * 0.65f, -r * 0.40f)
            lineTo(-r * 0.20f, -r * 0.24f)

            moveTo(r * 0.65f, -r * 0.40f)
            lineTo(r * 0.20f, -r * 0.24f)
        }

        canvas.drawPath(brows, mouthPaint)

        // Eyes
        canvas.drawOval(
            RectF(
                -r * 0.52f, -r * 0.22f,
                -r * 0.20f, r * 0.20f
            ),
            eyePaint
        )

        canvas.drawOval(
            RectF(
                r * 0.20f, -r * 0.22f,
                r * 0.52f, r * 0.20f
            ),
            eyePaint
        )

        // Angry mouth
        val mouth = Path().apply {
            moveTo(-r * 0.32f, r * 0.48f)
            quadraticTo(
                0f, r * 0.24f,
                r * 0.32f, r * 0.48f
            )
        }

        canvas.drawPath(mouth, mouthPaint)
    }

    // --------------------------------------------------------
    // DEAD BUDDY
    // --------------------------------------------------------

    private fun drawDeadBuddy(
        canvas: Canvas,
        r: Float
    ) {
        // Skull only. No arms, no legs.

        canvas.drawOval(
            RectF(
                -r * 0.78f,
                -r,
                r * 0.78f,
                r * 0.65f
            ),
            skullPaint
        )

        // Jaw
        canvas.drawRoundRect(
            RectF(
                -r * 0.52f,
                r * 0.38f,
                r * 0.52f,
                r * 0.90f
            ),
            r * 0.15f,
            r * 0.15f,
            skullPaint
        )

        // Eye sockets
        canvas.drawOval(
            RectF(
                -r * 0.57f, -r * 0.20f,
                -r * 0.12f, r * 0.30f
            ),
            eyePaint
        )

        canvas.drawOval(
            RectF(
                r * 0.12f, -r * 0.20f,
                r * 0.57f, r * 0.30f
            ),
            eyePaint
        )

        // Nose
        val nose = Path().apply {
            moveTo(0f, r * 0.10f)
            lineTo(-r * 0.10f, r * 0.42f)
            lineTo(r * 0.10f, r * 0.42f)
            close()
        }

        canvas.drawPath(nose, eyePaint)

        // Teeth
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


// ============================================================
// 2. BOUNCING BUDDY PATCH CONTROLLER
// ============================================================

object BouncingBuddyPatch {

    private data class BuddyEntry(
        val parent: ViewGroup,
        val buddy: BouncingBuddyView,
        val originalVisibility: Int
    )

    private val entries =
        WeakHashMap<View, BuddyEntry>()

    private fun checkMainThread() {
        check(
            Looper.myLooper() == Looper.getMainLooper()
        ) {
            "Bouncing Buddy operations must run on the main thread."
        }
    }

    /**
     * Inject Buddy into the spinner's parent.
     */
    fun injectBuddy(loadingSpinnerView: View) {
        checkMainThread()

        if (entries.containsKey(loadingSpinnerView)) return

        val parent =
            loadingSpinnerView.parent as? ViewGroup ?: return

        val originalVisibility =
            loadingSpinnerView.visibility

        val originalIndex =
            parent.indexOfChild(loadingSpinnerView)

        val originalParams =
            loadingSpinnerView.layoutParams

        val buddy = BouncingBuddyView(
            loadingSpinnerView.context
        ).apply {
            tag = "bouncing_buddy_tag"

            visibility = View.GONE

            layoutParams = originalParams?.let {
                when (it) {
                    is ViewGroup.MarginLayoutParams ->
                        ViewGroup.MarginLayoutParams(it)

                    else ->
                        ViewGroup.LayoutParams(it)
                }
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        parent.addView(
            buddy,
            originalIndex.coerceAtLeast(0)
        )

        loadingSpinnerView.visibility = View.GONE

        entries[loadingSpinnerView] = BuddyEntry(
            parent = parent,
            buddy = buddy,
            originalVisibility = originalVisibility
        )
    }

    /**
     * Called when buffering starts.
     */
    fun onBufferingStarted(
        loadingSpinnerView: View
    ) {
        checkMainThread()

        val entry =
            entries[loadingSpinnerView] ?: return

        entry.buddy.setNetworkDisconnected(false)
        entry.buddy.startBuffering()
    }

    /**
     * Call only after confirming network disconnection.
     */
    fun onNetworkDisconnected(
        loadingSpinnerView: View
    ) {
        checkMainThread()

        val entry =
            entries[loadingSpinnerView] ?: return

        entry.buddy.setNetworkDisconnected(true)
    }

    /**
     * Called when playback resumes.
     */
    fun onPlaybackReady(
        loadingSpinnerView: View
    ) {
        checkMainThread()

        val entry =
            entries[loadingSpinnerView] ?: return

        entry.buddy.stopBuffering()
    }

    /**
     * Remove Buddy and restore the original spinner.
     */
    fun removeBuddy(
        loadingSpinnerView: View
    ) {
        checkMainThread()

        val entry =
            entries.remove(loadingSpinnerView) ?: return

        entry.buddy.stopBuffering()

        entry.parent.removeView(entry.buddy)

        loadingSpinnerView.visibility =
            entry.originalVisibility
    }

    /**
     * Change Buddy animation settings.
     */
    fun configure(
        loadingSpinnerView: View,
        enabled: Boolean = true,
        size: Float = 0.48f,
        angryDelay: Long = 3000L
    ) {
        checkMainThread()

        val buddy =
            entries[loadingSpinnerView]?.buddy ?: return

        buddy.animationEnabled = enabled
        buddy.buddySize = size
        buddy.angryDelay = angryDelay
    }
}
