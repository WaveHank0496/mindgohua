package com.mindgohua.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindgohua.R
import com.mindgohua.detect.DetectorDebugBus
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import com.mindgohua.settings.InstalledApps
import com.mindgohua.settings.SettingLimits
import com.mindgohua.stats.DayTotal
import com.mindgohua.stats.SurvivalState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsFlow: Flow<AppSettings>,
    permissionsFlow: StateFlow<PermissionState>,
    dailyTotalsFlow: Flow<List<DayTotal>>,
    survivalFlow: Flow<SurvivalState>,
    diagnosticsFlow: StateFlow<DiagnosticsState>,
    installedAppsFlow: StateFlow<InstalledAppsState>,
    actions: ScreenActions,
) {
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val permissions by permissionsFlow.collectAsState()
    val dailyTotals by dailyTotalsFlow.collectAsState(initial = emptyList())
    val survival by survivalFlow.collectAsState(
        initial = SurvivalState(0, 0, 0, 0, 0, false),
    )
    val diagnostics by diagnosticsFlow.collectAsState()
    val installedApps by installedAppsFlow.collectAsState()
    val missing = settings.missingRequirements(permissions)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("mindgohua") },
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
            // 使用者真正需要的七張卡片。
            MasterSwitchCard(settings, missing, actions)
            PrivacyCard(settings)
            ModeCard(settings, actions)
            TargetAppsCard(settings, installedApps, actions)
            ThresholdCard(settings, actions)
            PermissionsCard(settings, permissions, actions)
            SurvivalCard(permissions, actions)

            // 以下是為了「驗收這個 app 自己」而存在的面板，預設收起來。
            // 它們對開發很重要，但第一次打開這個 app 的人不該看到
            // 滿螢幕的調參滑桿、存活紀錄與當機記錄。
            AdvancedToggleCard(settings, actions)
            if (settings.advancedVisible) {
                SurvivalStatusCard(survival, settings, actions)
                if (settings.mode == DetectionMode.FOCUS) {
                    DailyCountCard(settings, dailyTotals, installedApps, actions)
                    ModeBAcceptanceCard(settings, permissions, actions)
                    RhythmTuningCard(settings, actions)
                }
                TestCard(settings, actions)
                DiagnosticsCard(diagnostics, actions)
            }
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
                        "門檻 ${DurationInput.format(settings.thresholdSeconds)}"
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

/**
 * 挑選要看住哪些 app。
 *
 * 清單來自手機上實際安裝、且有桌面圖示的 app（見 [InstalledApps]），
 * 不再是寫死的那 7 個 —— 使用者想看住什麼就看住什麼。
 *
 * 預設只顯示已選的項目，按「新增 app」才展開完整清單。
 * 理由：多數人裝了上百個 app，一進設定頁就攤開一整面清單，
 * 會把底下的門檻、權限等設定全部擠到看不見的地方。
 */
