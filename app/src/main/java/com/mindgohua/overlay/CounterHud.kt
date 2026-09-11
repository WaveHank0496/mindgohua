package com.mindgohua.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 右上角的即時計數。
 *
 * 跟打斷用的 [OverlayController] 是兩個不同的視窗，設計目標也相反：
 * 那個要蓋住你、吃掉觸控；這個要盡量不打擾 ——
 * `FLAG_NOT_TOUCHABLE` 讓觸控完全穿透過去，你照樣可以滑下面的 IG。
 *
 * ⚠️ 設計上的疑慮（留給日後檢視）：
 * 一個持續跳動的數字有變成「計分板」的風險。spec 通篇在防馴化，
 * 而「今天 87 部」看兩週之後，可能從警訊變成成就感的來源。
 * 如果之後發現自己開始「想把數字滑高」，那就是這個功能該重新設計的訊號。
 */
class CounterHud(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    private var view: TextView? = null

    val isShowing: Boolean get() = view != null

    fun show(count: Int) {
        if (!Settings.canDrawOverlays(context)) return
        if (view != null) {
            update(count)
            return
        }

        val label = TextView(context).apply {
            text = format(count)
            setTextColor(Color.parseColor("#F5E9C9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#CC22202A"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCHABLE：觸控直接穿透，不影響下面的 app。
            // NOT_FOCUSABLE：不搶輸入焦點，打字時不會被它吃掉按鍵。
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            // 避開狀態列與瀏海
            y = dp(48)
        }

        runCatching { windowManager.addView(label, params) }
            .onSuccess { view = label }
    }

    fun update(count: Int) {
        val v = view ?: return
        handler.post { v.text = format(count) }
    }

    fun hide() {
        val v = view ?: return
        view = null
        handler.post { runCatching { windowManager.removeView(v) } }
    }

    private fun format(count: Int) = "▽ $count"

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
