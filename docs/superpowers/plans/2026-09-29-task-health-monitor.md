**Goal:** 让「任务此刻到底在不在正常跑」在主页一眼可见，「不动了」能被显式报成「卡住 · 已 N 分钟无进展」。

**Architecture:** 宿主进程只写事件时间戳与计数到 `config/<uid>/task_health.json`，界面侧由纯逻辑 `TaskHealthPolicy.evaluate` 按「无进展超时」现算状态 —— 因此宿主进程被杀、文件停在 RUNNING 也能如实显示成卡住。

**Tech Stack:** Kotlin / JUnit 4（纯逻辑单测）/ Jetpack Compose（卡片）/  Jackson（落盘）

## Global Constraints

- 文件 UTF-8 无 BOM；注释中文
- 只观测不干预：不得自动重试 / 取消 / 重启任务
- 不自动完成或绕过安全验证
- 状态**变化**才写日志；进展事件节流落盘（3s）

---

### Task 1: 纯判定逻辑 `TaskHealthPolicy`

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/task/TaskHealthPolicy.kt`
- Create: `app/src/test/java/fansirsqi/xposed/sesame/task/TaskHealthPolicyTest.kt`

- [x] **Step 1: 定状态枚举** —— 7 个：`IDLE / RUNNING / WAITING / OK / FAILED / BLOCKED / STALLED`
- [x] **Step 2: 定快照字段** —— 只存时间戳与计数（`lastStartAt / lastProgressAt / lastSuccessAt / lastFailureAt / consecutiveFailures / blockedReason / detail`）
- [x] **Step 3: 写失败测试** —— 覆盖「等待中不算卡住」「暂停优先于卡住」「未开始不因全局暂停而变暂停」
- [x] **Step 4: 最小实现 `evaluate`** —— 优先级：被暂停 > 卡住 > 记录的状态
- [x] **Step 5: `labelOf` / `describe`** —— 中英文案集中一处，界面与日志共用
- [x] **Step 6: 跑定向测试** → Run: `./gradlew.bat :app:testDebugUnitTest --tests "*TaskHealthPolicyTest*"`

### Task 2: 宿主侧事件入口 `TaskHealthMonitor`

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/task/TaskHealthMonitor.kt`

- [x] **Step 1: 事件入口** —— `onStart / onProgress / onWaiting / onSuccess / onFailure / onBlocked / onBlockedCleared`
- [x] **Step 2: 节流落盘** —— 状态变化立即写；同状态进展事件 3 秒内只写一次
- [x] **Step 3: 状态变化打一行日志** —— `任务状态：森林主任务 未开始 → 进行中（本轮开始）`，可直接 grep
- [x] **Step 4: 跨天清痕** —— `resetDailyIfNeeded()`，避免昨天的「正常」挂到今天
- [x] **Step 5: `readAll`** —— 供界面读取并现算状态

### Task 3: 埋点接入主链路任务

**Files:**
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/antForest/AntForest.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/antForest/EnergyWaitingManager.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/hook/ApplicationHook.kt`

- [x] **Step 1: 森林主任务** —— `onStart`（本轮开始）/ `onSuccess`（耗时 N 秒）/ `onFailure`
- [x] **Step 2: 收能量** —— `onStart` / 按好友 `onProgress` / `onSuccess`（收 N g）
- [x] **Step 3: 蹲点收取** —— `onWaiting` / 完成 / 终止 / 重试
- [x] **Step 4: 全局暂停** —— `ApplicationHook.setOffline` 处接 `onBlocked(reason)` / `onBlockedCleared()`

### Task 4: 主页卡片与刷新

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/card/TaskHealthCard.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/content/HomeContent.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/MainScreen.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/ui/viewmodel/MainViewModel.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/model/BaseModel.kt`

- [x] **Step 1: 新增设置项** —— `taskStallTimeoutMinutes` 默认 5（夹取 1–120）
- [x] **Step 2: 卡片 UI** —— 标题 + 右上「一切正常 / ⚠ N 项需关注」+ 三项状态行与一句说明
- [x] **Step 3: 接 ViewModel** —— `refreshTaskHealth()`，主页停留时每 5 秒刷新
- [x] **Step 4: 卡片插到主页顶部**（在模块激活状态卡之上）

### Task 5: 设备验证

