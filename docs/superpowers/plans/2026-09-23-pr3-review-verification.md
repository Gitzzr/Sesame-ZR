# PR#3 评审报告逐条核对与修复方案

> 核对基准：`fix/verification-pause-stuck`（HEAD `0b0fbee`）vs `origin/main`（`9213348`）
> 核对方式：逐条回源码、回 git 历史验证，不采信报告结论本身
> 报告原文：WorkBuddy 资料库 `pr-3-verification-pause-stuck`

## 一、核对结论总览

| 章节 | 条目 | 评审结论 | 核对结论 | 严重度 |
| --- | --- | --- | --- | --- |
| 第 1 节 | 无影响变更 1–8 | 均无影响 | **全部成立** | — |
| 第 2 节 | A 广播导出级别副作用 | 建议后续收口 | **事实成立**；但建议的收口手段**不可落地** | 低 |
| 第 2 节 | B skip 路径校验放宽 | 有意设计、低危 | **成立** | 低 |
| 第 2 节 | C `runBlocking` 是否致 ANR | 可接受 | **成立且非问题**（已确认后台派发器） | 无 |
| 第 2 节 | D plan 未回应规则 3 | 建议补决策段 | **成立** | 文档 |
| 第 3 节 | 1 持久化暂停标志与规则 3 冲突 | 高 / 确定 / 请勿合入 | **冲突成立**，但来源与影响范围需两处修正 | 建议**高 → 中** |

**结论**：评审报告的事实描述经核对基本准确，对第 1、2 节的判断可以直接采信。需要修正的是第 3 节的**归因**与**影响链表述**（见第三节），以及 A 项给出的**修复手段**（见第四节方案 2）。

## 二、逐条核对明细

### 2.1 第 1 节「无影响变更」8 项 —— 全部成立

| # | 位置 | 核对方式 | 核对结果 |
| --- | --- | --- | --- |
| 1 | `AntFarmDonationResponse.requiresVerification` | `git diff main...HEAD` | 确认由「认 `RPC_VERIFICATION_REQUIRED` / `resultCode=1009` / `error=1009`」收紧为只认 `RPC_VERIFICATION_REQUIRED`；3 处调用方（`AntFarm.kt:1757/1776/1823`）原 `return false` 后落到 `ResChecker.checkRes` 失败分支，`Log.record(failureMessage(jo))` 兜底，**行为等价不丢功能** |
| 2 | `OldRpcBridge` / `NewRpcBridge` | 全仓库 grep 调用点 | `handleVerificationRequired` 签名由 `(method)` 扩为 `(method, errorCode, errorMessage)`，调用点仅 `NewRpcBridge.java:301`、`OldRpcBridge.java:152`，均已同步。`isVerificationRequired(errorCode, errorMessage)` **签名未变**（main 亦为 2 参），`EnergyRainCoroutine.kt:45` 不受编译影响 |
| 3 | `VerificationPausePolicy`（新增） | 读源码 + 读测试 | 无 Android 依赖，纯逻辑；`requiresVerification` / `entryHintFor` / `isMarkExpired` 三入口；硬规则 5 合规 |
| 4 | `RequestManager` 清除路径 | 读源码 | `markVerification` / `clearVerification` / `readHint` 三键（`<uid>` / `<uid>_at` / `<uid>_hint`）成对管理；`resumePausedTasks` 在账户一致的前提下**任何校验分支都不再早退不清标志**，确认修掉旧实现的核心缺陷 |
| 5 | `onRpcBridgeReady` | 读源码 + 追调用点 | 新增 TTL 校验分支；全仓库唯一调用点 `ApplicationHook.kt:643`，且其上一行 `ApplicationHook.kt:642` 已先把 `offline = false` 复位（见 2.4 核查） |
| 6 | `notificationText` / `dialogText` | 读源码 | 纯文案，点明触发入口；无功能风险 |
| 7 | `Notify.sendNewNotification` | 读源码 | `@JvmOverloads` + `resumeAction` / `skipAction` 默认 `null`，旧调用点不受影响 |
| 8 | 测试 3 文件 | 读测试源码 | `VerificationPausePolicyTest` 8 用例（三态 / 入口映射 / TTL / 旧版无时间戳 / 09-21 实测样本）；`RequestManagerTest` 新增「业务拒绝码不再单独触发验证暂停」；`AntFarmDonationResponseTest` 断言翻转 —— **与修复点对齐** |

