package com.mindgohua.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindgohua.R
import com.mindgohua.settings.InstalledApps
import com.mindgohua.ui.InstalledAppsState
import com.mindgohua.ui.PermissionState
import com.mindgohua.ui.ScreenActions

/**
 * 引導流程的畫面。
 *
 * 這個檔案**只負責畫**：什麼時候能前進、要不要自動往下走、
 * 哪一步被跳過了，全部在 [OnboardingState]（純邏輯、有測試）。
 * 這裡一個 if 都不該用來做流程判斷。
 *
 * 版面的三個刻意決定：
 *
 *  - **一頁只講一件事。** 設定頁那面七張卡片的牆，問題不在資訊量，
 *    在於它要使用者自己決定先做哪一個。
 *  - **主要按鈕永遠是可以按的。** 灰掉又按不動的按鈕正是這次要修的病；
 *    條件沒滿足時按下去會得到一句話，而不是什麼都沒發生。
 *  - **次要出路永遠在。** 每一個權限步驟都給得起「先跳過」。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    installedApps: InstalledAppsState,
    selectedPackages: Set<String>,
    actions: OnboardingActions,
) {
    val copy = OnboardingCopy.of(state.step, state.totalSteps)

    // 「按了下一步卻走不動」之後才顯示理由。一進頁面就先貼一行紅字，
    // 是在指責一個還沒做錯任何事的人。
    var nudged by remember(state.step) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ProgressHeader(state, actions)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.step == OnboardingStep.WELCOME || state.step == OnboardingStep.DONE) {
                Spacer(Modifier.height(8.dp))
                Image(
                    painter = painterResource(R.drawable.ic_cat),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }

            Text(
                copy.title,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
            )
            Text(copy.body, fontSize = 15.sp, lineHeight = 23.sp)

            copy.bullets.forEach { line ->
                Text(
                    "・$line",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state.step) {
                OnboardingStep.PICK_APPS -> AppPicker(
                    installedApps = installedApps,
                    selectedPackages = selectedPackages,
                    onToggle = actions.onToggleTarget,
                )

                OnboardingStep.PREVIEW_CAT -> if (!state.canPreviewCat) {
                    NoteCard(copy.afterSkip.orEmpty())
                }

                OnboardingStep.DONE -> {
                    val pending = state.unfinishedSkips
                    if (pending.isNotEmpty()) {
                        NoteCard(
                            OnboardingCopy.unfinished(
                                pending.map { OnboardingCopy.of(it, state.totalSteps).title },
                            ),
                        )
                    }
                }

                else -> Unit
            }

            // 送他去系統設定之前的預告。Android 11 之後那兩個設定頁都不吃套件參數，
            // 一定會跳到一長串清單 —— 做不到一鍵，就先把會看到的東西講清楚。
            copy.headsUp?.let { HeadsUpCard(it) }

            if (state.returnedWithoutGranting) {
                copy.retry?.let { NoteCard(it) }
            }

            if (nudged && state.blocker == OnboardingBlocker.NEEDS_APP_SELECTION) {
                Text(
                    OnboardingCopy.NEEDS_APP_SELECTION,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        Footer(
            state = state,
            copy = copy,
            actions = actions,
            onBlocked = { nudged = true },
        )
    }
}

/** 進度。歡迎頁與結束頁不顯示 —— 那兩頁不是「要做的事」。 */
@Composable
private fun ProgressHeader(state: OnboardingState, actions: OnboardingActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.canGoBack) {
            TextButton(onClick = actions.onBack) { Text("‹ 上一步") }
        }
        Spacer(Modifier.weight(1f))
        if (state.step.isNumbered) {
            Text(
                "第 ${state.stepNumber} 步，共 ${state.totalSteps} 步",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
    if (state.step.isNumbered) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(state.totalSteps) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (i < state.stepNumber) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun HeadsUpCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("按下去之後會發生什麼", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun NoteCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(14.dp))
    }
}

/**
 * 選 app。
 *
 * 只列**這台手機上真的有的** app，常見的排在最前面（見 [OnboardingSuggestions]）。
 * 完整清單與搜尋框在下面，想選什麼都可以。
 */
