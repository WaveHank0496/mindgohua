package com.catgatekeeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catgatekeeper.R
import com.catgatekeeper.detect.DetectorDebugBus
import com.catgatekeeper.settings.AppSettings
import com.catgatekeeper.settings.DetectionMode
import com.catgatekeeper.settings.TargetApps
import com.catgatekeeper.stats.DayTotal
import com.catgatekeeper.stats.SurvivalState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsFlow: Flow<AppSettings>,
    permissionsFlow: StateFlow<PermissionState>,
    dailyTotalsFlow: Flow<List<DayTotal>>,
    survivalFlow: Flow<SurvivalState>,
    actions: ScreenActions,
) {
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val permissions by permissionsFlow.collectAsState()
    val dailyTotals by dailyTotalsFlow.collectAsState(initial = emptyList())
    val survival by survivalFlow.collectAsState(
        initial = SurvivalState(0, 0, 0, 0, 0, false),
    )
    val missing = settings.missingRequirements(permissions)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cat Gatekeeper") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MasterSwitchCard(settings, missing, actions)
            PrivacyCard(settings)
            ModeCard(settings, actions)
            TargetAppsCard(settings, actions)
            ThresholdCard(settings, actions)
            if (settings.mode == DetectionMode.FOCUS) {
                DailyCountCard(settings, dailyTotals, actions)
                ModeBAcceptanceCard(settings, permissions, actions)
                RhythmTuningCard(settings, actions)
            }
            PermissionsCard(settings, permissions, actions)
            SurvivalStatusCard(survival, actions)
            SurvivalCard(permissions, actions)
            TestCard(settings, actions)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun MasterSwitchCard(settings: AppSettings, missing: List<String>, actions: ScreenActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_cat),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (settings.enabled) "貓在看著你" else "貓在睡覺",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    if (missing.isEmpty()) {
                        "門檻 ${settings.thresholdSeconds / 60} 分 ${settings.thresholdSeconds % 60} 秒"
                    } else {
                        "還缺：" + missing.joinToString("、")
                    },
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { actions.onToggleEnabled(it) },
                enabled = settings.enabled || missing.isEmpty(),
            )
        }
    }
}

