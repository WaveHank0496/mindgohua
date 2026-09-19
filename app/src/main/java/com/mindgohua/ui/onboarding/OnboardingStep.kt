package com.mindgohua.ui.onboarding

/**
 * 第一次打開這個 app 的引導流程 —— **純邏輯層**。
 *
 * ## 為什麼這一層完全不碰 Android
 *
 * 引導流程真正會出錯的地方不是畫面，而是「什麼時候該前進」：
 * 從系統設定頁回來要不要自動往下走、中途被撤銷的權限要不要退回去、
 * 跳過的那一步算不算完成、已經開好的權限要不要再問一次。
 * 這些全是**看不出來的錯**——畫面照樣會畫出來，只是把使用者卡在原地，
 * 或是讓他一路走到最後才發現主開關仍然是灰的。
 *
 * 所以這裡一個 `Context`、一個 `Intent` 都不引用，全部用一般 JVM 單元測試釘住
 * （見 `OnboardingFlowTest`）。Android 物件在單元測試環境裡只是沒有實作的空殼，
 * 碰到就會丟「Method not mocked」。
 *
 * ## 這個流程長什麼樣
 *
 * ```
 * WELCOME → PICK_APPS → OVERLAY → PREVIEW_CAT → USAGE → NOTIFICATION → DONE
 * ```
 *
 * 排序的理由寫在 `notes/onboarding-設計.md`，摘要：
 *  - 先選 app 再要權限，因為權限要求必須有具體的對象；
 *  - 懸浮視窗排在使用情況存取權前面，因為它是唯一看得見成果的那一個；
 *  - 預覽貓緊接在懸浮視窗之後，讓使用者現在就拿到報酬，
 *    順便證明剛剛那個權限真的生效了；
 *  - 通知排最後，因為它是唯一一鍵就過的，也是唯一**不影響主開關**的。
 */
enum class OnboardingStep {
    /** 這個 app 是幹嘛的。不編號 —— 它不是一件「要做的事」。 */
    WELCOME,

    /** 要看住哪些 app。刻意排在所有權限之前：這一步不需要任何權限。 */
    PICK_APPS,

    /** 懸浮視窗（系統裡叫「顯示在其他應用程式上層」）。 */
    OVERLAY,

    /** 現在就讓貓出現一次，不必等滑滿門檻。 */
    PREVIEW_CAT,

    /** 使用情況存取權。 */
    USAGE,

    /** 通知權限。唯一真正「一鍵」的那個。 */
    NOTIFICATION,

    /** 隱私總結 + 把主開關打開。不編號。 */
    DONE,
    ;

    /**
     * 要不要算進「第 n 步，共 m 步」。
     *
     * 歡迎頁與結束頁不算：對使用者來說那不是「要做的事」，
     * 把它們算進去只會讓那個數字看起來比實際要求的多。
     */
    val isNumbered: Boolean
        get() = this != WELCOME && this != DONE

    /**
     * 這一步在等哪個權限。沒有就是 null（歡迎、選 app、預覽、結束）。
     */
    val permission: OnboardingPermission?
        get() = when (this) {
            OVERLAY -> OnboardingPermission.OVERLAY
            USAGE -> OnboardingPermission.USAGE_ACCESS
            NOTIFICATION -> OnboardingPermission.NOTIFICATION
            else -> null
        }

    /**
     * 使用者可不可以自己跳過這一步。
     *
     * 只有權限步驟可以跳 —— 把人關在一個他找不到開關的頁面裡，
     * 換來的唯一出口是解除安裝。
     *
     * 選 app 不能跳：一個 app 都沒選的話這個程式不會做任何事，
     * 跳過它等於讓使用者帶著一個不會動的 app 走完流程。
     */
    val isSkippable: Boolean
        get() = permission != null || this == PREVIEW_CAT
}

/** 引導流程在乎的三個權限。其他權限（電池、無障礙）不在引導裡，理由見設計文件。 */
enum class OnboardingPermission { OVERLAY, USAGE_ACCESS, NOTIFICATION }

/**
 * 權限狀態的純資料版本。
 *
 * 刻意不直接用 `com.mindgohua.ui.PermissionState`：那個型別帶著 OEM profile
 * 這種引導流程用不到的東西，而且它屬於設定頁。轉換寫在 `OnboardingScreen.kt`
 * （UI 層）——邏輯層不需要知道設定頁長什麼樣。
 */
data class OnboardingPermissions(
    val overlay: Boolean = false,
    val usageAccess: Boolean = false,
    val notifications: Boolean = false,
) {
    fun isGranted(permission: OnboardingPermission): Boolean = when (permission) {
        OnboardingPermission.OVERLAY -> overlay
        OnboardingPermission.USAGE_ACCESS -> usageAccess
        OnboardingPermission.NOTIFICATION -> notifications
    }

    val allGranted: Boolean get() = overlay && usageAccess && notifications
}

