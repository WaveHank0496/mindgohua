# Cat Gatekeeper (Android) — 專案架構與 Prototype 規格

> 給 Claude Code 的實作規格。目標：Android 原生 app，偵測使用者在特定 app（IG / YouTube）內的「無意識連續滑動」，並用全螢幕 overlay 打斷。第一階段只求「能在實機跑起來、扛得住 ColorOS 殺後台」，不做花俏功能。

---

## 0. 核心設計原則（不可違背）

1. **本機處理、不落地、不外傳。** 任何從畫面或事件讀到的資料，判斷完立即丟棄，不寫入任何持久化儲存，不經任何網路傳出。
2. **無網路權限。** `AndroidManifest` 不宣告 `android.permission.INTERNET`。這是隱私的硬保證：系統層級即不可能連網。
3. **行為自限。** 即使持有全域感知權限，程式邏輯只在目標 app（IG / YouTube）前景時運作，其他 app 一律 early return、不做任何讀取。
4. **兩種偵測模式並存**（見下），使用者可選，對應兩種信任層級。

---

## 1. 兩個版本 / 兩種偵測模式

App 內用一個設定開關切換偵測引擎，共用同一套「計時 → 打斷」下游邏輯。

### Mode A：Privacy Mode（乾淨版，預設）
- **來源**：`UsageStatsManager` 的 `UsageEvents`（`queryEvents`）。
- **能力**：只知道「哪個 app 在前景、連續多久」，**看不到畫面內容**。
- **判斷**：配對 `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND` 事件，計算單次 session 長度；連續超過門檻即觸發。
- **適合**：不信任開發者、或只想要「待太久就打斷」的使用者。
- **限制**：分不出「在滑 Reels」還是「在回訊息」，只能做「在此 app 連續停留過久」。

### Mode B：Focus Mode（精準版，需額外授權）
- **來源**：`AccessibilityService` 的 **scroll event 時間戳**（`TYPE_VIEW_SCROLLED`）。
- **能力**：偵測「滑動的節奏」以判斷無意識刷動。**只讀捲動事件的時間戳，不抓畫面節點樹、不讀任何文字內容。**
- **判斷**：對滑動時間戳序列做滑動窗口統計（見 §4），判定「規律、密集、持續」→ 觸發。
- **適合**：信任開發者、要最佳效果的使用者。
- **限制/代價**：需開 AccessibilityService（授權畫面較嚇人）；偵測邏輯需維護。

> Prototype 階段：**Mode A 先做完、跑穩**，Mode B 的滑動偵測作為第二階段模組，介面預留但可先 stub。

---

## 2. 系統架構（元件分層）

```
┌─────────────────────────────────────────────┐
│  UI Layer (Settings + Onboarding)            │
│  - 選目標 app、設門檻分鐘/秒、選 Mode A/B     │
│  - 權限引導頁（逐步帶使用者開權限）           │
│  - 隱私說明頁（講清楚看得到/看不到什麼）      │
└─────────────────────────────────────────────┘
                    │ 讀寫設定
                    ▼
┌─────────────────────────────────────────────┐
│  Settings Store (本機，DataStore/SharedPref)  │
│  - 只存「使用者的設定」，不存任何偵測資料      │
└─────────────────────────────────────────────┘
                    │ 提供設定
                    ▼
┌─────────────────────────────────────────────┐
│  Detection Layer（可插拔，兩個實作）          │
│  - interface Detector { onSignal(); }         │
│  - UsageStatsDetector  (Mode A)               │
│  - ScrollAccessibilityDetector (Mode B)       │
└─────────────────────────────────────────────┘
                    │ 回報「目標 app 前景中 + 使用狀態」
                    ▼
┌─────────────────────────────────────────────┐
│  Session Engine（核心邏輯，與偵測來源無關）    │
│  - 維護當前 session 起訖、冷卻判斷            │
│  - 超過門檻 → 發出 INTERRUPT 事件             │
└─────────────────────────────────────────────┘
                    │ INTERRUPT
                    ▼
┌─────────────────────────────────────────────┐
│  Overlay Layer                                │
│  - SYSTEM_ALERT_WINDOW 疊全螢幕「貓」          │
│  - 使用者互動後解除（見 §5）                  │
└─────────────────────────────────────────────┘
```

**關鍵設計**：`Session Engine` 不知道偵測來源是誰，只吃「目標 app 是否前景 / 是否處於活躍使用」這個抽象訊號。這樣 Mode A / B 可以熱插拔，共用同一套計時與打斷邏輯。

---

## 3. 權限清單與用途（每個都要在隱私頁對使用者交代）

| 權限 | 用途 | 哪個 Mode 需要 |
|---|---|---|
| `PACKAGE_USAGE_STATS`（特殊權限，需跳系統設定授權） | 讀前景 app 事件流 | Mode A |
| `BIND_ACCESSIBILITY_SERVICE` | 讀 scroll 事件時間戳 | Mode B |
| `SYSTEM_ALERT_WINDOW` | 疊 overlay 打斷 | 兩者皆需 |
| `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` | 常駐服務不被殺、顯示常駐通知 | 兩者皆需 |
| ~~`INTERNET`~~ | **刻意不宣告** | 無 |

---

## 4. Mode B 的滑動偵測演算法（第二階段，先寫清楚原理）

**輸入**：一串滑動事件時間戳 `t_1, t_2, ...`（來自 `TYPE_VIEW_SCROLLED`）。

