package com.mindgohua.util

import android.content.ComponentName
import android.content.Intent

/**
 * 各家 OEM 的「自啟動管理」對策。
 *
 * ## 為什麼需要這個檔案
 *
 * 這個 app 的核心是一個必須長期活著的 foreground service。Android 官方
 * 對 foreground service 有保護，但**各家廠商都自己加了一層更兇的背景管制**，
 * 而且那層管制沒有公開 API、查不到狀態、也沒有標準的設定頁入口。
 *
 * 原本的實作只處理 OPPO / ColorOS（因為那是開發用的手機）。對其他廠牌的
 * 使用者來說，這個 app 會**安靜地失效**：開關看起來是開的，貓再也不出現。
 * 這是最糟的失敗模式，因為使用者不會來回報 bug，他只會解除安裝。
 *
 * ## 這裡的做法
 *
 * 用 [Build.MANUFACTURER] 判斷廠牌，給出該廠牌的設定頁 intent 與**文字步驟**。
 * intent 一定會失敗（改版、機型差異、地區版本），所以文字步驟不是備援，
 * 而是主要手段 —— intent 只是替使用者省下找路的力氣。
 *
 * ## 為什麼這份表是純資料、不碰 Context
 *
 * [profileFor] 只吃一個廠牌字串、回傳一個 [OemProfile]，沒有任何 Android 相依。
 * 這讓「Redmi 要對應到小米」「大小寫不該影響判斷」這類容易寫錯的規則
 * 可以被單元測試釘住（見 `OemSurvivalTest`），不需要七支不同廠牌的手機。
 *
 * 真正要碰系統的部分留在 [Permissions]，這裡只負責「該去哪、該怎麼說」。
 */
object OemSurvival {

    /**
     * 一個廠牌的背景管制對策。
     *
     * @param id 內部識別用，也用於測試斷言。
     * @param displayName 顯示給使用者看的廠牌名。
     * @param autoStartSteps 文字步驟。**這是主要手段**，intent 失敗時使用者
     *   仍然能照著做。順序即為畫面上的顯示順序。
     * @param components 可能的設定頁 component。逐一嘗試，開得起來就停。
     * @param needsAutoStart 這個廠牌是否真的有「自啟動管理」這道關卡。
     *   近原生系統（Pixel、Sony、Motorola）沒有，對它們顯示這段只會造成困惑。
     */
    data class OemProfile(
        val id: String,
        val displayName: String,
        val autoStartSteps: List<String>,
        val components: List<Pair<String, String>>,
        val needsAutoStart: Boolean = true,
    )

    /**
     * 依廠牌字串取得對策。
     *
     * 比對一律轉小寫：`Build.MANUFACTURER` 的大小寫沒有標準，
     * 同一家廠商在不同機型上可能回傳 `Xiaomi`、`xiaomi` 或 `XIAOMI`。
     *
     * 認不出來的廠牌回傳 [GENERIC] —— 保守地假設它有背景管制，
     * 因為漏掉一個有管制的廠牌，代價是 app 靜默失效；
     * 多提醒一個沒管制的廠牌，代價只是多看一段文字。
     */
    fun profileFor(manufacturer: String?): OemProfile {
        val m = manufacturer?.trim()?.lowercase().orEmpty()
        return when {
            m.isEmpty() -> GENERIC
            m.contains("oppo") || m.contains("oplus") || m.contains("realme") -> OPPO
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
            m.contains("vivo") || m.contains("iqoo") -> VIVO
            m.contains("huawei") || m.contains("honor") -> HUAWEI
            m.contains("samsung") -> SAMSUNG
            m.contains("oneplus") -> ONEPLUS
            m.contains("asus") -> ASUS
            m.contains("google") || m.contains("sony") || m.contains("motorola") -> NEAR_STOCK
            else -> GENERIC
        }
    }

