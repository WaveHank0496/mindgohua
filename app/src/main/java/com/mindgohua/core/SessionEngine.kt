package com.mindgohua.core

/**
 * Session Engine（spec §5）。
 *
 * 這個類別**刻意不依賴任何 Android framework**：純函式狀態機，吃事件、吐 effect。
 * 好處有二：
 *  1. 可以完全單元測試（見 SessionEngineTest），不用實機也能驗證冷卻／門檻／GRACE 邏輯。
 *  2. 它不知道訊號來自 UsageStats 還是 AccessibilityService（spec §2 的「關鍵設計」），
 *     所以 Mode A / Mode B 可以熱插拔，共用同一套計時與打斷邏輯。
 *
 * 時間一律由呼叫端以參數傳入（`atMs`），內部不呼叫 System.currentTimeMillis()，
 * 測試才能自由操控時間軸。呼叫端應傳入 **單調時鐘**（SystemClock.elapsedRealtime），
 * 避免使用者調時區／校時把 session 洗掉。
 *
 * 注意：這裡不保存任何「歷史紀錄」。只有一個當下的 session 狀態，離開就沒了（spec §0.1）。
 */
class SessionEngine(config: EngineConfig) {

    var config: EngineConfig = config
        private set

    private sealed interface Phase {
        /** 沒有 session 在跑。 */
        data object Idle : Phase

        /** 目標 app 在前景、正在累計。 */
        data class Active(
            val resumedAtMs: Long,
            val accumulatedMs: Long,
            val packageName: String?,
        ) : Phase

        /** 目標 app 離開前景，但還在冷卻期內 —— 尚未歸零（spec §5 的 session 邊界問題）。 */
        data class Paused(
            val accumulatedMs: Long,
            val pausedAtMs: Long,
            val packageName: String?,
        ) : Phase
    }

    private var phase: Phase = Phase.Idle

    /** 這個時間點之前不再打斷（使用者剛解除過 overlay）。 */
    private var graceUntilMs: Long = Long.MIN_VALUE

    /** overlay 目前是否掛著，避免重複發 INTERRUPT。 */
    private var interruptShowing: Boolean = false

    /** 給 UI / 常駐通知看的當下狀態。 */
    fun snapshot(atMs: Long): SessionSnapshot = when (val p = phase) {
        is Phase.Idle -> SessionSnapshot(
            inSession = false,
            elapsedMs = 0L,
            packageName = null,
            interruptShowing = interruptShowing,
            graceRemainingMs = (graceUntilMs - atMs).coerceAtLeast(0L),
        )

        is Phase.Active -> SessionSnapshot(
            inSession = true,
            elapsedMs = p.accumulatedMs + (atMs - p.resumedAtMs).coerceAtLeast(0L),
            packageName = p.packageName,
            interruptShowing = interruptShowing,
            graceRemainingMs = (graceUntilMs - atMs).coerceAtLeast(0L),
        )

        is Phase.Paused -> SessionSnapshot(
            inSession = true,
            elapsedMs = p.accumulatedMs,
            packageName = p.packageName,
            interruptShowing = interruptShowing,
            graceRemainingMs = (graceUntilMs - atMs).coerceAtLeast(0L),
        )
    }

    fun process(event: SessionEvent): List<EngineEffect> = when (event) {
        is SessionEvent.ForegroundChanged -> onForegroundChanged(event)
        is SessionEvent.Tick -> onTick(event.atMs)
        is SessionEvent.UnconsciousScrollDetected -> onUnconsciousScroll(event.atMs)
        is SessionEvent.InterruptDismissed -> onDismissed(event)
        is SessionEvent.ConfigChanged -> {
            config = event.config
            emptyList()
        }
        is SessionEvent.Stopped -> {
            val effects = if (interruptShowing) listOf(EngineEffect.HideInterrupt) else emptyList()
            phase = Phase.Idle
            interruptShowing = false
            effects
        }
    }

    private fun onForegroundChanged(event: SessionEvent.ForegroundChanged): List<EngineEffect> {
        val at = event.atMs
        return if (event.isTarget) {
            when (val p = phase) {
                is Phase.Idle -> {
                    phase = Phase.Active(resumedAtMs = at, accumulatedMs = 0L, packageName = event.packageName)
                    listOf(EngineEffect.SessionUpdated(0L, event.packageName))
                }

                is Phase.Paused -> {
                    // 冷卻期內切回來 → 視為同一個 session，繼續累計。
                    // 這就是「切出去瞄一眼通知再切回來」不該把計時洗掉的那條規則。
                    val withinCooldown = (at - p.pausedAtMs) <= config.cooldownMs
                    val carried = if (withinCooldown) p.accumulatedMs else 0L
                    phase = Phase.Active(resumedAtMs = at, accumulatedMs = carried, packageName = event.packageName)
                    listOf(EngineEffect.SessionUpdated(carried, event.packageName))
                }

                is Phase.Active -> {
                    // 目標 app 之間互跳（IG → YouTube）：不歸零，繼續算同一段連續使用。
                    phase = p.copy(packageName = event.packageName)
                    emptyList()
                }
            }
        } else {
            when (val p = phase) {
                is Phase.Active -> {
                    if (interruptShowing) {
                        // 貓還蓋著，而使用者選擇直接離開目標 app。
                        // **這正是我們希望發生的事**，它應該得到跟按下按鈕一樣的待遇。
                        //
                        // 原本的寫法只收掉 overlay，把已經超過門檻的累計值原封不動
                        // 搬進 Paused，而且不給冷靜期。於是使用者在冷卻時間內切回來時，
                        // elapsed 一開始就 >= 門檻 → 下一個 tick 立刻再跳一次貓。
                        //
                        // 結果是「逃走」被懲罰、「按按鈕」才有赦免 —— 誘因方向剛好相反。
                        // 而且冷卻上限有 10 分鐘，這個窗口大到幾乎必中。
                        interruptShowing = false
                        graceUntilMs = at + config.graceMs
                        phase = Phase.Paused(accumulatedMs = 0L, pausedAtMs = at, packageName = p.packageName)
                        listOf(EngineEffect.HideInterrupt)
                    } else {
                        val elapsed = p.accumulatedMs + (at - p.resumedAtMs).coerceAtLeast(0L)
                        phase = Phase.Paused(accumulatedMs = elapsed, pausedAtMs = at, packageName = p.packageName)
                        emptyList()
                    }
                }

                else -> emptyList()
            }
        }
    }