### 2.2 第 2 节 A/B/C/D

**A（`ApplicationHook` receiver 导出级别）—— 事实成立**

`ApplicationHook.kt:835-847`：API 33+ 用 `Context.RECEIVER_EXPORTED`，`< 33` 用 `ContextCompat.RECEIVER_EXPORTED`，**两个分支均为导出**。相对 main 的 diff 确认 `< 33` 分支由 `RECEIVER_NOT_EXPORTED` 改为 `RECEIVER_EXPORTED`。

影响范围经核对**需作两点澄清**：

1. **这不是新增了一类暴露面，而是把旧分支对齐到 33+ 的既有行为。** API 33+ 一直是 `RECEIVER_EXPORTED`，因此 `RESTART` / `MANUAL_TASK` / `RPC_TEST` / `STATUS` 在 Android 13+ 上本来就对任意 App 开放。本次改动的风险增量**仅限 Android 8.0–12L**。
2. **只有 `RESUME_VERIFIED` / `SKIP_VERIFICATION` 具备实际后果。** 经全仓库 grep 确认发送方：
   - `RESTART`：`SettingActivity.java:235`、`WebSettingsActivity.java:501`、`PortUtil.java:52/75`
   - `MANUAL_TASK`：`ManualTaskActivity.kt:54`
   - `RESUME_VERIFIED` / `SKIP_VERIFICATION`：**仅**由宿主进程内发出（`RequestManager` 的通知 `PendingIntent` 与宿主对话框），模块 App 侧无发送方

   因此真正需要收口的是后两个动作，但它们恰好只来自宿主自身 —— 这一点直接决定了方案 2 的可行性。

**B（skip 路径 uid/token 放宽）—— 成立**

`RequestManager.kt:220` `if (intentUid != null && intentUid != uid) return false`：不带 `userId` 的广播被放行。`RequestManager.kt:148` 的 `skipIntent` 用 `token.orEmpty()`，配合 `forceResumeAfterVerification` 的 `requireToken = false`，构成「无凭证即可恢复」。叠加 A 项后，外部 App 可发一帧无 `userId`、无令牌的 `SKIP_VERIFICATION` 强制恢复。后果仅限「恢复后服务端会重新拦截」，**低危**，与评审判断一致。

**C（`runBlocking`）—— 成立且非问题**

`GlobalThreadPools.kt:63`：`computeDispatcher = Dispatchers.Default.limitedParallelism(COMPUTE_PARALLELISM)`，为后台派发器；广播处理走 `execute { ... }`（`ApplicationHook.kt:363`）落在此派发器上，非主线程，**不致 ANR**。且 `resumePausedTasks` 带 `@Synchronized`，串行执行。评审判断正确。

**D（plan 未回应规则 3）—— 成立**

`docs/superpowers/plans/2026-09-21-verification-pause-stuck.md` 第 289 行只论证了对**硬规则 2**（不绕过验证）的合规性，第 290 行说明令牌精度下调，**全文未出现对硬规则 3 的回应**。评审判断正确。

### 2.3 第 3 节 问题 1（持久化暂停标志与规则 3 冲突）—— 冲突成立，归因需修正

冲突本身**成立**且无争议：`RequestManager.markVerification`（`:116-122`）把 `<uid>` / `<uid>_at` / `<uid>_hint` 三键写入 `shared_prefs`（`VERIFICATION_PREFS = "sesame_rpc_verification"`），`onRpcBridgeReady`（`:330-346`）在进程重启后读回并重建暂停 —— 确实构成「跨调用、跨进程重启存活」的持久化标记，与 `AGENTS.md:18` 规则 3 的字面要求直接冲突。

但**来源**与**影响链表述**需要修正，见第三节。

### 2.4 核查过但不成立的怀疑点（备查）