/** 為什麼現在不能往下一步。null 表示可以走。 */
enum class OnboardingBlocker {
    /** 一個 app 都還沒選。 */
    NEEDS_APP_SELECTION,

    /** 這一步在等的權限還沒開，而且使用者也還沒選擇跳過。 */
    NEEDS_PERMISSION,
}

/**
 * 引導流程的進入點：要不要顯示、以及顯示哪幾步。
 */
object OnboardingFlow {

    /**
     * 這個人現在該不該看到引導。
     *
     * 兩種不顯示的情況：
     *
     * 1. **走完過了。** 不管當初是好好走完還是中途跳過，都不再問第二次。
     * 2. **他根本不需要。** 三個權限都已授權、而且已經選了至少一個 app ——
     *    這是「更新版本之後才裝上引導」的既有使用者。把他抓去重走一次
     *    五步流程，只會讓他覺得這個 app 把他的設定弄丟了。
     *
     * 注意第二種情況**必須同時檢查有沒有選 app**。只看權限的話，
     * 一個權限開好但目標清單是空的人會直接進到設定頁，
     * 然後對著一張「還沒有選任何 app」的紅字卡片自己想辦法。
     */
    fun shouldShow(
        completed: Boolean,
        permissions: OnboardingPermissions,
        targetCount: Int,
    ): Boolean {
        if (completed) return false
        return !(permissions.allGranted && targetCount > 0)
    }

    /**
     * 這一次要走哪幾步。
     *
     * **已經授權的權限步驟整個拿掉**，而不是保留著標成「已完成」。
     * 對一個不懂技術的人來說，「還要我做幾件事」才是他真正想知道的數字；
     * 「共 5 步，其中兩步已完成」是工程師的表達方式。
     *
     * 這份清單在流程開始時算一次就固定住（見 [start]）。中途不重算是刻意的：
     * 總步數在走到一半時變動，會讓那個數字失去意義。
     */
    fun stepsFor(permissions: OnboardingPermissions): List<OnboardingStep> = buildList {
        add(OnboardingStep.WELCOME)
        add(OnboardingStep.PICK_APPS)
        if (!permissions.overlay) add(OnboardingStep.OVERLAY)
        add(OnboardingStep.PREVIEW_CAT)
        if (!permissions.usageAccess) add(OnboardingStep.USAGE)
        if (!permissions.notifications) add(OnboardingStep.NOTIFICATION)
        add(OnboardingStep.DONE)
    }

    fun start(permissions: OnboardingPermissions, targetCount: Int): OnboardingState =
        OnboardingState(
            steps = stepsFor(permissions),
            index = 0,
            permissions = permissions,
            targetCount = targetCount,
        )
}

/**
 * 引導流程的當前狀態。不可變 —— 每個動作回傳一個新的狀態。
 *
 * @param steps 這一次要走的步驟，開始時決定，之後不變。
 * @param index 目前在 [steps] 的第幾個。
 * @param permissions 最近一次從系統查到的權限狀態。
 * @param targetCount 使用者已經選了幾個 app。
 * @param skipped 使用者**自己選擇**跳過的步驟。跟「還沒走到」是兩回事，
 *   因為跳過的步驟即使權限沒開也不會再把人拉回去。
 * @param visitedSettings 已經按過「去開這個開關」的步驟。用來決定要不要顯示
 *   「看起來還沒打開」那段補充說明 —— 第一次就先教他怎麼找，
 *   是在講他還沒遇到的問題；回來之後才講，才是在回答他當下的困惑。
 * @param finished 引導已經結束（走完、或使用者主動放棄）。
 */