- [x] **Step 1: 装 APK + 重启支付宝** —— 宿主侧代码必须重启支付宝才生效
- [x] **Step 2: 确认落盘** —— `config/<uid>/task_health.json` 三项齐全、时间戳正确
- [x] **Step 3: 确认日志** —— `任务状态：森林主任务 未开始 → 进行中` … `→ 正常`
- [x] **Step 4: 确认卡片渲染** —— dump UI：森林主任务/收能量/蹲点收取 三行 + 状态标签 + 说明
- [x] **Step 5: 构造异常数据验证三态** —— 卡住 / 失败 / 已暂停，标题变「⚠ 3 项需关注」

### Task 6: 修「界面读不到文件」

**发现：** 模块 App 进程里没有支付宝登录态，`UserMap.currentUid` 为空，按 uid 拼路径读到空文件，
卡片永远显示「还没有任务状态记录」。

- [x] **Step 1: `resolveHealthFile`** —— uid 为空时扫 `config/` 下的账号目录，取**最近改过**的那份
- [x] **Step 2: ViewModel 优先传当前账号** —— `DataStore` 里的 `activedUser`，拿不到才走扫描兜底
- [x] **Step 3: 复验** —— 重启模块 App，卡片读到真实数据

### Task 7: 文档与收尾

- [x] **Step 1: `changelog.d/20260929-task-health-monitor.md`**
- [x] **Step 2: `docs/architecture.md` §12** —— 状态机设计、七个状态、判定优先级、三条硬约束
- [x] **Step 3: `docs/user-guide.md`** —— 主页卡片怎么看 + 设置项说明
- [x] **Step 4: `docs/component-api.md` §H** —— API 与两个坑（uid 为空、`hasFlagToday` 不能跨天）
- [x] **Step 5: 文档索引同步** —— `AGENTS.md`、`docs/project-overview.md`
- [x] **Step 6: 全量单测 + 构建** → Run: `./gradlew testDebugUnitTest assembleDebug`

---

## 轮次二：实机暴露的两个缺陷（2026-09-29 下午）

前六项在 Redmi 上跑通后，装机复验时暴露出两个**设计稿漏掉**的问题，都在这一步修掉并复验。

### Task 8: 重启支付宝会把状态抹成「未开始」

**发现过程：** 重启支付宝后 `task_health.json` 变成三项全 `IDLE`、时间戳全 0。
原因是宿主进程重启后内存态是空的，`persistNow()` 直接把空内存写了出去。

**为什么必须修：** 「明明有能量却不动 → 重启支付宝看看」是用户最常用的排查动作，
抹掉历史等于在最需要线索的时刻把线索删了。

**Files:**
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/TaskHealthMonitor.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/TaskHealthPolicy.kt`
- Modify: `app/src/test/java/fansirsqi/xposed/sesame/task/TaskHealthPolicyTest.kt`

- [x] **Step 1: 抽出纯函数** —— `TaskHealthPolicy.belongsToToday(snapshot, now)`，
      按 `max(lastStartAt, lastProgressAt, lastSuccessAt, lastFailureAt)` 的自然日判定
- [x] **Step 2: `syncDailyState(now)`** —— 写盘前先把磁盘上属于今天的记录接进内存；
      同一自然日只读一次盘（`loadedDay` 短路）。同时取代原先只活在内存里的 `resetDailyIfNeeded`
- [x] **Step 3: 界面侧同一口径** —— `readAll` 也过滤掉不属于今天的记录
- [x] **Step 4: 补 4 条单测** —— 今天 / 昨天 / 看的是最新时间戳 / 全零按属于今天
- [x] **Step 5: 实机复验** —— 先种一份今天的 OK 状态，重启支付宝后 `lastSuccessAt` 与 `detail` 全部保留

### Task 9: 「已暂停」根本没落到盘上

**发现过程：** 设备当时正处于安全验证暂停，日志里有 `⛔ 已进入离线`，
但 `task_health.json` 里 `blockedReason` 是空串 —— 因为 `ordered()` 没把它带上。

**为什么必须修：** 界面读的是文件，拿不到 `blockedReason` 就不会显示「已暂停」，
只会显示「卡住」。而这两者的处置方式完全不同（等自愈 vs 人工过验证），等于把人往错的方向带。

**Files:**
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/TaskHealthMonitor.kt`

- [x] **Step 1: `ordered()` 给每一项带上 `blockedReason`** —— 它是全局的，不写进去界面就看不到
- [x] **Step 2: `snapshots()` 去掉重复处理** —— 已在 `ordered()` 统一做
- [x] **Step 3: 实机复验** —— 文件里 `blockedReason : "安全验证暂停中"`；
      主页卡片显示「已暂停 · 安全验证暂停中 · 收 12g · 上次成功 09:33」，标题「⚠ 2 项需关注」