为确认评审是否有遗漏，另核查了三个可疑点，**均不成立**：

| 怀疑点 | 核查结论 |
| --- | --- |
| `onRpcBridgeReady` 过期分支只清标志、不复位 `offline`，可能残留离线态 | **不成立**。调用方 `ApplicationHook.kt:642` 在调用前已执行 `offline = false`，且该方法是全仓库唯一调用点（模块初始化路径，每进程一次） |
| `handleVerificationRequired` 在 `recoveryPolicy.onVerificationRequired()` 返回 `NONE` 时会早退，但此前已 `setOffline(true)`，可能留下无标志的悬挂离线态 | **不成立**。`RpcRecoveryPolicy.onVerificationRequired()` 无论返回值如何都会 `reason.set(VERIFICATION)`，拦截照常生效；且首次调用已完成写标志与通知，此处早退只是避免重复通知，属正确设计 |
| 通知与对话框的 `PendingIntent` 共用固定 request code，可能相互覆盖 | **不成立**。`:41-42` 定义了 `REQUEST_CODE_RESUME = 109` / `REQUEST_CODE_SKIP = 110`，两者分属不同动作，不冲突 |

## 三、对评审结论的修正

### 修正 1：持久化标记不是本 PR 引入的

报告表述「本 PR **保留**了写进 `shared_prefs` 的跨调用持久化标志」——「保留」二字准确，未宣称本 PR 引入。但结合其「请勿直接合入」的结论，容易被读成「本 PR 是问题来源」。git 历史给出了明确答案：

| 时间 | 提交 | 事实 |
| --- | --- | --- |
| 2026-08-01 | `9634797d` 记录能量雨安全验证重试设计 | 规则 3 的来源文档落地 |
| **2026-09-05** | **`2133ee86` 修复开发分支安全验证暂停及会员游戏每日去重** | **新增 `VERIFICATION_PREFS`，把暂停从内存态改为落盘态 —— 违反规则 3 的是这一次改动** |
| 2026-09-22 | 本 PR | 保留落盘，但补 TTL 上界、启动自愈、自救入口 |

`2133ee86` 之前，`RequestManager` 的暂停**纯内存**（仅 `recoveryPolicy.blockReason == VERIFICATION` + `setOffline(true)`），进程重启即清，**完全符合规则 3**。也就是说：**规则 3 曾被满足，2026-09-05 被主动放弃，而 main 至今如此。**

这带出一个比「本 PR 是否合规」更重要的事实：**规则 3 与主干代码已经不一致近三周，且无人察觉**。原因是这条约束只以自然语言存在、没有任何可执行检查。据此，本次应当解决的是「规则与代码长期背离」这个根因，而不是让本 PR 单方面回退。

### 修正 2：2026-08-01 spec 的适用范围被扩大引用

报告将 `2026-08-01-energy-rain-verification-retry-design.md` 与规则 3 并列，作为「同样禁止」的第二重依据。核对原文后需要收窄：

- 该 spec 的目标行确实写着「不再保存跨调用、跨当天的安全验证暂停状态」，**但**其实现章节明确限定为「删除**能量雨专用**的当天安全验证标记及所有读写」，全文未涉及 RPC 层的全局熔断。
- 该 spec 处理的是**业务功能级的「当天标记」**（能量雨当次不重试），与 **RPC 链路级的全局熔断**是两种机制、两种目的。

因此：冲突的有效依据**只有 `AGENTS.md:18` 规则 3 本身**。这不削弱结论（规则 3 的措辞确实是全局的），但会改变处理方式 —— 正确的解法是**给规则 3 划定范围**，而不是让 RPC 层去迁就一条本为业务级标记写的规范。

### 修正 3：严重度建议由「高」下调为「中」

报告的影响链表述为「标志落盘 → 进程重启仍命中 → 即便 TTL 到期自愈，期间所有 RPC 被本地熔断 → 与规则 3 语义不一致」。其中「期间所有 RPC 被本地熔断」需要区分：