@Composable
private fun TargetAppsCard(
    settings: AppSettings,
    installed: InstalledAppsState,
    actions: ScreenActions,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    SectionCard("要看住哪些 app") {
        val selected = settings.targetPackages

        if (selected.isEmpty()) {
            Text(
                "還沒有選任何 app。沒有選的話，這個程式不會做任何事。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // 已選的項目即使解除安裝了也要顯示，否則使用者會看到一個
            // 「明明選了 3 個卻只列出 2 個」的清單，卻不知道少了什麼。
            selected.sorted().forEach { pkg ->
                val label = installed.labelOf(pkg)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = true,
                            onValueChange = { actions.onSettingsChange { s -> s.toggleTarget(pkg, false) } },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = true, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(label, fontSize = 15.sp)
                        if (!installed.isInstalled(pkg)) {
                            Text(
                                "已不在這台手機上",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起清單" else "新增 app")
        }

        if (expanded) {
            when {
                installed.loading -> Text(
                    "正在讀取已安裝的 app…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                installed.entries.isEmpty() -> Text(
                    "讀不到已安裝的 app 清單。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜尋 app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    val visible = InstalledApps.sortForPicker(
                        InstalledApps.filterByQuery(installed.entries, query),
                        selected,
                    )

                    if (visible.isEmpty()) {
                        Text(
                            "找不到符合「$query」的 app。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "共 ${visible.size} 個",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 這裡刻意不用 LazyColumn：整張設定頁已經在一個
                        // verticalScroll 裡，巢狀的可捲動容器會讓 Compose
                        // 無法決定高度而直接崩潰。
                        visible.forEach { entry ->
                            val checked = entry.packageName in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        onValueChange = { value ->
                                            actions.onSettingsChange {
                                                it.toggleTarget(entry.packageName, value)
                                            }
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(10.dp))
                                Text(entry.label, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "沒有勾選的 app，本程式完全不處理它的事件。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「進階與診斷」的開關。
 *
 * 底下那幾張卡片是為了驗收這個 app 自己而存在的：調參滑桿、存活紀錄、
 * 手動觸發測試、當機紀錄。它們對開發很重要 ——
 * 但一個第一次打開這個 app 的陌生人，看到的應該是
 * 「我要看住哪些 app、滑多久打斷我」，不是一個儀表板。
 *
 * 順帶一提：把這區收起來之後，**使用者看得到的畫面上就一根滑桿也沒有了**。
 * 剩下的滑桿全在這扇門後面。
 */
@Composable
private fun AdvancedToggleCard(settings: AppSettings, actions: ScreenActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    actions.onSettingsChange { it.setAdvancedVisible(!settings.advancedVisible) }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("進階與診斷", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    if (settings.advancedVisible) {
                        "調參、存活紀錄、測試工具、當機紀錄"
                    } else {
                        "開發與除錯用的面板。平常不需要打開。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.advancedVisible,
                onCheckedChange = { v -> actions.onSettingsChange { it.setAdvancedVisible(v) } },
            )
        }
    }
}

@Composable
private fun ThresholdCard(settings: AppSettings, actions: ScreenActions) {
    // 哪一個項目正在編輯。null = 沒有對話框開著。
    var editing by remember { mutableStateOf<String?>(null) }

    SectionCard("什麼時候打斷") {
        DurationRow(
            label = "連續使用超過",
            valueText = DurationInput.format(settings.thresholdSeconds),
            onClick = { editing = "threshold" },
        )
        Text(
            "研究把介入點放在「連續滑 15 分鐘」：大多數人要到 10～20 分鐘後" +
                "才開始對自己的使用產生負面感受。太早打斷，換來的只是反彈。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DurationRow(
            label = "切出去多久內回來算同一段",
            valueText = DurationInput.format(settings.cooldownSeconds),
            onClick = { editing = "cooldown" },
        )
        Text(
            "切出去瞄一眼通知再切回來，不該把計時洗掉。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DurationRow(
            label = "解除後多久內不再打斷",
            valueText = DurationInput.format(settings.graceMinutes * 60),
            onClick = { editing = "grace" },
        )
        Text(
            "被打斷之後，平均還會再滑 3 分半才真的停下來。在這段尾巴裡再跳一次貓" +
                "只會惹人厭，所以這個值建議不要低於 5 分鐘。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DurationRow(
            label = "貓出現後幾秒才能按按鈕",
            valueText = DurationInput.format(settings.unlockDelaySeconds),
            onClick = { editing = "unlock" },
        )
        Text(
            "這個延遲是刻意的：秒點就消失的按鈕，兩週後會退化成反射動作。" +
                "研究顯示幾秒鐘就夠了 —— 真正有效的是「可以不進去」這個選項本身，" +
                "不是讓你等更久。不過想給自己上重鎖的話，最多可以設到 3 分鐘。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 範圍全部來自 SettingLimits —— SettingsStore 存進去時夾的是同一份。
    // 兩邊各寫一份數字的話，使用者輸入的值會悄悄變成別的，而且畫面上不會有錯誤。
    when (editing) {
        "threshold" -> DurationInputDialog(
            title = "連續使用超過多久打斷",
            initialSeconds = settings.thresholdSeconds,
            minSeconds = SettingLimits.THRESHOLD_SECONDS.first,
            maxSeconds = SettingLimits.THRESHOLD_SECONDS.last,
            presetSeconds = listOf(300, 600, 900, 1200, 1800),
            onDismiss = { editing = null },
            onConfirm = { v -> actions.onSettingsChange { it.setThresholdSeconds(v) } },
        )

        // 上限放寬到 10 分鐘之後就不能只留秒欄了 ——
        // 只有秒欄的話「10 分」這個值根本打不進去。
        "cooldown" -> DurationInputDialog(
            title = "切出去多久內回來算同一段",
            initialSeconds = settings.cooldownSeconds,
            minSeconds = SettingLimits.COOLDOWN_SECONDS.first,
            maxSeconds = SettingLimits.COOLDOWN_SECONDS.last,
            presetSeconds = listOf(0, 10, 30, 60, 300, 600),
            onDismiss = { editing = null },
            onConfirm = { v -> actions.onSettingsChange { it.setCooldownSeconds(v) } },
        )

        "grace" -> DurationInputDialog(
            title = "解除後多久內不再打斷",
            initialSeconds = settings.graceMinutes * 60,
            minSeconds = SettingLimits.GRACE_MINUTES.first * 60,
            maxSeconds = SettingLimits.GRACE_MINUTES.last * 60,
            showSecondsField = false,
            presetSeconds = listOf(300, 600, 900, 1800),
            onDismiss = { editing = null },
            onConfirm = { v -> actions.onSettingsChange { it.setGraceMinutes(v / 60) } },
        )

        "unlock" -> DurationInputDialog(
            title = "貓出現後多久才能按按鈕",
            initialSeconds = settings.unlockDelaySeconds,
            minSeconds = SettingLimits.UNLOCK_DELAY_SECONDS.first,
            maxSeconds = SettingLimits.UNLOCK_DELAY_SECONDS.last,
            presetSeconds = listOf(0, 3, 10, 30, 60, 180),
            onDismiss = { editing = null },
            onConfirm = { v -> actions.onSettingsChange { it.setUnlockDelaySeconds(v) } },
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

/**
 * 一個「點了就跳輸入框」的設定列。
 *
 * 整列都是觸控目標，而且**完全沒有拖曳手勢** —— 這是重點。
 * 原本的滑桿橫躺在一頁可以上下捲的設定裡，使用者往下捲時手指落在滑桿上，
 * 門檻就被無聲地拖成 0。加一個 ✎ 按鈕沒有解決這件事，因為滑桿還在。
 * 唯一的解法是把拖曳這個手勢從這張卡片上移除。
 */
@Composable
private fun DurationRow(
    label: String,
    valueText: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            valueText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text("✎", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 滑桿。**只剩開發用的調參卡片在用**。
 *
 * 使用者會碰到的時間設定已經全部改成 [DurationRow] + 鍵盤輸入。
 * 這裡留著是因為調參那幾個值本來就是「拖一拖看感覺」的性質，
 * 而且那張卡片預計在 M3 直接對一般使用者隱藏。
 */
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

/**
 * 用鍵盤輸入一個時間值。
 *
 * 分、秒分成兩欄，因為門檻範圍是 30 秒～60 分鐘 ——
 * 要使用者輸入「600 秒」而不是「10 分鐘」很不友善。
 *
 * 解析與驗證的規則全在 [DurationInput]（純函式、18 個單元測試），
 * 這裡只負責把字串收進來、把結果送出去。
 */
@Composable
private fun DurationInputDialog(
    title: String,
    initialSeconds: Int,
    minSeconds: Int,
    maxSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    showMinutesField: Boolean = true,
    showSecondsField: Boolean = true,
    /** 常用值。空的就不顯示那一排按鈕。 */
    presetSeconds: List<Int> = emptyList(),
) {
    // **key 一定要帶 initialSeconds。**
    // 冷啟動時 DataStore 還沒讀完，settings 是預設值；使用者在那一瞬間點開
    // 對話框，兩個欄位就會被預設值鎖死，之後真實設定讀進來也不會更新。
    // 他什麼都沒打，按下確定卻把自己的門檻改掉了。
    var minutesText by remember(initialSeconds) {
        mutableStateOf(if (showMinutesField) (initialSeconds / 60).toString() else "")
    }
    var secondsText by remember(initialSeconds) {
        mutableStateOf(
            when {
                !showSecondsField -> ""
                !showMinutesField -> initialSeconds.toString()
                else -> (initialSeconds % 60).toString()
            },
        )
    }

    fun parsed(): Int? =
        if (!showMinutesField) {
            DurationInput.parse(secondsText, minSeconds, maxSeconds)
        } else {
            DurationInput.parseMinutesSeconds(
                minutesText = minutesText,
                secondsText = if (showSecondsField) secondsText else "",
                minTotalSeconds = minSeconds,
                maxTotalSeconds = maxSeconds,
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showMinutesField) {
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { minutesText = it },
                            label = { Text("分") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (showSecondsField) {
                        OutlinedTextField(
                            value = secondsText,
                            onValueChange = { secondsText = it },
                            label = { Text("秒") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (presetSeconds.isNotEmpty()) {
                    // 常用值一鍵帶入。滑桿唯一比輸入框強的地方就是「快速粗調」，
                    // 用按鈕一樣做得到，而且不會在捲動頁面時被誤觸。
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presetSeconds.forEach { preset ->
                            OutlinedButton(
                                onClick = {
                                    if (showMinutesField) {
                                        minutesText = (preset / 60).toString()
                                        secondsText = (preset % 60).toString()
                                    } else {
                                        secondsText = preset.toString()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                Text(DurationInput.format(preset), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Text(
                    "可輸入範圍：${DurationInput.format(minSeconds)} ～ ${DurationInput.format(maxSeconds)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 按下確定之前就先告訴你會存成什麼。
                // 夾範圍是靜悄悄發生的，不講的話使用者會以為自己打的數字被吃掉了。
                val preview = parsed()
                Text(
                    if (preview == null) {
                        "這樣的輸入看不懂，按確定不會改動原本的值。"
                    } else {
                        "會存成：${DurationInput.format(preview)}"
                    },
                    fontSize = 12.sp,
                    color = if (preview == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 解析不出來就什麼都不改 —— 這正是這次要修掉的那種
                // 「使用者沒想改，值卻變了」的情況。
                parsed()?.let(onConfirm)
                onDismiss()
            }) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
private fun SurvivalStatusCard(
    survival: SurvivalState,
    settings: AppSettings,
    actions: ScreenActions,
) {
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

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("被系統關掉時通知我", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "這個 app 失效的方式是無聲的：開關還是開的，但貓再也不出現。" +
                        "開著這個，系統把服務關掉超過 30 分鐘時會發一則通知告訴你。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.survivalAlertEnabled,
                onCheckedChange = { v -> actions.onSettingsChange { it.setSurvivalAlertEnabled(v) } },
            )
        }

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

/**
 * 讓服務活下去的設定引導。
 *
 * 文案與步驟**依手機廠牌而變**（見 [OemSurvival]）。原本這裡寫死了
 * 「OPPO / ColorOS」，對其他廠牌的使用者來說是一段照著做也做不到的說明 ——
 * 他們的手機裡根本沒有那個選單。
 */
@Composable
private fun SurvivalCard(state: PermissionState, actions: ScreenActions) {
    val oem = state.oem
    val title = if (oem.needsAutoStart) "讓貓活著（${oem.displayName} 必做）" else "讓貓活著"

    SectionCard(title) {
        Text(
            if (oem.needsAutoStart) {
                "${oem.displayName} 對背景程式管制很兇，不做這幾步的話，服務跑一陣子就會被系統殺掉，" +
                    "而且不會有任何提示 —— 你只會發現貓再也沒出現過。"
            } else {
                "你的系統接近原生 Android，沒有額外的自啟動管制，" +
                    "但仍建議把電池限制解除，否則長時間待機後服務可能被收掉。"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionRow(
            granted = state.batteryUnrestricted,
            title = "電池不受限制",
            why = "設定 → 電池 → 找到本 app → 選「不受限制 / 允許背景執行」。",
            onFix = actions.onOpenBattery,
        )
        if (oem.needsAutoStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自啟動管理", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "這個開關沒有 API 可查，請自行確認已開啟。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = actions.onOpenAutoStart) { Text("開啟") }
            }
        }
        // 文字步驟才是主要手段：上面那顆「開啟」按鈕用的是 OEM 私有介面，
        // 隨時可能因為改版而失效，但這幾行字永遠有效。
        Text(
            "在你的手機上的步驟：",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        oem.autoStartSteps.forEachIndexed { index, step ->
            Text(
                "${index + 1}. $step",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    installed: InstalledAppsState,
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
                "　${installed.labelOf(pkg)}　$count",
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
            steps = 0,
            onChange = { v ->
                // 原本會把值吸附到 7 的倍數。但**預設值 90 不是 7 的倍數** ——
                // 使用者只要碰一下這支滑桿，就再也回不到 90；365 同理也永遠存不進去
                // （round(365/7)*7 = 364）。吸附帶來的整齊不值得這個代價。
                actions.onSettingsChange { it.setStatsRetentionDays(v.roundToInt()) }
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
                value = "${String.format(Locale.US, "%.1f", d.densityPerMinute)} 次/分",
                threshold = "門檻 ≥ ${settings.rhythmMinDensityPerMinute}",
                pass = d.densityPerMinute >= settings.rhythmMinDensityPerMinute,
            )
            MetricRow(
                label = "變異係數 CV",
                value = if (d.coefficientOfVariation.isNaN()) "樣本不足" else String.format(Locale.US, "%.2f", d.coefficientOfVariation),
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

/**
 * 本機診斷紀錄（當機報告）。
 *
 * 這張卡是「不宣告網路權限」這個決定的另一面。代價很直接：app 爆掉時
 * 沒有任何東西會自動回報，開發者永遠不會知道，使用者只會覺得「這東西壞了」。
 * 所以紀錄留在手機上，並且給使用者一個明確的出口 —— 要不要送出、送給誰，由他決定。
 *
 * 沒有紀錄時這張卡也照樣顯示，不藏起來：使用者該在「還沒出事」的時候就知道
 * 這個機制存在，而不是出事之後才發現手機裡有一份他不知情的檔案。
 */
@Composable
private fun DiagnosticsCard(state: DiagnosticsState, actions: ScreenActions) {
    SectionCard("診斷紀錄") {
        Text(
            "本 app 沒有網路權限，當機時不會自動回報任何東西。程式若異常結束，" +
                "錯誤訊息會寫進這台手機的私有目錄 —— 其他 app 讀不到，解除安裝就一起消失。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "紀錄裡只有：時間、app 版本、Android 版本、手機型號、錯誤堆疊。" +
                "不含你在看住哪些 app、用了多久，也不含任何設定值。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        if (state.reportCount == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(true)
                Spacer(Modifier.width(10.dp))
                Text("目前沒有當機紀錄", fontSize = 14.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(false)
                Spacer(Modifier.width(10.dp))
                Text(
                    "有 ${state.reportCount} 份當機紀錄",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "匯出後可以傳給開發者協助修正。存到哪裡由你選，app 不會自己送出去。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.onExportDiagnostics) { Text("匯出診斷紀錄") }
            TextButton(onClick = actions.onClearDiagnostics) { Text("清除紀錄") }
        }
    }
}
