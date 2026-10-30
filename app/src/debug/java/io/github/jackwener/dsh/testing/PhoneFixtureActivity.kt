package io.github.jackwener.dsh.testing

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.jackwener.dsh.R
import java.util.concurrent.atomic.AtomicInteger

/** Test-only native widgets and canvas. No network, private user data or externally exported control surface. */
class PhoneFixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var canvas: CanvasPage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        taps.set(0); delayedTaps.set(0); canvasText = "TARGET ALPHA"; animated = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 100, 24, 60); setBackgroundColor(Color.WHITE) }
        fun label(value: String) = TextView(this).apply { text = value; textSize = 18f; setTextColor(Color.BLACK); root.addView(this) }
        label("DSH native phone test fixture")
        label("Deterministic local data")
        status = label("Count 0")
        label("Ready for native tools")
        when (intent.getStringExtra("scenario") ?: "form") {
            "longtext" -> label(LONG_TEXT)
            "list" -> {
                val scroll = ScrollView(this).apply { id = R.id.phone_fixture_list }
                val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (i in 1..60) rows.addView(TextView(this).apply {
                    text = row(i); textSize = 22f; setTextColor(Color.BLACK); setPadding(20, 28, 20, 28)
                    minHeight = (80 * resources.displayMetrics.density).toInt()
                })
                scroll.addView(rows); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            "canvas" -> {
                canvas = CanvasPage(this)
                root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            else -> {
                val input = EditText(this).apply { id = R.id.phone_fixture_input; hint = "Test input"; setSingleLine() }
                root.addView(input)
                root.addView(Button(this).apply { isAllCaps = false; text = "Count once"; setOnClickListener { status.text = "Count ${taps.incrementAndGet()}" } })
                root.addView(Button(this).apply { isAllCaps = false; text = "Delayed result"; setOnClickListener {
                    delayedTaps.incrementAndGet()
                    handler.postDelayed({ status.text = "Delayed ready" }, intent.getLongExtra("delayMs", 900))
                } })
                root.addView(Button(this).apply { isAllCaps = false; text = "Read input"; setOnClickListener { status.text = "Input: ${input.text}" } })
                repeat(2) { root.addView(Button(this).apply { isAllCaps = false; text = "Duplicate"; setOnClickListener { taps.incrementAndGet() } }) }
            }
        }
        setContentView(root)
    }
    fun updateCanvas(text: String, moving: Boolean = false) { canvasText = text; animated = moving; canvas.invalidate() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (current === this) current = null; super.onDestroy() }

    private inner class CanvasPage(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f }
        private var tick = 0
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.WHITE)
            if (animated) {
                tick++
                for (i in 0..10) {
                    paint.color = if ((tick + i) % 2 == 0) Color.RED else Color.BLUE
                    c.drawRect(0f, i * height / 11f, width.toFloat(), (i + 1) * height / 11f, paint)
                    paint.color = Color.WHITE
                    c.drawText("MOVING ${tick * 17 + i}", (tick * 23 % 130).toFloat(), (i + 1) * height / 11f - 20f, paint)
                }
                postInvalidateDelayed(37)
            } else {
                paint.color = Color.BLACK
                c.drawText("CANVAS READY", 80f, 160f, paint)
                c.drawText(canvasText, 80f, 360f, paint)
                c.drawText("VERIFY FRAME", 80f, 560f, paint)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && e.y in 260f..400f) {
                status.text = "Canvas count ${taps.incrementAndGet()}"
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    companion object {
        @Volatile var current: PhoneFixtureActivity? = null
        val taps = AtomicInteger()
        val delayedTaps = AtomicInteger()
        @Volatile var canvasText = "TARGET ALPHA"
        @Volatile var animated = false
        fun row(i: Int) = if (i % 8 == 0) "Repeated value" else "Row ${i.toString().padStart(2, '0')}"
        val LONG_TEXT = "A native paragraph must not be silently cut at 120 characters. ".repeat(8).trim()
    }
}