@Composable
private fun PrivacyCard(settings: AppSettings) {
    SectionCard("這個 app 看得到什麼") {
        val seen = when (settings.mode) {
            DetectionMode.PRIVACY -> listOf(
                "✔ 哪個 app 現在在前景、你在裡面待了多久",
            )
            DetectionMode.FOCUS -> listOf(
                "✔ 哪個 app 現在在前景、你在裡面待了多久",
                "✔ 你「什麼時候」滑了一下（只有時間點）",
            )
        }
        val notSeen = listOf(
            "✘ 畫面上的任何文字、圖片、影片",
            "✘ 你看了誰的貼文、按了什麼",
            "✘ 帳號、聯絡人、通知內容",
            "✘ 非目標 app 的任何事（一律略過不讀）",
        )
        seen.forEach { Text(it, fontSize = 14.sp) }
        notSeen.forEach { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))

        // 這段文字必須跟著設定變 —— 留一個過期的承諾在畫面上比沒有承諾更糟。
        Text(
            if (settings.statsEnabled) {
                "判斷過程都在這台手機上完成、當場丟棄。唯一寫入儲存空間的是每日計數：" +
                    "日期 + app 名稱 + 一個數字，沒有時間點也沒有內容。" +
                    "本 app 沒有宣告網路權限 —— 就算程式想傳，系統也不會讓它連線。"
            } else {
                "所有判斷都在這台手機上完成、當場丟棄，不寫入任何檔案。" +
                    "本 app 沒有宣告網路權限 —— 就算程式想傳，系統也不會讓它連線。"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeCard(settings: AppSettings, actions: ScreenActions) {
    SectionCard("偵測方式") {
        ModeOption(
            selected = settings.mode == DetectionMode.PRIVACY,
            title = "Privacy Mode（預設）",
            desc = "只看你在這個 app 連續待了多久。看不到畫面內容，權限最少。" +
                "缺點是分不出你在滑 Reels 還是在回訊息。",
            onSelect = { actions.onSettingsChange { it.setMode(DetectionMode.PRIVACY) } },
        )
        ModeOption(
            selected = settings.mode == DetectionMode.FOCUS,
            title = "Focus Mode（實驗中）",
            desc = "另外讀「你在什麼時間點滑了一下」，用節奏判斷是不是在無意識刷。" +
                "需要開無障礙服務。演算法參數還沒用真實資料校準過，可能誤判。",
            onSelect = { actions.onSettingsChange { it.setMode(DetectionMode.FOCUS) } },
        )
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, desc: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = selected, onValueChange = { onSelect() })
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TargetAppsCard(settings: AppSettings, actions: ScreenActions) {
    SectionCard("要看住哪些 app") {
        TargetApps.selectable.forEach { (pkg, label) ->
            val checked = pkg in settings.targetPackages
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        onValueChange = { value ->
                            actions.onSettingsChange { it.toggleTarget(pkg, value) }
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
                Text(label, fontSize = 15.sp)
            }
        }
        Text(
            "沒有勾選的 app，本程式完全不處理它的事件。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThresholdCard(settings: AppSettings, actions: ScreenActions) {
    SectionCard("什麼時候打斷") {
        LabeledSlider(
            label = "連續使用超過",
            valueText = "${settings.thresholdSeconds / 60} 分 ${settings.thresholdSeconds % 60} 秒",
            value = settings.thresholdSeconds.toFloat(),
            range = 30f..3600f,
            steps = 118, // 每 30 秒一格
            onChange = { v ->
                val snapped = (v / 30f).roundToInt() * 30
                actions.onSettingsChange { it.setThresholdSeconds(snapped) }
            },
        )
        LabeledSlider(
            label = "切出去多久內回來算同一段",
            valueText = "${settings.cooldownSeconds} 秒",
            value = settings.cooldownSeconds.toFloat(),
            range = 0f..60f,
            steps = 11,
            onChange = { v ->
                val snapped = (v / 5f).roundToInt() * 5
                actions.onSettingsChange { it.setCooldownSeconds(snapped) }
            },
        )
        Text(
            "切出去瞄一眼通知再切回來，不該把計時洗掉。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSlider(
            label = "解除後多久內不再打斷",
            valueText = "${settings.graceMinutes} 分鐘",
            value = settings.graceMinutes.toFloat(),
            range = 1f..30f,
            steps = 28,
            onChange = { v -> actions.onSettingsChange { it.setGraceMinutes(v.roundToInt()) } },
        )
        LabeledSlider(
            label = "貓出現後幾秒才能按按鈕",
            valueText = "${settings.unlockDelaySeconds} 秒",
            value = settings.unlockDelaySeconds.toFloat(),
            range = 0f..10f,
            steps = 9,
            onChange = { v -> actions.onSettingsChange { it.setUnlockDelaySeconds(v.roundToInt()) } },
        )
        Text(
            "這個延遲是刻意的：秒點就消失的按鈕，兩週後會退化成反射動作。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RhythmTuningCard(settings: AppSettings, actions: ScreenActions) {
    SectionCard("Focus Mode 調參") {
        Text(
            "這些門檻需要拿你自己的真實使用資料反覆調。預設值只是起點。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSlider(
            label = "統計窗口",
            valueText = "${settings.rhythmWindowSeconds} 秒",
            value = settings.rhythmWindowSeconds.toFloat(),
            range = 30f..300f,
            steps = 26,
            onChange = { v ->
                val snapped = (v / 10f).roundToInt() * 10
                actions.onSettingsChange { it.setRhythmWindowSeconds(snapped) }
            },
        )
        LabeledSlider(
            label = "密度門檻（每分鐘滑幾次）",
            valueText = "${settings.rhythmMinDensityPerMinute} 次",
            value = settings.rhythmMinDensityPerMinute.toFloat(),
            range = 5f..60f,
            steps = 54,
            onChange = { v -> actions.onSettingsChange { it.setRhythmDensity(v.roundToInt()) } },
        )
        LabeledSlider(
            label = "變異門檻 CV（越低越規律）",
            valueText = "${settings.rhythmMaxCvPercent / 100.0}",
            value = settings.rhythmMaxCvPercent.toFloat(),
            range = 10f..150f,
            steps = 27,
            onChange = { v ->
                val snapped = (v / 5f).roundToInt() * 5
                actions.onSettingsChange { it.setRhythmCvPercent(snapped) }
            },
        )
        LabeledSlider(
            label = "需持續多久才觸發",
            valueText = "${settings.rhythmSustainSeconds} 秒",
            value = settings.rhythmSustainSeconds.toFloat(),
            range = 10f..300f,
            steps = 28,
            onChange = { v ->
                val snapped = (v / 10f).roundToInt() * 10
                actions.onSettingsChange { it.setRhythmSustainSeconds(snapped) }
            },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp)
            Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun PermissionsCard(
    settings: AppSettings,
    state: PermissionState,
    actions: ScreenActions,
) {
    SectionCard("權限") {
        PermissionRow(
            granted = state.overlay,
            title = "懸浮視窗",
            why = "把貓疊在 IG 上面。沒有它就無法打斷。",
            onFix = actions.onOpenOverlay,
        )
        PermissionRow(
            granted = state.usageAccess,
            title = "使用情況存取權",
            why = "知道哪個 app 在前景、待了多久。看不到畫面內容。",
            onFix = actions.onOpenUsageAccess,
        )
        PermissionRow(
            granted = state.notifications,
            title = "通知",
            why = "Android 規定常駐服務必須顯示一則通知。",
            onFix = actions.onRequestNotifications,
        )
        if (settings.mode == DetectionMode.FOCUS) {
            PermissionRow(
                granted = state.accessibility,
                title = "無障礙服務（Focus Mode 才需要）",
                why = "只讀捲動事件的時間點。程式已在設定檔關閉「讀取視窗內容」的能力。",
                onFix = actions.onOpenAccessibility,
            )
        }
    }
}

@Composable
private fun PermissionRow(granted: Boolean, title: String, why: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(granted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(why, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            TextButton(onClick = onFix) { Text("去開") }
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Text(
        text = if (ok) "✔" else "！",
        color = if (ok) Color(0xFF2E7D32) else Color(0xFFB3261E),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier
            .size(24.dp)
            .background(
                color = if (ok) Color(0x1A2E7D32) else Color(0x1AB3261E),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(top = 2.dp),
    )
}

/**
 * 存活紀錄（spec §8 第 6 條的驗收面板）。
 *
 * 這張卡存在的理由：ColorOS 殺掉服務時是**靜默的**，而要測出這件事，
 * 手機必須拔掉電源進入深度休眠 —— 那時你根本不能用 adb 盯著看。
 * 所以讓 app 自己記錄，測完打開來看就好。
 */
@Composable
private fun SurvivalStatusCard(survival: SurvivalState, actions: ScreenActions) {
    // 每秒重算一次顯示用的「現在」，讓存活時間會自己往上跑。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    SectionCard("存活紀錄") {
        if (!survival.isTracking) {
            Text(
                "服務目前沒在跑。打開最上面的開關後開始記錄。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "已連續存活 ${formatDuration(survival.survivedMs(nowMs))}",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "驗收目標：螢幕關閉、拔掉電源的情況下 ≥ 4 小時。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (survival.gapCount == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(true)
                Spacer(Modifier.width(10.dp))
                Text("尚未偵測到任何中斷", fontSize = 14.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(false)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("曾中斷 ${survival.gapCount} 次", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "最近一次中斷了 ${formatDuration(survival.lastGapDurationMs)}" +
                            if (survival.lastGapWasReboot) "（重開機造成，不算被殺）" else "（服務被系統終止）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "測試時請拔掉 USB 線。插著電手機不會進入深度休眠，" +
                "測出來的存活時間會過度樂觀。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = actions.onResetSurvival) { Text("重新開始記錄") }
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "$days 天 $hours 小時"
        hours > 0 -> "$hours 小時 $minutes 分"
        else -> "$minutes 分"
    }
}

@Composable
private fun SurvivalCard(state: PermissionState, actions: ScreenActions) {
    SectionCard("讓貓活著（ColorOS 必做）") {
        Text(
            "OPPO / ColorOS 對背景程式管制很兇，不做這兩步的話，服務跑一陣子就會被系統殺掉，" +
                "而且不會有任何提示 —— 你只會發現貓再也沒出現過。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionRow(
            granted = state.batteryUnrestricted,
            title = "電池不受限制",
            why = "設定 → 電池 → 找到本 app → 選「不受限制 / 允許背景執行」。",
            onFix = actions.onOpenBattery,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自啟動管理", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "手機管家 / 安全中心 → 自啟動管理 → 允許 Cat Gatekeeper。" +
                        "這個開關沒有 API 可查，請自行確認已開啟。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = actions.onOpenAutoStart) { Text("開啟") }
        }
        Text(
            "另外建議在最近任務畫面把本 app 「上鎖」，避免被一鍵清理掃掉。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TestCard(settings: AppSettings, actions: ScreenActions) {
    SectionCard("驗收工具") {
        Text(
            "不想等滿門檻也可以直接叫貓出來，確認 overlay 和兩顆按鈕正常。" +
                "這是預覽，不會影響正在跑的計時。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = actions.onTestInterrupt) { Text("預覽「待太久」的貓（Mode A）") }
        if (settings.mode == DetectionMode.FOCUS) {
            OutlinedButton(onClick = actions.onTestFocusInterrupt) { Text("預覽「無意識刷動」的貓（Mode B）") }
        }

        Spacer(Modifier.height(8.dp))
        DiagnosticsToggle(settings, actions)
    }
}

/**
 * 驗收期間把即時數字顯示在常駐通知上。
 *
 * 平常的通知只顯示到分鐘（秒數會讓它每 2 秒重畫一次，白白耗電），
 * 但驗收時你需要看到秒 —— 尤其是「切出去再切回來，計時有沒有被洗掉」這種
 * 只看最終有沒有跳貓根本分辨不出來的情況。
 */
@Composable
private fun DiagnosticsToggle(settings: AppSettings, actions: ScreenActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("在通知上顯示即時數字", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                when (settings.mode) {
                    DetectionMode.PRIVACY -> "通知會變成「Instagram 23 秒 / 門檻 30 秒」，" +
                        "下拉通知欄就能邊滑邊看計時。驗收完記得關掉。"
                    DetectionMode.FOCUS -> "通知會顯示密度、CV、持續時間。調 Mode B 參數必開。" +
                        "調完記得關掉。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.diagnosticsNotification,
            onCheckedChange = { value ->
                actions.onSettingsChange { it.setDiagnosticsNotification(value) }
            },
        )
    }
}

/**
 * 每日滑動計數。
 *
 * 這張卡對應的功能**刻意推翻了 spec §0.1 的「不落地」**，所以卡片上必須把
 * 「到底存了什麼」講清楚，而不是只顯示漂亮的數字。使用者有權知道自己換掉了什麼。
 */
@Composable
private fun DailyCountCard(
    settings: AppSettings,
    dailyTotals: List<DayTotal>,
    actions: ScreenActions,
) {
    val debugState by DetectorDebugBus.state.collectAsState()
    val d = debugState

    SectionCard("每日滑動計數") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("記錄每天滑過幾項", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "會寫進手機儲存空間的只有三樣：日期、app 名稱、一個數字。" +
                        "沒有時間點、沒有你滑了什麼。仍然沒有網路權限，出不了這台手機。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.statsEnabled,
                onCheckedChange = { v -> actions.onSettingsChange { it.setStatsEnabled(v) } },
            )
        }

        if (!settings.statsEnabled) {
            Text(
                "關閉時完全不寫入任何紀錄。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(Modifier.height(4.dp))

        // 「量不到」和「沒滑」一定要分得開，否則畫面上的 0 是騙人的。
        if (d != null && d.totalScrollEvents > 0 && !d.indexDataAvailable) {
            Text(
                "⚠ 這個 app 的滑動事件沒有附上清單位置資訊，數不出項數。" +
                    "已收到 ${d.totalScrollEvents} 個滑動事件，但其中沒有可用的 index。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val today = dailyTotals.firstOrNull()
        Text(
            "今天：${today?.total ?: 0} 項",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        today?.perApp?.forEach { (pkg, count) ->
            Text(
                "　${TargetApps.labelOf(pkg)}　$count",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (d != null && d.itemsAdvanced > 0) {
            Text(
                "（本次啟動累計 ${d.itemsAdvanced} 項，最近位置 ${d.lastToIndex}/${d.lastItemCount}）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("在角落顯示即時數字", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "滑目標 app 時右上角浮出今日計數，觸控會直接穿透不影響操作。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.hudEnabled,
                onCheckedChange = { v -> actions.onSettingsChange { it.setHudEnabled(v) } },
            )
        }

        LabeledSlider(
            label = "每天幾點歸零",
            valueText = "${settings.dayBoundaryHour}:00",
            value = settings.dayBoundaryHour.toFloat(),
            range = 0f..23f,
            steps = 22,
            onChange = { v -> actions.onSettingsChange { it.setDayBoundaryHour(v.roundToInt()) } },
        )
        Text(
            "午夜零點換日會把熬夜那段切成兩天，兩邊的數字都會失真。預設 4:00。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LabeledSlider(
            label = "保留幾天的紀錄",
            valueText = "${settings.statsRetentionDays} 天",
            value = settings.statsRetentionDays.toFloat(),
            range = 7f..365f,
            steps = 50,
            onChange = { v ->
                val snapped = (v / 7f).roundToInt() * 7
                actions.onSettingsChange { it.setStatsRetentionDays(snapped) }
            },
        )

        if (dailyTotals.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text("歷史", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            val max = dailyTotals.maxOf { it.total }.coerceAtLeast(1)
            dailyTotals.take(14).forEach { day ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${day.date}", fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(14.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(7.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(day.total.toFloat() / max)
                                .height(14.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(7.dp),
                                ),
                        )
                    }
                    Text(
                        "${day.total}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(56.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = actions.onClearStats) { Text("清除所有紀錄") }
    }
}

/**
 * Mode B 的驗收面板。
 *
 * Mode B 比 Mode A 難驗收，因為它有三段可能斷掉：
 * 無障礙服務有沒有連上 → 事件有沒有進來 → 統計量有沒有跨過門檻。
 * 這張卡把三段分開顯示，壞了才知道是壞在哪一段。
 */
@Composable
private fun ModeBAcceptanceCard(
    settings: AppSettings,
    permissions: PermissionState,
    actions: ScreenActions,
) {
    val debugState by DetectorDebugBus.state.collectAsState()
    val d = debugState

    SectionCard("Mode B 驗收") {
        // ── 第一段：服務有沒有連上 ──
        CheckLine(
            ok = permissions.accessibility,
            label = "無障礙服務已在系統設定中開啟",
        )
        CheckLine(
            ok = d?.accessibilityConnected == true,
            label = "無障礙服務已連線到本程式",
            note = if (d == null) "偵測服務沒在跑，先打開最上面的開關" else null,
        )

        // ── 第二段：事件有沒有進來 ──
        val events = d?.totalScrollEvents ?: 0
        CheckLine(
            ok = events > 0,
            label = "已從目標 app 收到 $events 個滑動事件",
            note = if (events == 0) "去 IG 隨便滑幾下再回來看這個數字" else null,
        )

        Spacer(Modifier.height(4.dp))

        // ── 第三段：統計量 ──
        if (d == null || d.mode != DetectionMode.FOCUS) {
            Text(
                "打開開關後，這裡會顯示即時的滑動節奏統計。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (d.frozen) "以下是你離開目標 app 前的最後一筆數字" else "即時數字（目標 app 前景中）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricRow(
                label = "密度",
                value = "${String.format("%.1f", d.densityPerMinute)} 次/分",
                threshold = "門檻 ≥ ${settings.rhythmMinDensityPerMinute}",
                pass = d.densityPerMinute >= settings.rhythmMinDensityPerMinute,
            )
            MetricRow(
                label = "變異係數 CV",
                value = if (d.coefficientOfVariation.isNaN()) "樣本不足" else String.format("%.2f", d.coefficientOfVariation),
                threshold = "門檻 ≤ ${settings.rhythmMaxCvPercent / 100.0}",
                pass = !d.coefficientOfVariation.isNaN() &&
                    d.coefficientOfVariation <= settings.rhythmMaxCvPercent / 100.0,
            )
            MetricRow(
                label = "持續時間",
                value = "${d.sustainedSeconds} 秒",
                threshold = "門檻 ≥ ${settings.rhythmSustainSeconds}",
                pass = d.sustainedSeconds >= settings.rhythmSustainSeconds,
            )
            MetricRow(
                label = "窗口內樣本",
                value = "${d.sampleCount} 筆",
                threshold = "至少 11 筆才判斷",
                pass = d.sampleCount >= 11,
            )
            Text(
                "本次啟動已觸發 ${d.triggerCount} 次",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            "調參時把最下面「驗收工具」的「在通知上顯示即時數字」打開，" +
                "就能一邊滑 IG 一邊從通知欄看這些數字。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "這些數字只是統計量（幾次、多規律），不含你滑了什麼。" +
                "只存在記憶體，關掉開關就消失。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckLine(ok: Boolean, label: String, note: String? = null) {
    Row(Modifier.fillMaxWidth()) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 14.sp)
            if (note != null) {
                Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 一行「目前值 vs 門檻」，讓人一眼看出是哪個條件卡住了。 */
@Composable
private fun MetricRow(label: String, value: String, threshold: String, pass: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (pass) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
            )
            Text(threshold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