@Composable
private fun AppPicker(
    installedApps: InstalledAppsState,
    selectedPackages: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 已勾選、但已經不在這台手機上的套件，靜默取消勾選。
    // 這處理的是「預設鎖死 Instagram，但這個人沒裝 IG」——
    // 那不是使用者的錯，不該用一行紅字丟回去給他。
    val installedPackages = installedApps.entries.map { it.packageName }
    val stale = if (installedApps.loading) {
        emptyList()
    } else {
        OnboardingSuggestions.staleSelection(selectedPackages, installedPackages)
    }
    LaunchedEffect(stale) {
        stale.forEach { onToggle(it, false) }
    }

    if (installedApps.loading) {
        Text(
            "正在讀取這台手機上的 app…",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (stale.isNotEmpty()) {
        NoteCard(OnboardingCopy.removedUninstalled(stale.map { installedApps.labelOf(it) }))
    }

    val suggested = OnboardingSuggestions.rank(installedPackages, selectedPackages)
    suggested.forEach { pkg ->
        AppRow(
            label = installedApps.labelOf(pkg),
            checked = pkg in selectedPackages,
            onCheckedChange = { onToggle(pkg, it) },
        )
    }

    TextButton(onClick = { showAll = !showAll }) {
        Text(if (showAll) "收起清單" else "顯示這台手機上的全部 app")
    }

    if (showAll) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜尋 app") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 這裡刻意不用 LazyColumn：整頁已經在一個 verticalScroll 裡，
        // 巢狀的可捲動容器會讓 Compose 量不出高度而直接崩潰。
        InstalledApps.sortForPicker(
            InstalledApps.filterByQuery(installedApps.entries, query),
            selectedPackages,
        ).forEach { entry ->
            if (entry.packageName !in suggested) {
                AppRow(
                    label = entry.label,
                    checked = entry.packageName in selectedPackages,
                    onCheckedChange = { onToggle(entry.packageName, it) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 16.sp)
    }
}

@Composable
private fun Footer(
    state: OnboardingState,
    copy: StepCopy,
    actions: OnboardingActions,
    onBlocked: () -> Unit,
) {
    // 貓預覽過一次之後，主按鈕改成「下一步」。
    var previewed by remember(state.step) { mutableStateOf(false) }

    val label = when {
        state.step == OnboardingStep.PREVIEW_CAT && !state.canPreviewCat -> "下一步"
        state.step == OnboardingStep.PREVIEW_CAT && previewed -> copy.primaryAfter ?: copy.primary
        else -> copy.primary
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = {
                when (state.step) {
                    OnboardingStep.OVERLAY ->
                        actions.onOpenSettingsFor(OnboardingPermission.OVERLAY)

                    OnboardingStep.USAGE ->
                        actions.onOpenSettingsFor(OnboardingPermission.USAGE_ACCESS)

                    OnboardingStep.NOTIFICATION ->
                        actions.onOpenSettingsFor(OnboardingPermission.NOTIFICATION)

                    OnboardingStep.PREVIEW_CAT -> when {
                        !state.canPreviewCat -> actions.onNext()
                        previewed -> actions.onNext()
                        else -> {
                            previewed = true
                            actions.onPreviewCat()
                        }
                    }

                    OnboardingStep.DONE -> actions.onFinish(true)

                    else -> {
                        // 走不動時不是「沒反應」，而是給一句話。
                        if (state.canAdvance) actions.onNext() else onBlocked()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(label, fontSize = 16.sp)
        }

        copy.secondary?.let { secondary ->
            TextButton(
                onClick = {
                    when (state.step) {
                        OnboardingStep.WELCOME -> actions.onAbandon()
                        OnboardingStep.DONE -> actions.onFinish(false)
                        else -> actions.onSkip()
                    }
                },
            ) {
                Text(secondary, fontSize = 14.sp)
            }
        }
    }
}

/**
 * 引導流程需要的動作。
 *
 * 刻意跟設定頁的 `ScreenActions` 分開：那一組有十四個動作，
 * 引導只用得到其中四個，而且語意不同（例如「預覽貓」在設定頁是驗收工具，
 * 在這裡是報酬）。轉接寫在 [onboardingActions]。
 */
data class OnboardingActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onSkip: () -> Unit,
    val onAbandon: () -> Unit,
    val onOpenSettingsFor: (OnboardingPermission) -> Unit,
    val onPreviewCat: () -> Unit,
    val onToggleTarget: (String, Boolean) -> Unit,
    /** true = 順手把主開關打開。走完五步之後還要自己回設定頁找開關，是多餘的一道坎。 */
    val onFinish: (Boolean) -> Unit,
)

/**
 * 把引導的動作接到 `MainActivity` 既有的 [ScreenActions] 上。
 *
 * **不需要在 `ScreenActions` 新增任何東西** —— 引導用到的四個動作
 * （開懸浮視窗設定、開使用情況設定、要通知權限、預覽貓）全部已經存在。
 *
 * @param state 目前狀態。
 * @param update 狀態變了要往哪裡寫回去。
 * @param screen 設定頁既有的動作組。
 * @param onFinish 引導結束（走完或放棄）。參數 true 表示順便打開主開關。
 */
fun onboardingActions(
    state: OnboardingState,
    update: (OnboardingState) -> Unit,
    screen: ScreenActions,
    onFinish: (Boolean) -> Unit,
): OnboardingActions = OnboardingActions(
    onNext = { update(state.next()) },
    onBack = { update(state.back()) },
    onSkip = { update(state.skip()) },
    onAbandon = {
        update(state.abandon())
        onFinish(false)
    },
    onOpenSettingsFor = { permission ->
        // 先記下「已經送他去過了」，回來還是沒開成時才顯示補充說明。
        update(state.markSettingsOpened())
        when (permission) {
            OnboardingPermission.OVERLAY -> screen.onOpenOverlay()
            OnboardingPermission.USAGE_ACCESS -> screen.onOpenUsageAccess()
            OnboardingPermission.NOTIFICATION -> screen.onRequestNotifications()
        }
    },
    onPreviewCat = screen.onTestInterrupt,
    onToggleTarget = { pkg, selected ->
        screen.onSettingsChange { it.toggleTarget(pkg, selected) }
    },
    onFinish = onFinish,
)

/**
 * 設定頁的權限狀態 → 引導流程在乎的那三個。
 *
 * 轉換放在 UI 層，是為了讓 [OnboardingPermissions] 那一層不必認得
 * `PermissionState`（它還帶著 OEM profile 這些引導用不到的東西）。
 */
fun PermissionState.toOnboarding(): OnboardingPermissions = OnboardingPermissions(
    overlay = overlay,
    usageAccess = usageAccess,
    notifications = notifications,
)
