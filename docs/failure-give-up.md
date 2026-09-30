# 重复失败放弃（当天失败到上限就停止尝试）

面向**维护者与使用者**的机制说明与**限制清单**。
最近更新：**2026-10-01**（首次落地）。

相关代码：`model/RepeatFailurePolicy.kt`（判定，纯逻辑）、`util/RepeatFailureGuard.kt`（接线与播报）、
`data/Status.kt` 的 `failureCountTodayList`（当天计数）。契约测试：`RepeatFailurePolicyTest`。

---

## 1. 这份机制解决什么

真机日志显示，有一批调用**每轮都失败、当天从未成功**——主任务轮询一天跑几十轮，它们就失败几十次：

| 目标 | 当天失败次数 | 服务端回应（原文） |
| --- | --- | --- |
| 信用2101 账户查询 | 39 | `返回为空或非 SUCCESS` |
| 运动竞猜 participate | 39 | `{"error":3000,"errorMessage":"系统出错，正在排查"}` |
| 会员宝箱 | 39 | `resultCode=PARAM_ILLEGAL` / `resultDesc=系统繁忙，请稍后重试` |
| 芝麻炼金 joinActivity | 32 | `生活记录模板不存在` |
| 学生签到 queryCheckInModel | 25 | Check failed |

**量化收益（红米 K50，2026-09-30）**：当日 `error.log` 共 479 条，
其中 **89%（428 条）来自 18 个重复模板**；接入本机制的目标合计约 210 条/天。
按上限 5 次收敛后，这些目标合计降到约 25 条/天。

---

## 2. 机制

```
调用前： if (RepeatFailureGuard.shouldSkipToday(KEY)) return      // 今天已放弃 → 静默跳过
调用后失败： RepeatFailureGuard.recordFailure(KEY, "给用户看的名字", 回应文本…)
```

- **计数**：存在 `Status.failureCountTodayList`（`<key> → 当天失败次数`），**跨天随 `Status.unload()` 清零**；
  落盘在 `config/<uid>/status.json`，进程重启不丢。
- **阈值**：`RepeatFailurePolicy.DAILY_FAILURE_LIMIT = 5`。达到 5 次后该 key 当天不再被调用。
- **播报**：只在**恰好达到上限那一次**写一行 `Log.record`，并写一条每日台账
  `失败放弃：<名字>[今日失败5次已停止尝试·<类别>]`。之后各轮静默跳过（不重复刷日志）。
- **次日核对**：台账与「今日完成」页同源（`DailyOnceAudit`，保留 7 天、跨日不清），
  所以第二天能看到"昨天谁被放弃了、为什么"。

---

## 3. ⚠️ 限制清单（使用/评估本机制前必读）

### L1 阈值对两类失败是**同一个值**：不可用类也是"当天放弃"，不是退避

机制把失败归类为 `DETERMINISTIC`（确定性）与 `TRANSIENT`（服务端不可用），
但**两者都在 5 次后停止尝试**——类别目前**只影响日志/台账的措辞**，不影响阈值。

- **代价**：若某接口只是**当天某个时段**故障（例如维护两小时），5 次失败多半发生在故障期，
  之后即使服务恢复也不会再尝试，**该目标的当天收益就丢了**。
- **为什么不区分阈值**：客户端**无法可靠区分**"临时故障"与"长期不可用"。
  实测反例：运动竞猜的 `error 3000 系统出错` 看着像临时故障，实际全天 39/39 全失败。
  分开阈值只会制造"更聪明"的假象，却把误判风险换成了少拿收益。

### L2 计数是**当天累计**，不是"连续失败"

白天失败 4 次、晚上再失败 1 次，同样会触发放弃。
若某目标天然会间歇性失败（例如只在特定时段可做），不要接入本机制。

### L3 类别判定是**关键词匹配**，不理解业务语义

`RepeatFailurePolicy.kindOf(...)` 用小写化的子串表匹配；服务端改文案会退化成 `UNKNOWN`。
`UNKNOWN` 的处理是**继续尝试**（宁可多试，不误放弃）——这是刻意的安全方向。
新增判据必须同时更新本文 §4 的表与 `RepeatFailurePolicyTest`。

### L4 key 由调用方手工指定；改 key 等于重置当天计数

计数按 key 存。重命名 key（或改了拼进 key 的变量，如芝麻炼金的 `templateId`）
会让当天计数重新从 0 开始 —— **不会造成错误，只是多试几次**。
反之，若把两个不同目标误用同一个 key，会互相累加导致**提前放弃**。

### L5 只适合"一天最多成功一次"的目标

本机制不理解"成功了几次、还该成功几次"。**一天本该成功多次**的目标（例如浇水、帮喂、收能量）
接入会直接少拿收益 —— 这类要靠名单次数上限（见 `interaction-limits.md`）而不是本机制。

### L6 不覆盖的失败类型（各有归属，别硬塞进来）