- **这恰恰是熔断的设计目标**，不是缺陷 —— 在风控未解除时继续冲击服务端才是要避免的（第八节结论 3 已实测确认暂停期间不发请求）。
- 真正的合规风险是**「跨调用」中的「用户已完成验证但模块仍拒绝发起请求」**这一段，而这正是本 PR 用 TTL + 启动自愈 + 两处自救入口在收敛的。

结合有界（30 分钟）、可自愈、有用户可见解除入口、服务端仍为最终裁决者、不产生数据丢失与安全问题，**建议严重度定为中**。真正意义上的「高」应保留给旧版那种**无界**卡死（用户只能卸载支付宝）—— 那是本 PR 修掉的问题。

### 修正 4：A 项建议的收口手段不可落地

报告中「建议后续用「自定义签名级 permission」收口，而非裸 `EXPORTED`」在此项目结构下**无法实现**，原因是：

- 接收器是**运行期注册在宿主（支付宝）进程内**的，其可用的 `broadcastPermission` 必须能被**发送方**满足；
- 本场景的发送方有两类且分属**不同 uid**：模块 App（`fansirsqi.xposed.sesame` 进程）与宿主自身（对话框 `activity.sendBroadcast`、通知 `PendingIntent` 均由支付宝 uid 发出）；
- 任何由模块 App 定义的签名级 permission，**支付宝自身不持有** → 会把自己发出的恢复指令也拦掉；
- 而给宿主 manifest 新增权限又不可能（APK 已由原厂签名，patch 只改 code，不加权限声明）。

可落地的替代方案见第四节方案 2。

## 四、修复方案

### 方案 1（阻塞项）：硬规则 3 的边界 —— 建议走路线 A，并改写规则

**建议：采用路线 A（修订规则），但不是无条件放宽，而是把「一刀切禁令」改成「分层 + 可执行约束」。**

理由（按权重排序）：

1. **规则 3 的原始适用范围本就窄于字面**：它源自能量雨 spec，而该 spec 只管业务级当天标记（修正 2）。字面的全局禁令在写下时就没有真正覆盖 RPC 层。
2. **规则与代码的背离已存在近三周且无人察觉**（修正 1）。一条长期与实际不符、且无可执行检查的规则，已不再是约束。继续保留只会让后续每个触及该区域的 PR 都「不合规」。
3. **路线 B 的真实代价被低估**：回到内存态后进程重启即遗忘暂停。MIUI 的后台清理十分常见，用户完成验证前若宿主被重启，模块会立刻重新发起请求 —— 这等于放弃 `2133ee86` 想达成的目标，而那个目标（让暂停跨重启存活）本身是合理的产品判断。
4. **规则 3 真正要防的三件事，可以用约束表达得更精确**：不要让暂停**无界**、不要让用户**无从得知入口**、不要让用户**无从解除**。这三条恰好是本 PR 补上的。

**修改位置与内容：**

**（1）`AGENTS.md:18`** —— 将规则 3 替换为分层表述：

```markdown
3. **安全验证响应只能结束「当前这一次调用流程」，不得据此判定验证通过。** 分两层：
   - **业务功能级**：单个业务不得写入「跨天」的暂停标记（即「该功能今天不再尝试」）。安全验证响应只结束本次流程，
     后续定时或手动调用应能重新查询。参见 2026-08-01 能量雨 spec。
   - **RPC 链路级**：允许写入**有界**的本地熔断暂停标志，但必须同时满足四条硬约束：
     (a) 有 TTL 上界（≤30 分钟）；(b) 启动时必须自愈，无时间戳或已超时的标志一律清除；
     (c) 必须提供用户可见的解除入口（通知 / 对话框）；(d) 到期后必须重新发起请求，不得缓存「已验证」结论。
   任何版本都不得出现「用户无从解除、只能卸载宿主应用」的状态。
```

**（2）`docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md`** —— 在「目标」章节补一句范围说明，避免后续被扩大引用：

```markdown
> 适用范围：本条只约束**业务功能级的「当天标记」**（能量雨当次不重试）。
> RPC 链路级的全局熔断暂停由 `AGENTS.md` 规则 3 单独约束，不在本设计范围内。
```