    private fun onTick(at: Long): List<EngineEffect> = when (val p = phase) {
        is Phase.Idle -> emptyList()

        is Phase.Paused -> {
            if (at - p.pausedAtMs > config.cooldownMs) {
                phase = Phase.Idle
                listOf(EngineEffect.SessionUpdated(0L, null))
            } else {
                emptyList()
            }
        }

        is Phase.Active -> {
            val elapsed = p.accumulatedMs + (at - p.resumedAtMs).coerceAtLeast(0L)
            if (shouldInterrupt(at) && elapsed >= config.thresholdMs) {
                interruptShowing = true
                listOf(EngineEffect.Interrupt(elapsed, p.packageName, InterruptReason.DURATION))
            } else {
                listOf(EngineEffect.SessionUpdated(elapsed, p.packageName))
            }
        }
    }

    /** Mode B：偵測器已自行判定「無意識刷動且持續 T 秒」，門檻邏輯在偵測器內（spec §5）。 */
    private fun onUnconsciousScroll(at: Long): List<EngineEffect> {
        val p = phase as? Phase.Active ?: return emptyList()
        if (!shouldInterrupt(at)) return emptyList()
        val elapsed = p.accumulatedMs + (at - p.resumedAtMs).coerceAtLeast(0L)
        interruptShowing = true
        return listOf(EngineEffect.Interrupt(elapsed, p.packageName, InterruptReason.SCROLL_RHYTHM))
    }

    private fun shouldInterrupt(at: Long): Boolean = !interruptShowing && at >= graceUntilMs

    private fun onDismissed(event: SessionEvent.InterruptDismissed): List<EngineEffect> {
        val at = event.atMs
        interruptShowing = false
        graceUntilMs = at + config.graceMs

        // 解除後計時重新起算，否則 GRACE 一過就會立刻再次超過門檻、連環轟炸。
        phase = when (val p = phase) {
            is Phase.Active -> Phase.Active(resumedAtMs = at, accumulatedMs = 0L, packageName = p.packageName)
            is Phase.Paused -> Phase.Paused(accumulatedMs = 0L, pausedAtMs = p.pausedAtMs, packageName = p.packageName)
            is Phase.Idle -> Phase.Idle
        }

        return listOf(EngineEffect.HideInterrupt, EngineEffect.SessionUpdated(0L, null))
    }
}

data class EngineConfig(
    /** 連續使用多久就打斷。 */
    val thresholdMs: Long,
    /** 離開目標 app 多久內切回來仍算同一個 session。 */
    val cooldownMs: Long,
    /** 解除 overlay 後多久內不再打斷。 */
    val graceMs: Long,
)

sealed interface SessionEvent {
    /**
     * 前景 app 換了。[isTarget] 由偵測層依使用者設定判斷，
     * Session Engine 不需要知道「哪些 package 算目標」。
     */
    data class ForegroundChanged(val isTarget: Boolean, val packageName: String?, val atMs: Long) : SessionEvent

    /** 週期性心跳，用來推進計時與檢查門檻。 */
    data class Tick(val atMs: Long) : SessionEvent

    /** Mode B 偵測器回報：進入無意識刷動且已持續足夠久。 */
    data class UnconsciousScrollDetected(val atMs: Long) : SessionEvent

    /** 使用者把 overlay 解除掉了。 */
    data class InterruptDismissed(val atMs: Long, val choice: DismissChoice) : SessionEvent

    data class ConfigChanged(val config: EngineConfig) : SessionEvent

    /** 服務停止 / 使用者關掉開關。 */
    data object Stopped : SessionEvent
}

enum class DismissChoice {
    /** 「停下來」——使用者選擇回桌面。 */
    STOP,

    /** 「再滑 5 分鐘」——進入 GRACE。 */
    SNOOZE,
}

enum class InterruptReason {
    /** Mode A：在此 app 連續停留過久。 */
    DURATION,

    /** Mode B：滑動節奏判定為無意識刷動。 */
    SCROLL_RHYTHM,
}

sealed interface EngineEffect {
    data class Interrupt(
        val elapsedMs: Long,
        val packageName: String?,
        val reason: InterruptReason,
    ) : EngineEffect

    data object HideInterrupt : EngineEffect

    /** 純資訊：目前累計時間，給常駐通知顯示用。 */
    data class SessionUpdated(val elapsedMs: Long, val packageName: String?) : EngineEffect
}

data class SessionSnapshot(
    val inSession: Boolean,
    val elapsedMs: Long,
    val packageName: String?,
    val interruptShowing: Boolean,
    val graceRemainingMs: Long,
)