data class OnboardingState(
    val steps: List<OnboardingStep>,
    val index: Int,
    val permissions: OnboardingPermissions,
    val targetCount: Int,
    val skipped: Set<OnboardingStep> = emptySet(),
    val visitedSettings: Set<OnboardingStep> = emptySet(),
    val finished: Boolean = false,
) {
    init {
        require(steps.isNotEmpty()) { "引導流程至少要有一頁" }
        require(index in steps.indices) { "index $index 不在 steps 範圍內" }
    }

    val step: OnboardingStep get() = steps[index]

    /** 「共 m 步」的 m。 */
    val totalSteps: Int get() = steps.count { it.isNumbered }

    /**
     * 「第 n 步」的 n。歡迎頁是 0 —— UI 在那一頁不顯示進度。
     */
    val stepNumber: Int get() = steps.take(index + 1).count { it.isNumbered }

    val canGoBack: Boolean get() = index > 0

    /**
     * 為什麼現在不能往下。
     *
     * 注意這裡**不讓按鈕變灰**：判斷出的結果是拿去顯示一行理由用的。
     * 「按了沒反應的灰按鈕」正是這次要修掉的病。
     */
    val blocker: OnboardingBlocker?
        get() = when {
            step == OnboardingStep.PICK_APPS && targetCount <= 0 ->
                OnboardingBlocker.NEEDS_APP_SELECTION

            step in skipped -> null

            step.permission?.let { !permissions.isGranted(it) } == true ->
                OnboardingBlocker.NEEDS_PERMISSION

            else -> null
        }

    val canAdvance: Boolean get() = blocker == null

    /** 現在能不能真的叫貓出來。沒有懸浮視窗權限就叫不出來（跳過的人會走到這裡）。 */
    val canPreviewCat: Boolean get() = permissions.overlay

    /** 使用者自己跳過、而且到現在仍然沒做的事。結束頁要把它們列出來。 */
    val unfinishedSkips: List<OnboardingStep>
        get() = skipped.filter { s -> s.permission?.let { !permissions.isGranted(it) } == true }
            .sortedBy { steps.indexOf(it) }

    /**
     * 往下一步。
     *
     * 前進時會略過「權限已經開好」的步驟：有些人會在系統設定裡順手
     * 把兩個開關一次都打開，那就不該再帶他去看第二頁一模一樣的說明。
     * （流程開始時就已授權的步驟在 [OnboardingFlow.stepsFor] 就被拿掉了，
     * 這裡處理的是**流程進行中**才變成已授權的情況。）
     */
    fun next(): OnboardingState {
        if (!canAdvance) return this
        if (index == steps.lastIndex) return copy(finished = true)
        var target = index + 1
        while (target < steps.lastIndex && steps[target].isAlreadyDone(permissions)) target++
        return copy(index = target)
    }

    /**
     * 使用者自己選擇跳過。
     *
     * 跳過會被記下來（[skipped]），因為它跟「還沒做」不一樣：
     * 之後權限狀態再怎麼變，都不會把他拉回這一步。
     */
    fun skip(): OnboardingState {
        if (!step.isSkippable) return this
        return copy(skipped = skipped + step).next()
    }

    fun back(): OnboardingState =
        if (!canGoBack) this else copy(index = index - 1)

    /** 記下「這一步已經送他去過系統設定了」。 */
    fun markSettingsOpened(): OnboardingState =
        copy(visitedSettings = visitedSettings + step)

    /** 使用者去過設定頁了，回來卻還是沒開成。 */
    val returnedWithoutGranting: Boolean
        get() = step in visitedSettings && blocker == OnboardingBlocker.NEEDS_PERMISSION

    /**
     * 權限狀態變了（`MainActivity.onResume()` 的 `refreshPermissions()` 之後）。
     *
     * **這是整個引導流程的核心。** 「從設定頁回來自動偵測」的機制本來就存在，
     * 缺的一直是「偵測到之後自動前進」。
     *
     * 三種處理，依序：
     *
     * 1. 已經結束就什麼都不做。引導不該從墳墓裡爬出來 ——
     *    結束之後的權限變動由設定頁的權限卡片負責。
     * 2. **先前走過、而且不是自己跳過的步驟，權限沒了 → 退回那一步。**
     *    不退回去的話，他會一路走到最後才發現主開關還是打不開，
     *    而且不知道是哪裡出了問題。
     * 3. 目前這一步等的權限開好了 → 自動前進。
     */
    fun withPermissions(updated: OnboardingPermissions): OnboardingState {
        if (finished) return copy(permissions = updated)

        val revoked = steps.take(index).firstOrNull { earlier ->
            earlier !in skipped &&
                earlier.permission?.let { !updated.isGranted(it) } == true
        }
        if (revoked != null) {
            return copy(permissions = updated, index = steps.indexOf(revoked))
        }

        val waitingFor = step.permission
        val justGranted = waitingFor != null &&
            !permissions.isGranted(waitingFor) &&
            updated.isGranted(waitingFor)
        val moved = copy(permissions = updated)
        return if (justGranted) moved.next() else moved
    }

    /** 使用者在選 app 那一步勾了或取消了東西。 */
    fun withTargetCount(count: Int): OnboardingState = copy(targetCount = count)

    /**
     * 使用者主動放棄整個引導（歡迎頁的「我自己來」）。
     *
     * 這條出路一定要留著。沒有它，一個找不到系統開關的人就只剩解除安裝。
     */
    fun abandon(): OnboardingState = copy(finished = true)

    private fun OnboardingStep.isAlreadyDone(permissions: OnboardingPermissions): Boolean =
        permission?.let { permissions.isGranted(it) } == true
}