**（3）`docs/superpowers/plans/2026-09-21-verification-pause-stuck.md`** —— 在 §7「与计划的差异」后新增一节「与硬规则 3 的关系」（同时闭合评审 #D）。

**验证方式：**
- 文档一致性：三处表述互不矛盾，且规则 3 的每条约束都能在 `RequestManager.kt` 中找到对应实现（TTL → `:35`；自愈 → `:336-340`；解除入口 → `:151-194`；到期重查 → `:336` 后 `reset()` + 不缓存结论）。
- 回归：`./gradlew :app:testDebugUnitTest` 保持通过（本方案只改文档，不改代码）。

### 方案 2：A/B 广播按信任边界拆分接收器

> ⚠️ **本节方案随后被修正。** 原提议「自救接收器一律使用 `NOT_EXPORTED`」在 Android 8.0–12L 上会直接抛异常，
> 实际实现改为「13+ 用 `NOT_EXPORTED`、12L 及以下导出 + 令牌校验」。原因与证据见第六节 6.1。

评审建议的签名级 permission 不可落地（修正 4）。**可落地且更彻底的替代方案是按发送方信任边界拆成两个接收器**：

| 接收器 | 导出级别 | 承载动作 | 发送方 |
| --- | --- | --- | --- |
| 命令接收器 | `EXPORTED` | `RESTART` / `RE_LOGIN` / `MANUAL_TASK` / `RPC_TEST` / `STATUS` | 模块 App 自身进程（跨进程必需） |
| 自救接收器 | `NOT_EXPORTED` | `RESUME_VERIFIED` / `SKIP_VERIFICATION` | **仅宿主自身**（通知 `PendingIntent` + 宿主对话框） |

**修改位置：**
- `ApplicationHook.kt:821-853`（`registerBroadcastReceiver`）：构造两个 `IntentFilter`、注册两个 receiver 实例，分别按上表设置导出级别；`unregisterBroadcastReceiver`（`:855-865`）同步反注册两个。
- `ApplicationHook.kt:340-370`（`when` 分支）：动作分发按接收器归属拆到两个 `onReceive` 实现中（或共用一个处理函数、仅在注册处区分）。

**为什么可行**：`RESUME_VERIFIED` / `SKIP_VERIFICATION` 的发送方经全仓库 grep 确认**只有宿主自身**（见 2.2-A 第 2 点），同 uid 发送不受 `NOT_EXPORTED` 限制，因此拆出后自救链路完全不受影响，而外部 App 的伪造广播被系统直接拦掉。

**补充加固（可选，与上面正交）**：让 skip 路径也校验令牌 —— `RequestManager.kt:203-204` 改为 `requireToken = true`。由于 skip 的**唯一合法发送方**（通知与对话框）均通过 `skipIntent()`（`:143-149`）携带令牌，收紧不损失任何自救能力；而外部 App 拿不到令牌（它是写入宿主私有 `shared_prefs` 的随机 UUID）。需要同步更新 `:201` 的 KDoc 与 plan §7 第 290 行对「令牌精度下调」的说明。

**验证方式：**
- 模块 App 指令可达：`adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.restart`（应生效）
- 外部 App 伪造被拦：用任意第三方 App（或 `adb shell am broadcast` 以 `--user 0` 之外的 uid）发送 `...skipVerification`，**不应**解除暂停
- 自救链路不受影响：通知「跳过并恢复」、对话框「跳过并恢复」「已验证，恢复任务」三处点击均应生效
- **需在 Android 8.0–12L 实机复测**：该分支的 `ContextCompat.RECEIVER_NOT_EXPORTED` 依赖 `android.permission.RECEIVER_NOT_EXPORTED` 的同 uid 放行语义，是本次改动的风险集中区

### 方案 3（对应 D）：plan 补决策段

**修改位置**：`docs/superpowers/plans/2026-09-21-verification-pause-stuck.md`，在 §7「与计划的差异」之后。

