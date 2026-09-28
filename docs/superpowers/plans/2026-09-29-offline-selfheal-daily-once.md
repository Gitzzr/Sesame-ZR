# 离线自愈与「一天一次」任务 —— 实施计划与记录

> 设计稿：[`../specs/2026-09-29-offline-selfheal-daily-once-design.md`](../specs/2026-09-29-offline-selfheal-daily-once-design.md)
> 状态：已实施（2026-09-29，PR #34）；离线全链路与次日核对待实机复核。

**Goal:** 让离线/暂停不再静默停摆（自动探测恢复 + 不空转 + 显式日志），
并把「一天一次」的任务改成成功后锁定、附可追溯台账。

**Architecture:** 纯策略（`OfflineRecoveryPolicy`）描述探测节奏；`ApplicationHook.setOffline` 作为状态唯一入口
驱动看门狗与日志；`RequestManager` 提供绕过门控的探活；`EnergyWaitingManager` 在离线期间挂起、解除后恢复。
台账侧由纯逻辑 `DailyOnceAudit`（merge/trim/dayKey）加 IO 包装实现。

**Tech Stack:** Kotlin / Java / kotlinx.coroutines / kotlinx.serialization 无关的 Jackson（`JsonUtil`）/ JUnit 4。

## Global Constraints

- 文件 UTF-8 无 BOM；注释中文；判定逻辑尽量抽成 `*Policy` 并配单测。
- 台账是旁路观测：**任何失败都不得影响任务流程**。
- 锁定**只发生在服务端返回成功时**；失败/未受理不得锁定。
- 探测复用既有 RPC（森林主页查询），不引入新接口面。
- 「一天一次」的开关语义与既有的 `Status.hasFlagToday` 保持一致（同一套 flagList）。

## 一、任务拆解

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | 离线探测节奏策略 | `hook/OfflineRecoveryPolicy.kt` + 测试 |
| 2 | `setOffline` 收口 + 显式日志 + 看门狗 | `hook/ApplicationHook.kt` |
| 3 | 绕过门控的探活 | `hook/RequestManager.kt` `probeOfflineRecovery` / `rawProbe` |
| 4 | 蹲点离线不空转 + 解除后恢复 | `task/antForest/EnergyWaitingManager.kt` |
| 5 | 台账 | `task/DailyOnceAudit.kt` + 测试 |
| 6 | 第一批锁定 | `task/antFarm/AntFarm.kt`、`task/antForest/EcoLife.kt` |
| 7 | 核对入口 | `ui/screen/DailyTaskCheckScreen.kt`「每日一次」区块 |
| 8 | 第二批降噪 | `AntOcean.java` / `TaskExecutor.kt` / `AntMember.kt` / `AntForest.kt` |
| 9 | 文档 | 本文件 + 设计稿 + `docs/daily-once-tasks.md` + `architecture.md` §9.3/§9.4/§11 |

## 二、实施记录

- [x] **1** 探测节奏 2/5/15/30 分钟、封顶 30、重开支付宝 ≤1 次、5 次后静默（`OfflineRecoveryPolicyTest` 3 项）
- [x] **2** `setOffline` 成为唯一入口（原先 `ApplicationHook` 内两处直接赋值也改走它）：
      进入打 `⛔ 已进入离线`，解除打 `✅ 离线已解除（持续 N 分钟）`，并 `startOfflineProbe(1)`
- [x] **3** `probeOfflineRecovery`：验证暂停先试 TTL；否则 `rawProbe()` 直接
      `bridge.requestString("alipay.antforest.forest.h5.queryHomePage", args)` 绕过门控
- [x] **4** 离线期间不再 60 秒重试：只提示一次「N 个蹲点任务暂停等待」；
      新增 `onOfflineCleared()`，由 `setOffline(false)` 触发
- [x] **5** `daily_once.json`（flag / label / at，保留 7 天）+ `📌 每日一次已锁定：…` 日志行；
      `DailyOnceAuditTest` 5 项（含「同 flag 保留最早」「7 天裁剪」「GMT+8 日键」）
- [x] **6** 庄园任务成功分支补 `farm::task::limit::$bizKey`；绿色打卡逐项 `EcoLife::tick::$actionId`
- [x] **7** 今日完成页新增「每日一次」区块（列出当天锁定项与首次成功时间）
- [x] **8** 四处高频例行输出降级为 `Log.debug`
- [x] **9** 文档四件套：本计划、设计稿、`docs/daily-once-tasks.md`、`architecture.md` 新章节

**实机证据（小米 17，装机后首轮 2026-09-28 23:57–23:58）**：

```
📌 每日一次已锁定：庄园任务[秋叶月饼任务] | flag=farm::task::limit::HEART_DONATION_ADVANCED_FOOD_V2
📌 每日一次已锁定：庄园任务[完成1笔旧衣回收] | flag=farm::task::limit::LSHS_xiadan_202509
📌 每日一次已锁定：庄园任务[逛闪购外卖1元起吃] | flag=farm::task::limit::SHANGOU_xiadan
```

`config/2088412711917481/daily_once.json` 同步生成（451 字节，内容与日志一致）。

## 三、验收清单

- [x] 全量单测通过（`./gradlew :app:testDebugUnitTest`，含新增 8 项）
- [x] 台账落盘与日志一致（首轮实测）
- [ ] 进入离线时能看到 `⛔ 已进入离线`，恢复时能看到 `✅ 离线已解除（持续 N 分钟）`
- [ ] 离线期间不再出现「顺延 60 秒」刷屏（改造前 52 分钟约 1700 行）
- [ ] 次日核对「每日一次」区块：被锁定的任务当天都真的完成过
- [ ] record.log 单日体积较改造前明显下降

## 四、遗留与后续

- 「一天一次」第三批（A5/A6/A7、B2、C1、C3、D1、D3）**未落地**，口径待用户逐条确认，
  清单与理由见 [`../../daily-once-tasks.md`](../../daily-once-tasks.md) §5。
  其中 **C1 海洋 AI 摸鱼**（重复的是「未受理」）应做退避、**D1 IP 抽抽乐**（有「剩余次数」）不能锁定。
- `checkInterval` 目前 50 分钟，一轮本身耗时约 540 秒；是否调短待定（受轮次时长约束，
  且请求量与风控暴露面会同步上升）。
- 小米 17 每轮主任务耗时逼近 10 分钟超时（当天 1 次 `timedOut`），是否专项优化耗时大户待评估。

## 五、来源说明

- 问题定位基于 2026-09-28 小米 17 的 57 个日志文件（47MB）与 `status.json` / `statistics.json` / `config_v2.json`；
  原始复盘存于 `.workbuddy/debug-logs/小米17_0928_日志分析.md`（不进仓库）。
- 日志降噪的构成统计同源：record.log 当天 3.7MB / 3.5 万行的 Top 消息分布。