| 失败类型 | 归属机制 |
| --- | --- |
| 代码异常 / 崩溃 | `ExceptionMode.PROTECTIVE` + `HookInvocation` 的统一异常出口 |
| RPC 层连续失败（网络/超时） | RPC 有界熔断（`NewRpcBridge.maxErrorCount`） |
| 服务端 1009「业务拒绝」 | 验证暂停（`VerificationPausePolicy`）与 `ServerBusyPolicy` |
| 需要真实交互/无客户端通道的任务 | 见 `daily-once-tasks.md` §4.3（AI 摸鱼按天放弃 + 台账） |
| 达上限类（`QUOTA_*`、`已达上限`） | 各自任务的当天标志（如 `forest::VitalityExchangeLimit::*`） |
| 单次执行内的循环 | 无进展退出（`ChouChouLeLoopPolicy`、商家任务的轮次上限） |

### L7 与既有机制的边界

- 「一天一次锁定」管的是**成功**之后不再做；本机制管**失败**到上限不再试 —— 两者互不影响；
- 命中本机制时**只跳过该 key 对应的调用**，不影响同一任务里的其它动作；
- 与 `TaskBlacklist`（跨天黑名单）不同：本机制**每天自动恢复**，黑名单不会。

### L8 播报"只发一次"依赖计数落盘

`recordFailure` 在**恰好等于上限**时播报。若那一轮进程被杀、`status.json` 未落盘，
下次可能再次触发播报（多一条日志），概率极低且无副作用。

### L9 未在真机验证"跨天恢复"与"实际收益不受影响"

2026-10-01 凌晨落地时只做了单测与编译验证；**以下两项需要第二天用真机日志确认**：
1. 计数确实在跨天后清零、目标重新开始尝试；
2. 被放弃的目标当天确实没有可拿的收益（即没有"本可以成功却被放弃"的情况）。

### L10 上限 5 的取值依据来自单日样本

5 次 ≈ 39 轮/天的前 13%，足以把噪音压到可接受，同时保留"白天换个时段说不定能成"的机会。
样本只有一天的日志，**不是统计结论**；若要调整，请同步本文 §4 与测试里的常量断言。

---

## 4. 判定表（`RepeatFailurePolicy.kindOf`）

**优先匹配确定性**（同一回应同时命中两类时判为确定性）：

| 类别 | 判据（子串，大小写不敏感） |
| --- | --- |
| `DETERMINISTIC` 确定性失败 | `param_illegal`、`生活记录模板不存在`、`模板不存在`、`任务还没有完成`、`返回为空或非 success`、`不支持rpc完成`、`quota_user_not_enough`、`i07`、`i09` |
| `TRANSIENT` 服务端不可用 | `系统繁忙`、`繁忙`、`系统出错`、`"error":3000` / `"error": 3000`、`请稍后重试`、`限流`、`网络`、`超时`、`timed out` |
| `UNKNOWN` 未判定 | 以上都不命中（**按继续尝试处理**） |

---

## 5. 当前接入点

| key | 显示名 | 位置 | 2026-09-30 实测 |
| --- | --- | --- | --- |
| `credit2101::queryAccountAsset` | 信用2101 账户查询 | `task/other/credit2101/Credit2101.kt` `queryAccountAsset()` | 39 次/天 |
| `member::treasureBox` | 会员宝箱 | `task/antMember/AntMember.kt` `triggerMemberTreasureBox()` | 39 次/天 |
| `sports::walkParticipate` | 走路挑战赛竞猜 | `task/antSports/AntSports.kt` `participate()` | 39 次/天 |
| `sesame::join::<templateId>` | 芝麻炼金任务[标题] | `task/antMember/AntMember.kt` `processAlchemyTasks()` | 32 次/天（模板不存在那条） |
| `forest::studentCheckIn` | 青春特权签到查询 | `task/antForest/Privilege.kt` `processStudentSignIn()` | 25 次/天 |

**已知同类但未接入**（各有原因）：

- 「会员任务结算失败」44 条/天：混了"任务还没有完成"（可接入）与"作弊检查校验失败"
  （涉及风控特征，**刻意不自动重试，也不接入**）—— 待逐类确认后再动；
- 商家服务 `doMerchantMoreTask`：已有轮次上限 + 只认服务端受理（PR #42）；
- 纯音/庄园抽抽乐：已有"无进展即退出"（`ChouChouLeLoopPolicy`）。

## 6. 新增一个接入点怎么做

1. 确认该目标**一天最多成功一次**（否则见 L5），并确认失败是"服务端回应"而非异常（否则见 L6）；
2. 选一个稳定 key：`模块::动作`（带变量的要保证同一逻辑目标稳定，见 L4）；
3. 调用前 `if (RepeatFailureGuard.shouldSkipToday(KEY)) return`（跳过要**静默**，不要打日志）；
4. 失败分支里 `RepeatFailureGuard.recordFailure(KEY, "给用户看的名字", 回应文本…)`
   —— 回应文本尽量传 `resultCode`/`resultDesc`/整段响应，便于归类；
5. 在 §5 表里登记这一行，并在 PR 描述里写清"为什么它能被放弃"。

## 7. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-10-01 | 首次落地：上限 5 次，接入 5 个目标；判定表见 §4；限制清单见 §3 |