**内容**：说明为何保留持久化（跨重启存活是 09-05 的既有产品判断）、与规则 3 的边界如何划定、以及本次收敛了三件事（TTL 上界 / 启动自愈 / 两处自救入口）。与方案 1 的（3）为同一处改动，可合并执行。

### 方案 4：TTL 到期分支的测试闭环

评审的 Todo 指出 plan 自承「30 分钟真实到期分支未实机验证」，建议补一条测试。核对后需要说明**残余风险实际很低**，并给出闭环路径：

- `VerificationPausePolicyTest` 已覆盖 `isMarkExpired` 的三种输入（TTL 内 `false`、TTL 外 `true`、无时间戳/负值 `true`）；
- `onRpcBridgeReady` 的「已过期」与「旧版本无时间戳」两个分支**共用同一段清除代码**（`:336-340`），其中「旧版本无时间戳」分支**已在 2026-09-22 真机验证通过**（日志命中「安全验证暂停标志已超时或来自旧版本，自动解除」）；
- 因此两者之差仅在 `isMarkExpired` 的返回值，而该函数已有单测。

**若要彻底闭环**，推荐做法（符合硬规则 5）：把 `onRpcBridgeReady` 的「读标志 → 判过期 → 决策（清除 / 重建暂停 / 无需动作）」抽成策略函数（例如并入 `VerificationPausePolicy`），`RequestManager` 只保留 prefs IO 与副作用调用。这样该分支可被纯 JVM 单测覆盖，无需 Android 环境。

**修改位置**：`VerificationPausePolicy.kt`（新增决策函数）+ `RequestManager.kt:330-346`（改为调用策略）+ `VerificationPausePolicyTest.kt`（补用例）。

**验证方式**：`./gradlew :app:testDebugUnitTest --tests "*VerificationPausePolicyTest*"`。

> 说明：由于宿主私有 `shared_prefs` 无 root 不可写，**无法**用 adb 直接伪造过期时间戳做端到端验证；策略抽取是唯一可自动化的闭环路径。

## 五、验证方式汇总

| 方案 | 类型 | 命令 / 操作 |
| --- | --- | --- |
| 1 规则改写 | 文档一致性 | 人工比对三处表述；`./gradlew :app:testDebugUnitTest` 保持通过 |
| 2 接收器拆分 | 单测 + 实机 | `adb shell am broadcast` 正反用例；Android 8–12L 实机复测自救三入口 |
| 3 plan 决策段 | 文档 | 人工评审 |
| 4 策略抽取 | 单测 | `./gradlew :app:testDebugUnitTest --tests "*VerificationPausePolicyTest*"` |

## 六、实施记录（2026-09-23）

### 6.1 对方案 2 的修正：原方案在 Android 8.0–12L 上不可用

原方案 2 提议「自救接收器统一改为 `NOT_EXPORTED`」。动手前反汇编了 `androidx.core:core:1.17.0`
的 `ContextCompat` 以确认该标志在低版本的真实语义，结论**推翻了原方案**：

```
ContextCompat.Api26Impl.registerReceiver(...):
  if (flags & RECEIVER_NOT_EXPORTED) {
      permission = ContextCompat.obtainAndCheckReceiverPermission(context)
      context.registerReceiver(receiver, filter, permission, handler)
  }

obtainAndCheckReceiverPermission(context):
  permission = context.getApplicationContext().getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
  if (checkSelfPermission(permission) != GRANTED) throw new RuntimeException("Permission ... is not granted")
```