    val OPPO = OemProfile(
        id = "oppo",
        displayName = "OPPO / realme（ColorOS）",
        autoStartSteps = listOf(
            "手機管家 → 權限隱私 → 自啟動管理 → 允許 mindgohua",
            "設定 → 電池 → mindgohua → 選「不受限制 / 允許背景執行」",
            "最近任務畫面下拉本 app 卡片 → 上鎖，避免一鍵清理掃掉",
        ),
        components = listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        ),
    )

    val XIAOMI = OemProfile(
        id = "xiaomi",
        displayName = "小米 / Redmi / POCO（MIUI、HyperOS）",
        autoStartSteps = listOf(
            "設定 → 應用設定 → 應用管理 → mindgohua → 自啟動，打開",
            "同一頁 → 省電策略 → 選「無限制」",
            "最近任務畫面下拉本 app 卡片 → 上鎖",
        ),
        components = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
        ),
    )

    val VIVO = OemProfile(
        id = "vivo",
        displayName = "vivo / iQOO（Funtouch、OriginOS）",
        autoStartSteps = listOf(
            "i 管家 → 應用管理 → 權限管理 → 自啟動 → 允許 mindgohua",
            "設定 → 電池 → 後台高耗電 → 允許 mindgohua",
        ),
        components = listOf(
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
    )

    val HUAWEI = OemProfile(
        id = "huawei",
        displayName = "華為 / 榮耀（EMUI、MagicOS）",
        autoStartSteps = listOf(
            "手機管家 → 應用啟動管理 → 關閉 mindgohua 的「自動管理」",
            "改為手動管理，三個開關（自啟動、關聯啟動、後台活動）全部打開",
        ),
        components = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    )

    val SAMSUNG = OemProfile(
        id = "samsung",
        displayName = "Samsung（One UI）",
        autoStartSteps = listOf(
            "設定 → 電池 → 背景使用限制 → 確認 mindgohua 不在「休眠應用程式」名單中",
            "設定 → 應用程式 → mindgohua → 電池 → 選「不受限制」",
        ),
        components = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
    )

    val ONEPLUS = OemProfile(
        id = "oneplus",
        displayName = "OnePlus（OxygenOS）",
        autoStartSteps = listOf(
            "設定 → 電池 → 電池最佳化 → mindgohua → 選「不要最佳化」",
            "最近任務畫面下拉本 app 卡片 → 上鎖",
        ),
        components = listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
    )

    val ASUS = OemProfile(
        id = "asus",
        displayName = "ASUS（ZenUI）",
        autoStartSteps = listOf(
            "行動管理員 → 省電管理 → 自動啟動管理 → 允許 mindgohua",
        ),
        components = listOf(
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        ),
    )

    /**
     * 近原生系統：Pixel、Sony、Motorola。
     *
     * 這些沒有額外的自啟動管理層，只要處理 Android 官方的電池最佳化即可。
     * 對它們顯示「去手機管家找自啟動管理」只會讓使用者找不到而困惑，
     * 所以 [needsAutoStart] 是 false。
     */
    val NEAR_STOCK = OemProfile(
        id = "near_stock",
        displayName = "接近原生的 Android",
        autoStartSteps = listOf(
            "設定 → 應用程式 → mindgohua → 電池 → 選「不受限制」",
        ),
        components = emptyList(),
        needsAutoStart = false,
    )

    /**
     * 認不出廠牌時的保守預設。
     *
     * 仍然假設有自啟動管理，理由見 [profileFor] 的註解：
     * 漏報的代價（app 靜默失效）遠大於誤報的代價（多讀一段文字）。
     */
    val GENERIC = OemProfile(
        id = "generic",
        displayName = "你的手機",
        autoStartSteps = listOf(
            "設定 → 電池 → mindgohua → 允許背景執行 / 不受限制",
            "若有內建的「手機管家」或「安全中心」，找「自啟動管理」並允許 mindgohua",
        ),
        components = emptyList(),
    )

    /**
     * 把 profile 裡的 component 轉成可以拿去 startActivity 的 intent。
     *
     * **不要在一般單元測試裡呼叫這個函式。** 它會建構 `android.content.Intent`，
     * 而純 JVM 測試環境裡的 Android 類別只是沒有實作的空殼，
     * 呼叫下去會丟「Method setComponent in android.content.Intent not mocked」。
     *
     * 要驗證內容就直接斷言 [OemProfile.components] 這份純資料 ——
     * 真正會寫錯的是資料（打錯類別名、漏掉廠牌），不是這層包裝。
     */
    fun intentsFor(profile: OemProfile): List<Intent> =
        profile.components.map { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls))
        }
}
