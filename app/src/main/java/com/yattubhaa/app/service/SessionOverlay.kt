package com.yattubhaa.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What stays on top of other apps while a screen is being shared: a large red Stop button he can
 * always find, and the ring the helper points with. Both are removed the moment the session
 * ends. Must be used from the main thread, and only once "display over other apps" is allowed.
 *
 * These windows are real on-screen content, so the screen picture sent to the helper includes
 * them too (the ring, the banner, the Stop button) — tried marking them FLAG_SECURE to keep them
 * out of that picture, but on this Android version that blanks the *entire* captured frame to
 * black, not just this window's own pixels, so that is not usable here.
 */
class SessionOverlay(private val context: Context, private val onStop: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var stopButton: Button? = null
    private var pointer: PointerView? = null
    private var question: View? = null
    private var banner: TextView? = null

    fun show() {
        if (stopButton != null) return
        addStopButton()
        addPointerLayer()
    }

    /** Fractions of the screen, or null to remove the ring. */
    fun point(x: Float?, y: Float?) {
        pointer?.target = if (x != null && y != null) x to y else null
    }

    fun remove() {
        pointer?.stopAnimating()
        listOfNotNull(stopButton, pointer, question, banner).forEach { runCatching { windows.removeView(it) } }
        stopButton = null
        pointer = null
        question = null
        banner = null
    }

    /**
     * A full-screen question with two big buttons, used when the helper asks to tap for them.
     * Full screen on purpose: it has to be impossible to miss, and to answer by accident.
     */
    fun askQuestion(text: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
        hideQuestion()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(72), dp(24), dp(72))
            addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(bigButton(yes, Color.rgb(10, 61, 145), Color.WHITE) { hideQuestion(); onYes() })
            addView(bigButton(no, Color.rgb(224, 224, 224), Color.BLACK) { hideQuestion(); onNo() })
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(column)
        }
        windows.addView(scroll, fullScreenParams(touchable = true))
        question = scroll
    }

    fun hideQuestion() {
        question?.let { runCatching { windows.removeView(it) } }
        question = null
    }

    /** A red strip across the top for as long as the helper can tap for them; null removes it. */
    fun setBanner(text: String?) {
        if (text == null) {
            banner?.let { runCatching { windows.removeView(it) } }
            banner = null
            return
        }
        banner?.let { it.text = text; return }
        val view = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            // See-through enough that a menu or button sitting right behind it still shows
            // through; the window itself stays fully opaque (alpha 1) so the text stays crisp
            // rather than washed out, and a text shadow keeps it readable over anything behind it.
            setBackgroundColor(Color.argb(190, 176, 0, 32))
            setShadowLayer(4f * resources.displayMetrics.density, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(12))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }
        windows.addView(view, params)
        banner = view
    }

    private fun bigButton(label: String, background: Int, textColor: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(textColor)
        setBackgroundColor(background)
        setPadding(dp(16), dp(20), dp(16), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) }
        setOnClickListener { onClick() }
    }

    private fun fullScreenParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    private fun addStopButton() {
        val button = Button(context).apply {
            text = "STOP SHARING"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(176, 0, 32))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(12)
            y = dp(96) // clear of the navigation bar
        }
        windows.addView(button, params)
        stopButton = button
    }

    private fun addPointerLayer() {
        val view = PointerView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ blocks touches that pass through an overlay more opaque than this, and
            // the whole point is that he can tap the app underneath the ring.
            alpha = 0.8f
        }
        windows.addView(view, params)
        pointer = view
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** A pulsing ring with a filled centre, drawn where the helper pointed. */
    private class PointerView(context: Context) : View(context) {
        var target: Pair<Float, Float>? = null
            set(value) { field = value; invalidate() }

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(220, 0, 0)
            strokeWidth = 10f * resources.displayMetrics.density
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 213, 79) }
        private var pulse = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; if (target != null) invalidate() }
            start()
        }

        fun stopAnimating() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val (fx, fy) = target ?: return
            val d = resources.displayMetrics.density
            val radius = (36f + 16f * pulse) * d
            canvas.drawCircle(fx * width, fy * height, radius, fill)
            canvas.drawCircle(fx * width, fy * height, radius, ring)
        }
    }
}