即：API < 33 上 `RECEIVER_NOT_EXPORTED` 依赖「本应用 manifest 声明
`<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`」。而本项目注册接收器用的是**宿主（支付宝）
的 context**，需要的是 `com.eg.android.AlipayGphone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；
宿主 APK 已原样签名、无法新增权限声明 → **直接抛异常**。

**由此同时修正评审报告 A 项的事实描述**：原实现（`RECEIVER_NOT_EXPORTED`）在 Android 8.0–12L 上
不是「指令收不到」，而是 `registerBroadcastReceiver` 抛异常被 catch 吞掉、**接收器根本没注册成功**，
所有广播（含自救）全部失效。PR 改回导出，同时也修掉了这个彻底失效。

### 6.2 实际实现

| 项 | 实现 | 位置 |
| --- | --- | --- |
| 接收器按信任边界拆分 | 命令通道：`RESTART` / `STATUS` / `RPC_TEST` / `MANUAL_TASK`；自救通道：`RESUME_VERIFIED` / `SKIP_VERIFICATION` / `RE_LOGIN` | `ApplicationHook.kt`：`registerBroadcastReceiver` / `registerDynamicReceiver` |
| 自救通道导出级别 | Android 13+ 用原生 `RECEIVER_NOT_EXPORTED`；12L 及以下无法使用（见 6.1），退化为导出并由令牌兜底 | 同上 |
| 令牌门槛 | 只要宿主已落盘令牌，任何恢复入口都必须出示它；完全无令牌时仍放行，保留自救能力 | `RequestManager.resumePausedTasks` |
| 恢复决策抽策略 | `restoreActionFor(hasMark, markedAt, now)` → `NONE` / `CLEAR_MARK` / `RESTORE_PAUSE` | `VerificationPausePolicy.kt`、`RequestManager.onRpcBridgeReady` |
| 规则 3 改写 | 分层：业务功能级禁止跨天标记；RPC 链路级允许有界熔断 + 四条硬约束 | `AGENTS.md` |
| spec 范围限定 | 能量雨 spec 补「适用范围」说明，避免被扩大引用 | `docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md` |
| plan 补决策段 | 新增「与硬规则 3 的关系」，含时间线证据 | `docs/superpowers/plans/2026-09-21-verification-pause-stuck.md` |

**未采用**：评审建议的「自定义签名级 permission」。原因见第三节修正 4 —— 接收器注册在宿主进程内，
宿主自身（对话框 / 通知 `PendingIntent`）用的是支付宝 uid，不持有模块定义的签名权限，会被一并拦掉。

### 6.3 验证结果

| 项 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（含新增 `restoreActionFor` 用例） |
| `./gradlew :app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| 新增单测 | `启动时按标志状态决定清除标志还是重建暂停`、`旧版本遗留的无时间戳标志在启动时被清除` |

### 6.4 仍需实机验证（两个 Android 版本区间的行为不同）

- **Android 13+**：外部 App 伪造 `SKIP_VERIFICATION` 应被系统直接丢弃；通知与对话框三处入口应正常。
- **Android 8.0–12L**：命令通道（模块 App 的「手动任务 / 重启」）应恢复可用；伪造的恢复广播应被令牌校验拒绝。

## 七、实机测试中发现的额外缺陷（2026-09-23，Android 10 设备）

为验证「Android 8.0–12L」区间，接入了一台已 root 的 MI 9（Android 10 / API 29）。环境探测中发现了一个**比导出级别更严重**的缺陷。

### 7.1 缺陷：正式包里广播接收器从未注册

`ApplicationHook.initHandler()` 中，`registerBroadcastReceiver` 的**唯一调用点**被包在调试分支内：

```kotlin
// 调试模式初始化
if (BuildConfig.DEBUG) {
    try {
        startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", processName, General.PACKAGE_NAME)
        registerBroadcastReceiver(appContext!!)   // ← 唯一调用点
    } catch (_: Throwable) { /* ignore */ }
}
```

而 `unregisterBroadcastReceiver`（在 `stopHandler` 内）**没有**被门控 —— 注册被门控、注销没被门控，
这个不对称说明它是疏漏而非有意设计。

三重证据：

| 证据 | 结论 |
| --- | --- |
| `AndroidManifest.xml` 中没有任何 `<receiver>` 声明 | 没有声明式兜底 |
| `release` 构建 `isDebuggable = false`、`isMinifyEnabled = true` | `BuildConfig.DEBUG` 为 false，且 R8 会裁掉不可达代码 |
| `.github/workflows/android.yml` 发布用 `./gradlew assembleRelease` | 用户实际安装的正是这个被裁剪过的包 |

**后果**：正式包里接收器不存在 → 「重启」「手动任务」「已验证，恢复任务」「跳过并恢复」全部失效。
其中**「跳过并恢复」正是本 PR 新增的自救入口**，即该功能在正式发布的包中从未生效。

