# mindgohua（麥勾滑）— Prototype

台語「麥溝滑」＝不要再滑了。偵測你在 Instagram / YouTube 等 app 裡的「無意識連續滑動」，用一隻全螢幕的貓打斷你。
全程在手機上處理，app 不具備網路權限。

<p align="center">
  <img src="docs/screenshots/overlay.jpg" width="300" alt="連續使用超過門檻後跳出的貓 overlay，兩顆按鈕在倒數結束前無法點選">
</p>

> **靈感來源：** 用貓打斷滑手機的概念來自 ZOKUZOKU 的
> [Cat Gatekeeper](https://zokuzoku.github.io/cat-gatekeeper/)（瀏覽器擴充功能 / 桌面版）。
> 本專案是在 Android 上獨立實作的版本，沒有使用原作的程式碼或素材（貓影片、圖示、logo）；
> app 裡的貓是專案自行繪製的向量圖。

依照 [`Mindgohua_Spec.md`](Mindgohua_Spec.md) 實作的第一階段原型。

偵測方式只有一種：**向系統查「哪個 app 在前景、待了多久」**，看不到畫面上的任何內容。
曾經有一個用無障礙服務讀滑動節奏的 Mode B，**v1 已完全移除**（理由見下方「架構」一節）。

**spec §8 六條驗收標準全數通過**（含 ColorOS 過夜存活 9 小時零中斷）。
實機環境：OPPO Reno7 5G（CPH2371）/ Android 13 / ColorOS，2026-07-24。

> 📐 **想了解整體架構與一次打斷的完整流程**，看
> [`docs/架構與運作流程.md`](docs/架構與運作流程.md) ——
> 那份文件是寫給不讀程式碼的人看的，而且每個數字都是實際量出來的。
>
> **接下來要做什麼**看 [`docs/SPEC-下一階段.md`](docs/SPEC-下一階段.md)。

> **關於開發方式：** 本專案以 AI 協作開發。規格（[`Mindgohua_Spec.md`](Mindgohua_Spec.md)）、
> 需求取捨與實機驗收由作者負責，程式碼實作以 Claude Code 輔助完成。
> 文件中提到的「使用者要求」，指的是作者在開發過程中提出的需求變更。

---

## 怎麼建置

已在上述實機建置、安裝、驗收通過。

1. 用 Android Studio（Ladybug 以上）開啟本資料夾，等 Gradle Sync 完成。
2. 接上手機（開發測試機為 OPPO Reno7），開啟開發者選項與 USB 偵錯，然後：

```bash
./gradlew installDebug
```

只跑純邏輯測試（不需要實機、不需要 SDK 之外的東西）：

```bash
./gradlew :app:testDebugUnitTest
```

---

## 專案結構

共 **5,523 行 Kotlin、32 個檔案**（2026-09-20 實測）。

```
app/src/main/java/com/mindgohua/
├── core/                       ← 純 Kotlin，不依賴 Android，可單元測試
│   ├── SessionEngine.kt           計時 / 冷卻 / 門檻 / GRACE 狀態機（spec §5）
│   ├── DayBoundary.kt             一天從幾點開始（可設定）
│   ├── ScrollRhythmAnalyzer.kt    ⚠️ 滑動節奏統計 —— 目前沒有任何呼叫端
│   └── ItemAdvanceCounter.kt      ⚠️ 數「滑過幾項」—— 目前沒有任何呼叫端
├── detect/
│   ├── Detector.kt                介面
│   ├── UsageStatsDetector.kt      唯一的偵測器：查前景 app
│   └── PollingPolicy.kt           省電規則：螢幕關了就停、閒置放慢
├── service/
│   ├── GatekeeperService.kt       Foreground Service，串起偵測→引擎→overlay
│   └── BootReceiver.kt            開機重啟
├── overlay/OverlayController.kt   全螢幕貓（spec §6）
├── stats/                         存活紀錄（每 60 秒的心跳）
├── settings/                      DataStore，只存設定
├── diagnostics/CrashLog.kt        本機當機紀錄，使用者可自行匯出
└── ui/
    ├── MainScreen.kt              設定頁
    └── onboarding/                ⚠️ 996 行，52 個測試，**但目前沒有入口**
```

`SessionEngine` 不知道訊號從哪裡來，只吃抽象事件 —— 這讓它能被一般單元測試
完整覆蓋（18 個測試），也是未來若要加回別種偵測方式時不必重寫的原因。

### ⚠️ 標了警告的那幾個，是「還在但沒在跑」

- **`ScrollRhythmAnalyzer` / `ItemAdvanceCounter`**：純演算法、零權限、零審核風險。
  刻意保留給未來，測試是它們唯一剩下的規格。
- **`ui/onboarding/`**：完整、有測試、但 `MainActivity` 完全沒有呼叫它。
  **接上去之前必須先修兩個會讓使用者卡死的問題**，詳見
  [`docs/架構與運作流程.md`](docs/架構與運作流程.md) 第 5 節。

### 為什麼刪掉 Mode B（無障礙服務）

不是隱藏，是**完全移除**。Play 掃的是上傳的安裝檔裡的設定檔，不是 app 的畫面 ——
只要那個服務宣告還在，就會觸發「無障礙用途宣告」審查，而那是目前拒絕率最高的類別之一。
我們的用法其實很乾淨、很好辯護，但那代表要花時間去打一場 v1 根本不需要打的仗。

現在有一條測試守著：任何人把無障礙宣告加回 manifest，`PrivacyInvariantTest` 就會紅。

---

## 隱私原則怎麼被強制

spec §0 的原則不是靠註解，是靠會失敗的測試（`PrivacyInvariantTest`）：

| 原則 | 強制方式 |
|---|---|
| 不得連網 | manifest 不宣告 `INTERNET`，而且用 `tools:node="remove"` **強制移除**任何第三方函式庫注入的網路權限。測試會同時檢查「沒有宣告」與「那行移除指令還在」 |
| 不得引入網路函式庫 | 測試會掃 `build.gradle.kts` 的相依清單 |
| 不讀畫面內容 | 本 app 只向系統查「哪個 app 在前景、從幾點到幾點」，拿不到畫面上的任何內容 |
| 沒有無障礙服務 | v1 完全移除，manifest 裡連宣告都沒有——`PrivacyInvariantTest` 會擋住任何人加回來 |
| 非目標 app | 系統送來的前景事件涵蓋所有 app，本程式**讀完立刻丟棄**不是目標的那些：不處理、不記錄、不留痕跡。這是程式自律，不是系統過濾——舊版曾靠 `ScrollWatchService` 的 `serviceInfo.packageNames` 做系統層過濾，那個機制隨 Mode B 一起移除了 |
| 不落地 | ⚠️ **已放寬**，見下 |

### 到底有什麼東西會寫進手機

| 寫什麼 | 在哪 | 會不會離開手機 |
|---|---|---|
| 你的設定 | `SettingsStore`（DataStore） | ❌ |
| 服務存活的時間戳 | `SurvivalLog`，每 60 秒一次 | ❌ |
| 當機紀錄 | `CrashLog` | 只有**你主動匯出**時 |
| 前景 app、滑動時間點 | **不存**，判斷完就丟 | — |

**偵測到的內容一律不落地。** 寫進手機的只有你的設定，
以及「服務有沒有正常在跑」的紀錄 —— 後者是為了在它被系統殺掉時能主動告訴你，
因為這個 app 的失效方式是靜默的。

> ⚠️ 舊版有一個「每日滑動計數」功能會把「日期 + app + 一個整數」寫進磁碟。
> 它的資料來源是無障礙事件，**隨 Mode B 一起移除**，現在不會再產生任何新資料。
> 設定頁的「進階與診斷」裡保留了一顆清除鍵，讓先前寫入的紀錄可以刪掉 ——
> 當初正當化「寫入磁碟」的理由就是「使用者可以自己清除」，
> 刪掉入口等於片面收回那個承諾。

---

## 驗收標準對照（spec §8）

**實機操作步驟見 [TESTING.md](TESTING.md)。**

### 第一階段驗收

實機驗證環境：**OPPO Reno7 5G（CPH2371）/ Android 13 / ColorOS**，2026-07-23。

| # | 項目 | 單元測試 | 實機 |
|---|---|---|---|
| 1 | 可選目標 app、設門檻 | — | ✅ |
| 2 | 超過門檻 → 貓 overlay | ✅ `連續使用超過門檻會觸發打斷` | ✅ |
| 3 | 短暫切出切回不歸零 | ✅ `冷卻期內切回來計時繼續累計`（含反例）| ✅ 含反例 |
| 4 | 兩顆按鈕 + GRACE | ✅ `解除後在GRACE期內不會再打斷` | ✅ 含解禁倒數與長按 |
| 5 | 無網路、不落地 | ✅ `PrivacyInvariantTest` | ✅ `dumpsys` 查無 INTERNET |
| 6 | ColorOS 長時間存活 | — | ✅ **9 小時零中斷**（過夜，拔電源）|

**單元測試 199 個全過，16 個 suite，0 失敗**（2026-09-20 從測試報告實際數出）。
最大的幾組：onboarding 流程 29、時間輸入解析 24、Session 狀態機 18、
onboarding 文案 14、存活提醒 / app 清單 / 設定預設值各 13。

其中 `PrivacyInvariantTest`（6 個）最特別 —— 它不測功能，**測的是承諾有沒有被違背**：
不得連網、不得引入網路函式庫、不得宣告無障礙服務、不得備份資料，
以及**隱私文案不得宣稱做不到的事**。

最後那條是後來補的：移除 Mode B 的時候，兩句「曾經為真」的話在沒有任何人動到它們的
情況下變成了不實陳述，而當時 146 個測試一個都沒紅。

### 診斷通知（調參與除錯用）

你滑 IG 的當下看不到設定頁，所以「進階與診斷」裡有一個開關，
打開後常駐通知會顯示即時數字（目前在哪個 app、已經幾秒、門檻多少），
下拉通知欄就能一邊滑一邊看。

代價是通知每 2 秒重畫一次，**預設關閉**，驗收完記得關掉。

---

## 防馴化設計（spec §6）

第一版做了兩層，因為「秒點就消失的按鈕」兩週後會退化成反射動作：

1. 貓出現後 **3 秒內兩顆按鈕都不能按**（可調 0–3 分鐘），畫面上有倒數。
2. 「再滑 N 分鐘」那顆要**按住 2 秒**才生效，並顯示進度百分比。
   「停下來」是單擊即可——不該讓做對的事比較費力。

另外 overlay 會吃掉返回鍵，不然一鍵 back 就跳過打斷了。
targetSdk 36 之後平台的返回鍵行為改變，所以這裡做了**兩道防線**：
傳統的 `KEYCODE_BACK` 攔截（API 30–32 只能靠它），
加上 API 33+ 的 `OnBackInvokedCallback`。理由詳見
[`docs/架構與運作流程.md`](docs/架構與運作流程.md) 第 2 節。

> 還有一個**尚未實作**的設計：一顆獨立的「我正在傳訊息」按鈕。
> 目的是讓「真的在回訊息」和「只是想滑」分開計次，避免單一按鈕退化成反射動作。
> 規格見 [`docs/SPEC-下一階段.md`](docs/SPEC-下一階段.md) 第三節。

---

## ColorOS 存活測試（spec §7，驗收關鍵指標）

OPPO / ColorOS 會殺掉 foreground service 且**不給任何提示**，
使用者只會發現貓再也沒出現過。所以這一項要當成功能來測。

### 前置設定（app 內「讓貓活著」區塊有按鈕帶過去）

1. 設定 → 電池 → mindgohua → **不受限制 / 允許背景執行**
2. 手機管家 / 安全中心 → **自啟動管理** → 允許 mindgohua
   （這個開關沒有公開 API 可查，只能靠使用者自己確認）
3. 最近任務畫面把本 app **上鎖**，避免一鍵清理掃掉

### 測試程序

```bash
# 1. 啟動後記錄 PID
adb shell pidof com.mindgohua

# 2. 每 10 分鐘檢查一次是否還活著、PID 有沒有變（變了代表被殺後重啟）
adb shell "while true; do echo \$(date +%H:%M) \$(pidof com.mindgohua); sleep 600; done"

# 3. 確認 foreground service 仍在
adb shell dumpsys activity services com.mindgohua | grep -i "isForeground\|app="

# 4. 看是否被系統以省電理由停掉
adb logcat -d | grep -iE "mindgohua|force stop|anr|killing"
```

### 通過條件

- 螢幕關閉 + 切換多個 app 後，**連續存活 ≥ 4 小時**，PID 不變。
- 期間打開 IG 滑超過門檻，貓仍會出現。
- 若 PID 變了但服務有回來（`START_STICKY` 或 `BootReceiver` 生效），記錄重啟間隔——
  可以接受，但要確認重啟後計時邏輯正常。

### 分階段記錄

建議至少測三種情境，因為失效模式不同：

| 情境 | 觀察重點 |
|---|---|
| 螢幕開著、前景使用其他 app | 幾乎不會被殺，基準線 |
| 螢幕關閉 1 小時 | ColorOS 深度睡眠的主要殺手 |
| 螢幕關閉過夜（8 小時） | 最嚴苛；多半需要自啟動白名單才過得了 |

---

## 已知風險與未完成項目

- **ColorOS 存活只在單一機型驗證過。** Reno7 過夜 9 小時零中斷，但其他 OEM
  （小米、vivo、三星）的殺後台策略不同，不保證同樣結果。步驟見 [TESTING.md 第 6 步](TESTING.md)。
- **🔴 沒有任何成效回饋。** 使用者只會被打斷，看不到自己成功停下來幾次 ——
  純成本、零獎勵。這是目前最大的留存風險，規格見 `SPEC-下一階段.md` 第四節。
- **🔴 首次引導還沒接上。** 新使用者打開 app 仍然是一整頁設定，
  而主開關在缺權限時是灰的、按下去沒有反應。
- **輪詢耗電沒有實測過。** 已改成螢幕關閉時完全停止、閒置時 6 秒、
  目標 app 內 2 秒，但實際耗電量仍未量測。
- **ColorOS 自啟動 intent 是脆弱的。** 那些 component name 是 OEM 私有介面，
  改版就可能失效，所以 UI 上同時附了文字步驟。
- **overlay 蓋不住系統關鍵畫面**（權限框、部分系統設定）。這是 Android 的保護機制，
  spec §6 明確說不用處理。
- 沒有做 instrumented test。overlay 的行為只能靠設定頁的「看看貓長什麼樣」手動驗。
- 診斷通知每 2 秒重畫一次，只該在調參時開。預設是關的。
- **`versionName` 還是 `0.1-prototype`、R8 未開、隱私政策與商店素材全缺** ——
  這些都是上架前必須處理的，清單見 `SPEC-下一階段.md` 第五節。