**目標**：判斷是否處於「無意識刷動」——特徵是**間隔短、變異小、密集、持續**。有意識使用則間隔長短混雜、有明顯停頓。

**第一版方法（輕量，O(1) 均攤）**：
- 維護一個環形緩衝區 (ring buffer)，只保留最近 W 秒（例如 90s）內的時間戳。
- 每來一個新事件：推入緩衝、把滑出窗口的丟掉。
- 計算兩個特徵：
  - **密度**：窗口內事件數 / W（滑得夠不夠頻繁）。
  - **間隔變異**：相鄰間隔的變異係數 (coefficient of variation = 標準差/平均)。越小代表越規律。
- **觸發條件**：密度 > 門檻 且 變異 < 門檻，且此狀態**持續** T 秒（例如 60s）。
- 複雜度：每事件更新 O(1) 均攤（用 running sum / running sum of squares 增量維護標準差），完全不吃電。

**進階（之後才做）**：間隔序列的自相關 (autocorrelation) 或熵 (entropy) 量化可預測性。第一版不要碰。

**調參**：密度門檻、變異門檻、持續時間，需拿真實使用資料反覆測。做成可調參數，別寫死。

---

## 5. Session Engine 邏輯（Mode A/B 共用）

**Mode A（UsageEvents）**：
- 收到目標 app `MOVE_TO_FOREGROUND` → session 開始計時。
- 收到 `MOVE_TO_BACKGROUND` → **不立即歸零**，進入冷卻。
- **冷卻判斷**：若在 COOLDOWN 秒（例如 10s）內又回到前景 → 視為同一 session、繼續累計；超過冷卻才回來 → 開新 session。
  - 理由：防止使用者「切出去瞄一眼通知再切回來」就把計時洗掉。這是 session 邊界問題（等同網路分析用 inactivity timeout 切 session）。
- session 連續時長 > 使用者門檻 → 發 INTERRUPT。

**Mode B**：偵測器判定「進入無意識刷動且持續 T 秒」→ 直接發 INTERRUPT（門檻邏輯在偵測器內）。

**打斷後**：overlay 出現。使用者解除後，設一段 GRACE 期（例如 X 分鐘內不再打斷），避免連環轟炸。GRACE 可設定。

---

## 6. Overlay（打斷體驗）

- 用 `SYSTEM_ALERT_WINDOW` 疊一個全螢幕 View，蓋住當前 app。
- 第一版內容：一隻貓（靜態圖或簡單動畫）+ 一句話（「你已經連續滑了 X 分鐘，還要繼續嗎？」）。
- **解除方式**（防馴化關鍵，第一版先簡單）：
  - 提供「停下來（回桌面）」與「再滑 5 分鐘（解除 overlay）」兩顆按鈕。
  - **注意防馴化**：解除不能是「秒點就消失」，否則兩週後退化成反射點掉的同意鍵。第一版可先做「按鈕要按住 2 秒」或「等 3 秒才可點」的微延遲；進階再考慮變化形式。
- overlay **蓋不住系統關鍵畫面**（權限框、部分系統設定），這是 Android 保護，屬正常，不用處理。

---

## 7. ColorOS / OPPO 殺後台問題（實機頭號障礙，必須處理）

目標實機是 **OPPO Reno7 5G（ColorOS，Android 11 起）**。ColorOS 後台管制極兇，會殺掉 AccessibilityService 與 foreground service。實作與測試須包含：

- 用 **Foreground Service** + 常駐通知維持存活。
- Onboarding 引導使用者手動設定：電池 → 允許背景執行 / 不受限制；ColorOS「自啟動管理」允許本 app。
- 預期會遇到「跑一陣子就失效」，需在實機反覆驗證服務存活時間。
- 這是 prototype 驗收的關鍵指標之一：**服務能否在螢幕關閉、切換 app 後持續存活 ≥ 數小時。**

---

## 8. Prototype 驗收標準（第一階段，只做 Mode A）

1. 使用者可在設定選「IG」為目標、設門檻（例如 2 分鐘）。
2. 打開 IG 連續停留超過門檻 → 貓 overlay 蓋住 IG。
3. 短暫切出再切回（<冷卻秒數）→ 計時不歸零。
4. overlay 兩顆按鈕可運作；解除後 GRACE 期內不再打斷。
5. 全程無網路權限、無任何資料寫入持久化儲存。
6. 服務在 ColorOS 上能長時間存活（附存活測試說明）。

Mode B（滑動偵測）：介面與 `Detector` 抽象先做好，實作可 stub，第二階段再補 §4 演算法。

---

## 9. 技術選型建議（給 Claude Code 參考，非強制）

- 語言：Kotlin。
- 最低 SDK：對齊 Android 11（API 30），因目標機是 ColorOS on Android 11 起。
- 設定儲存：Jetpack DataStore（或 SharedPreferences）。
- 架構：偵測層用 `interface Detector` 抽象，兩實作可插拔；Session Engine 純邏輯、可單元測試、與 Android framework 解耦。
- **不要**引入任何需要網路的函式庫或分析 SDK（會破壞「無 INTERNET 權限」原則）。

---

## 10. 明確不要做（避免 scope 爆炸）

- 不做帳號、登入、雲端同步（違反無網路原則）。
- 不做跨裝置、不做 iOS。
- 第一版不做「按職業出題解鎖」「拍照任務」等花俏解除（已評估為易馴化、內容無底洞）。
- 不抓 AccessibilityService 的畫面節點樹 / 文字內容（只用 scroll 事件時間戳）。
