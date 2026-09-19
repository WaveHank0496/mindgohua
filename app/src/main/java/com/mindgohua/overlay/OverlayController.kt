package com.mindgohua.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mindgohua.R
import com.mindgohua.core.DismissChoice
import com.mindgohua.core.InterruptReason
import com.mindgohua.settings.TargetApps

/**
 * Overlay 層（spec §6）。
 *
 * 用 SYSTEM_ALERT_WINDOW 疊一個全螢幕 View 蓋住當前 app。
 *
 * 防馴化設計（spec §6 的關鍵顧慮）：
 *  - 兩顆按鈕在 N 秒內都不可按（預設 3 秒），畫面上有倒數。
 *  - 「再滑一下」那顆還要**按住 2 秒**才生效 —— 讓它沒辦法退化成反射點掉的同意鍵。
 *  - 「停下來」那顆是單擊即可：我們不該讓「做對的事」比較費力。
 *
 * 已知限制：overlay 蓋不住系統關鍵畫面（權限框、部分系統設定）。這是 Android 的保護機制，
 * 屬正常行為，spec §6 明確說不用處理。
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var countdownRunnable: Runnable? = null

    val isShowing: Boolean get() = rootView != null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    /**
     * @param unlockDelaySeconds 按鈕解禁前的等待秒數。
     * @param onDismiss 使用者做出選擇。呼叫端負責通知 Session Engine。
     */
    fun show(
        elapsedMs: Long,
        packageName: String?,
        reason: InterruptReason,
        unlockDelaySeconds: Int,
        graceMinutes: Int,
        onDismiss: (DismissChoice) -> Unit,
    ) {
        if (!canDrawOverlay()) return
        if (isShowing) return

        val view = buildView(elapsedMs, packageName, reason, unlockDelaySeconds, graceMinutes, onDismiss)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 FLAG_NOT_TOUCHABLE / FLAG_NOT_TOUCH_MODAL：我們要吃掉所有觸控，
            // 否則使用者可以隔著貓繼續滑下面的 IG。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess { rootView = view }
    }

    fun hide() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        rootView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        rootView = null
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildView(
        elapsedMs: Long,
        packageName: String?,
        reason: InterruptReason,
        unlockDelaySeconds: Int,
        graceMinutes: Int,
        onDismiss: (DismissChoice) -> Unit,
    ): View {
        // 攔截返回鍵：不然一鍵 back 就把打斷跳過了。
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }
        root.setBackgroundColor(Color.parseColor("#F0121016"))
        root.isClickable = true
        root.isFocusable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }

        val cat = ImageView(context).apply {
            setImageResource(R.drawable.ic_cat)
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
        }

        val minutes = elapsedMs / 60_000
        val seconds = (elapsedMs % 60_000) / 1000
        val duration = if (minutes > 0) "$minutes 分 $seconds 秒" else "$seconds 秒"
        // 用吃 Context 的版本向系統問真名 —— 否則名單外的 app（例如 Threads）
        // 會在貓身上顯示成「com.instagram.barcelona」。
        val appLabel = TargetApps.labelOf(context, packageName)

        val headline = TextView(context).apply {
            text = when (reason) {
                InterruptReason.DURATION -> "你已經在 $appLabel 連續待了 $duration。"
                InterruptReason.SCROLL_RHYTHM -> "你已經無意識地滑了一陣子了。"
            }
            setTextColor(Color.parseColor("#F5E9C9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        }

        val subline = TextView(context).apply {
            text = "還要繼續嗎？"
            setTextColor(Color.parseColor("#A8A2B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        }

        val stopButton = makeButton(
            label = "停下來",
            textColor = "#22202A",
            background = "#F5E9C9",
        )

        val snoozeButton = makeButton(
            label = "再滑 $graceMinutes 分鐘",
            textColor = "#A8A2B8",
            background = "#2A2733",
        )

        val hint = TextView(context).apply {
            setTextColor(Color.parseColor("#6E6880"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        column.addView(cat)
        column.addView(headline)
        column.addView(subline)
        column.addView(stopButton)
        column.addView(snoozeButton)
        column.addView(hint)

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER },
        )

        // ---- 防馴化 1：解禁倒數 ----
        var remaining = unlockDelaySeconds
        stopButton.isEnabled = false
        snoozeButton.isEnabled = false
        stopButton.alpha = 0.4f
        snoozeButton.alpha = 0.4f

        fun unlock() {
            stopButton.isEnabled = true
            snoozeButton.isEnabled = true
            stopButton.alpha = 1f
            snoozeButton.alpha = 1f
            hint.text = "「再滑一下」需要按住 2 秒"
        }

        if (remaining <= 0) {
            unlock()
        } else {
            hint.text = "$remaining 秒後可以選擇"
            val tick = object : Runnable {
                override fun run() {
                    remaining--
                    if (remaining <= 0) {
                        unlock()
                    } else {
                        hint.text = "$remaining 秒後可以選擇"
                        handler.postDelayed(this, 1000L)
                    }
                }
            }
            countdownRunnable = tick
            handler.postDelayed(tick, 1000L)
        }

        stopButton.setOnClickListener {
            goHome()
            onDismiss(DismissChoice.STOP)
        }

        // ---- 防馴化 2：「再滑一下」要按住 2 秒 ----
        val holdRunnable = Runnable {
            onDismiss(DismissChoice.SNOOZE)
        }
        var holdProgress: Runnable? = null
        snoozeButton.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.postDelayed(holdRunnable, HOLD_MS)
                    var elapsed = 0L
                    val progress = object : Runnable {
                        override fun run() {
                            elapsed += 100
                            val pct = (elapsed * 100 / HOLD_MS).coerceAtMost(100)
                            snoozeButton.text = "再滑 $graceMinutes 分鐘… $pct%"
                            if (elapsed < HOLD_MS) handler.postDelayed(this, 100L)
                        }
                    }
                    holdProgress = progress
                    handler.postDelayed(progress, 100L)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    holdProgress?.let { handler.removeCallbacks(it) }
                    snoozeButton.text = "再滑 $graceMinutes 分鐘"
                    true
                }

                else -> false
            }
        }

        return root
    }

    private fun makeButton(label: String, textColor: String, background: String): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            this.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(background))
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(260),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(home) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val HOLD_MS = 2_000L
    }
}