### 7.2 这澄清了「指令收不到」的真正根因

PR 原先把这个现象归因于 `RECEIVER_NOT_EXPORTED` 的导出级别问题。实测澄清这是**两个独立缺陷**：

| 构建类型 | 现象 | 根因 |
| --- | --- | --- |
| 发行版（release） | 接收器压根没注册，**与 Android 版本无关** | 注册调用被 `BuildConfig.DEBUG` 门控 + R8 裁剪 |
| 调试版（debug） | 接收器会注册，但 Android 8.0–12L 上抛异常 | `ContextCompat.RECEIVER_NOT_EXPORTED` 需要宿主无法声明的权限 |

两者都要修，改导出级别并不能解决发行版的问题。

### 7.3 修复

`ApplicationHook.initHandler()`：把注册移出调试分支改为无条件执行，调试分支只保留仅供本地调试的 HTTP 服务。

### 7.4 验证：release 产物的静态证据

对 `assembleRelease` 产物（R8 已裁剪）检查 dex 字符串：

| 检查项 | 结果 | 含义 |
| --- | --- | --- |
| 调试服务密码 `ET3vB^#td87sQqKaY*eMUJXP` | **缺失** | 证实 R8 确实把 `BuildConfig.DEBUG` 分支整段裁掉了 |
| `BroadcastReceiver registered` | **存在** | 接收器注册已进入正式包 |
| `registerDynamicReceiver` / `mRescueReceiver` / `恢复指令令牌不匹配` | **存在** | 本次拆分与令牌门槛均在正式包中 |

由于旧实现里 `registerBroadcastReceiver` 的唯一调用点不可达，R8 必然把该方法一并剥离 ——
「调试密码缺失 + 注册日志存在」这一对结果即构成修复前后的对照证据。
另：`./gradlew :app:assembleRelease :app:testDebugUnitTest` 均 BUILD SUCCESSFUL。

### 7.5 该设备的框架情况（供后续测试参考）

| 项 | 现状 | 结论 |
| --- | --- | --- |
| 注入框架 | **EdXposed v0.4.6.2 (Riru)**，实测已注入支付宝 | 只实现**旧版 Xposed API** |
| 已装同类模块 | `leo.xposed.sesameX`（芝麻糊） | 走 `de.robv.android.xposed.*`，故能被 EdXposed 加载；实测 `RpcBridge` 为 null 抛 NPE（设备上是 2020 年的支付宝 10.5.66，过旧） |
| 本模块要求 | `META-INF/xposed/module.prop`：`minApiVersion=101`、`targetApiVersion=102` | **EdXposed 无法加载本模块**，须先换框架 |
| Magisk / Riru | 20.4 / v21.3 | LSPosed 的 Riru 变体要求 Riru 26.1.7+ |
| libxposed API 支持 | LSPosed **v1.9.2**（最后一个带 Riru 变体的发行版）**只到 API 100**；API 101 需 **v2.0.0+**（已移除 Riru），API 102 需 **v2.1.0+** | 只能走 **Zygisk** 路线 → 需 **Magisk ≥24** |

**结论**：该设备要用于本模块实测，有两条路 ——

1. **换框架（不碰支付宝）**：Magisk 升到 ≥24 并启用 Zygisk → 装 LSPosed v2.1.0+（Zygisk）或 Vector v2.2+，卸掉 EdXposed + Riru。这是与 EdXposed 同类的「框架层注入」方式，不改 APK、不改签名。
2. **用现成 LSPatch 打补丁**：设备上已装的 LSPatch 1.2 其 core `canary-3106` 已是 **API 102**，无需升 Magisk；但需给支付宝打补丁（改签名），可用 root 先备份数据、装完再回灌。

> 注意路径 2 的额外风险：设备上支付宝为 10.5.66（2020），已实测同类模块在其上 `RpcBridge` 为 null ——
> 业务 hook 大概率无法工作。但**接收器注册**与业务 hook 无关，仍可用于验证本节修复。
