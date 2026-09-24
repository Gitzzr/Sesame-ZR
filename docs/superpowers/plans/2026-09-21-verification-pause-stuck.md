# 安全验证暂停「卡死」问题分析与修复方案

> 状态：分析完成，待实机日志确认 1009 语义后实施。
> 现象来源：用户实机反馈 —— 弹出「自动任务已暂停」后，支付宝不出现任何安全验证界面，用户无从操作，只能重装支付宝才能恢复。

## 一、现象与复现路径

1. 自动任务运行中，某次 RPC 返回被判定为「需要安全验证」。
2. `RequestManager.handleVerificationRequired()` 触发：置 `offline=true`、`stopAllTask()`、写持久化标志、发通知。
3. 用户按提示去支付宝找验证入口 —— 找不到（支付宝不弹任何验证）。
4. 用户点「已验证，恢复任务」无效，重启支付宝仍被重新暂停。
5. 唯一有效操作：卸载重装支付宝。

## 二、判定入口（代码事实）

`RequestManager.kt:49-55`

```kotlin
fun isVerificationRequired(errorCode: String?, errorMessage: String?): Boolean {
    val message = errorMessage.orEmpty()
    return errorCode == "1009" ||
        message.contains("为保障您的正常访问，请进行验证后继续") ||
        message.contains("为了保障您的操作安全，请进行验证后继续") ||
        message.contains("请进行验证后继续")
}
```

两个调用点：
- `NewRpcBridge.java:300-303`（新桥）：`errorCode = error`, `errorMessage = errorMessage`
- `OldRpcBridge.java:149-156`（旧桥）：`errorCode = error`, `errorMessage = errorMessage ?: memo`

## 三、根因分析

### 根因 1（直接诱因）：`1009` 是通用业务错误码，被误判为「需要安全验证」

| 证据 | 位置 | 含义 |
| --- | --- | --- |
| `if ("1009" == errorCode) { // 访问被拒绝` | `AntForest.kt:3083` | 1009 的业务语义是**访问被拒绝** |
| `errorMark = ["1004","1009","2000","46","48"]` | `NewRpcBridge.java:35-37` | 1009 同时被归入**通用错误码**列表（走网络错误分支） |
| `"1009".equals(code) \|\| "1009".equals(response.optString("error"))` | `AntFarmDonationResponse.java:16-17` | 庄园捐蛋处同样把 1009 当验证 |

结论：非好友、已领取、参数非法、重复操作等**普通业务拒绝**都会返回 1009。这类请求**根本不需要人工验证**，所以支付宝当然不会弹验证页 —— 用户「不知道该怎么操作」正是被这个误判制造出来的。

真正表示人工风控的强信号只有：`resultCode == "RPC_VERIFICATION_REQUIRED"`，或文案中含「请进行验证后继续」。

### 根因 2（致命，解释「只能重装支付宝」）：暂停标志持久化在支付宝私有目录，且无超时、无兜底清除

`RequestManager.kt:34-38`

```kotlin
private const val VERIFICATION_PREFS = "sesame_rpc_verification"
private fun verificationPrefs() = ApplicationHook.appContext
    ?.getSharedPreferences(VERIFICATION_PREFS, Context.MODE_PRIVATE)
```

`ApplicationHook.appContext` 来自 `Application.attach(Context)`（`ApplicationHook.kt:195`）——**是支付宝的 Context**。因此标志文件落在：

```
/data/data/com.eg.android.AlipayGphone/shared_prefs/sesame_rpc_verification.xml
```

链路：

1. `handleVerificationRequired()` 写入 `uid -> UUID`（`RequestManager.kt:64-66`）
2. `onRpcBridgeReady()`（每次支付宝进程启动、桥接初始化都会调用）**只要 prefs 里存在该 uid 就无条件重新暂停 + 重新弹窗**（`RequestManager.kt:202-212`）
3. 唯一删除该 key 的地方是 `resumeAfterManualVerification()` 第 114 行
4. 而第 **105 / 106 / 107-108 / 112-113 行的每一个早退分支都不清除该 key**：

```kotlin
val uid = UserMap.currentUid ?: return false                    // 105 —— 不清标志
val expected = verificationPrefs()?.getString(uid, null) ?: return false  // 106 —— 不清标志
if (userId != uid || token != expected) return false             // 108 —— 不清标志
if (currentUid != uid || !isVerificationPaused() || ...) return false     // 113 —— 不清标志
verificationPrefs()?.edit()?.remove(uid)?.commit()               // 114 —— 只有走到这里才清
```

**任意一条早退命中，状态即永久卡死**：之后每次打开支付宝都会被重新暂停。

> 卸载支付宝会连同 `shared_prefs/` 一起删除，等于把卡死的标志清空 —— 这就是「重装支付宝才能正常运行」的真实机制。重装并未修复问题，只是清掉了状态位。

### 根因 3：恢复链路把「一次性令牌」当作硬门槛，缺少兜底放行

`resumeAfterManualVerification()` 需同时满足 uid 匹配 + token 匹配 + 当前仍处于 VERIFICATION + token 未变。任一不满足即 `return false`，既不恢复也不清标志，且 `execHandler()` 不执行。

典型死锁：进程重启后桥接尚未就绪（`onRpcBridgeReady()` 还没跑），用户先点了通知按钮 → 第 112 行 `!isVerificationPaused()` 成立 → `return false` → 标志保留 → 随后 `onRpcBridgeReady()` 又把它重新暂停。**用户点多少次都没用。**

### 根因 4：模块只「暂停」，从不引导验证；并阻断了唯一能触发验证的请求

- `reOpenApp()` 在 `isVerificationPaused()` 时直接 return（`ApplicationHook.kt:748-749`）—— 模块连「拉起支付宝」都不做。
- `executeRpc` 在离线/验证态直接返回 `blockedResponse()`，`RpcDispatchGate.awaitPermission()` 二次拦截 —— **触发风控的那条 RPC 永远不会重放**。
- 支付宝安全验证页通常由用户手动进入对应场景（蚂蚁森林首页等）时服务端下发。模块既不清状态、也不引导、也不重放请求，用户只能对着一个不会出现的弹窗等待。
- 通知文案「请先在支付宝完成验证」没有任何「找不到验证入口时怎么办」的兜底说明。

### 根因 5（次要）：模块 App 侧没有任何兜底入口

- 支付宝内注册的 receiver 在 API 33 以下用 `RECEIVER_NOT_EXPORTED`（`ApplicationHook.kt:820-829`），模块 App 发不进去。
- 模块 App 也无法写支付宝的 `shared_prefs`。
- 因此用户侧不存在任何「重置验证暂停」的操作，唯一退路只剩重装。

## 四、修复方案

### P0-1 收紧验证判定，`1009` 不再单独作为验证判据

`RequestManager.kt`

```kotlin
private val VERIFICATION_TEXTS = listOf(
    "请进行验证后继续",
    "为保障您的正常访问",
    "为了保障您的操作安全"
)

@JvmStatic
fun isVerificationRequired(errorCode: String?, errorMessage: String?): Boolean {
    val message = errorMessage.orEmpty()
    if (VERIFICATION_TEXTS.any { message.contains(it) }) return true
    if (errorCode == "RPC_VERIFICATION_REQUIRED") return true
    // 1009 是通用业务码（访问被拒绝），不再单独判定；只记录以便后续用真实日志核对。
    if (errorCode == "1009") {
        Log.record(TAG, "收到 1009（业务拒绝），未判定为安全验证 | msg=$message")
    }
    return false
}
```

同步修改 `AntFarmDonationResponse.java:14-18`，移除 `"1009".equals(code)` 与 `"1009".equals(response.optString("error"))`。

> 待确认项：`docs/superpowers/plans/2026-09-05-verification-and-log-errors.md` 记录过「3 次原始 1009 响应」。实施前需用真实日志核对这 3 次的 `error` / `errorMessage` / `memo` 原文，确认服务端是否在风控场景下也返回 1009。若确实同时存在，则改为「1009 **且** 文案属于风控语义」才判定，而不是无条件判验证。

### P0-2 暂停标志增加 TTL 与启动自愈

`RequestManager.kt`

```kotlin
private const val VERIFICATION_TTL_MS = 30 * 60 * 1000L   // 30 分钟
private const val KEY_AT_SUFFIX = "_at"

private fun markVerification(uid: String) {
    verificationPrefs()?.edit()
        ?.putString(uid, UUID.randomUUID().toString())
        ?.putLong(uid + KEY_AT_SUFFIX, System.currentTimeMillis())
        ?.commit()
}

private fun clearVerification(uid: String) {
    verificationPrefs()?.edit()?.remove(uid)?.remove(uid + KEY_AT_SUFFIX)?.commit()
}
```

`onRpcBridgeReady()` 改为：

```kotlin
fun onRpcBridgeReady() {
    val uid = UserMap.currentUid
    recoveryPolicy.reset()
    if (uid == null) return
    if (verificationPrefs()?.contains(uid) != true) return
    val at = verificationPrefs()?.getLong(uid + KEY_AT_SUFFIX, 0L) ?: 0L
    if (at <= 0L || System.currentTimeMillis() - at > VERIFICATION_TTL_MS) {
        clearVerification(uid)
        Log.record(TAG, "验证暂停标志已超时（${(System.currentTimeMillis() - at) / 60000} 分钟），自动解除")
        return
    }
    recoveryPolicy.onVerificationRequired()
    ApplicationHook.setOffline(true)
    Log.record(TAG, "保留安全验证暂停状态，等待用户确认恢复")
    notifyVerificationPause()
}
```

效果：即使判定错误，最多卡 30 分钟，不再需要重装。

### P0-3 恢复流程改为「先清标志，再判定」

`resumeAfterManualVerification()`：只要 uid 匹配就清除标志并解除暂停；token 仅用于日志与防重放提示，不作为放行门槛。

```kotlin
@Synchronized
fun resumeAfterManualVerification(intent: Intent): Boolean {
    val uid = UserMap.currentUid ?: return false
    if (intent.getStringExtra("userId") != uid) return false

    val expected = verificationPrefs()?.getString(uid, null)
    val tokenMatched = expected != null &&
        intent.getStringExtra("verificationToken") == expected
    if (expected == null && !isVerificationPaused()) return false   // 本来就没暂停

    Log.record(TAG, "收到恢复指令（令牌匹配=$tokenMatched），等待旧任务退出")
    runBlocking { ModelTask.stopAllTaskAndJoin() }
    if (UserMap.currentUid != uid) return false

    clearVerification(uid)          // 关键：无论令牌是否匹配都清除
    recoveryPolicy.reset()
    ApplicationHook.setOffline(false)
    Log.record(TAG, "已解除安全验证暂停")
    return true
}
```

### P0-4 提供「跳过并恢复」出口 + 修正文案

- `Notify.sendNewNotification()` 增加可选第二 action（沿用其现有 `resumeAction` 参数风格）：

```kotlin
fun sendNewNotification(
    title: String?, content: String?,
    resumeAction: PendingIntent? = null,
    skipAction: PendingIntent? = null
)
```

```kotlin
if (resumeAction != null) {
    errorBuilder.addAction(android.R.drawable.ic_media_play, "已验证，恢复任务", resumeAction)
}
if (skipAction != null) {
    errorBuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "跳过并恢复", skipAction)
}
```

- `notifyVerificationPause()` 构造两个 PendingIntent（request code 109 / 110），后者带 `force=true`。
- 广播处理器增加：

```kotlin
BroadcastActions.RESUME_VERIFIED -> execute {
    if (RequestManager.resumeAfterManualVerification(intent)) execHandler()
}
BroadcastActions.SKIP_VERIFICATION -> execute {
    if (RequestManager.forceResume(intent)) execHandler()
}
```

- `showVerificationResumeDialog()` 增加中性按钮「没有验证页，跳过恢复」，并改写文案：

```
标题：自动任务已暂停
内容：检测到支付宝风控拦截。
      · 若你已打开支付宝 → 蚂蚁森林并看到验证页：完成验证后点「已验证，恢复任务」
      · 若没有任何验证页（多为误报）：点「跳过并恢复」即可继续自动任务
按钮：保持暂停 / 跳过并恢复 / 已验证，恢复任务
```

### P1-1 模块 App 侧兜底入口

把支付宝内 receiver 改为 `Context.RECEIVER_EXPORTED`（API 33+）并配自定义 permission 或校验调用方包名，模块 App 提供「重置安全验证暂停」按钮，显式 `setPackage("com.eg.android.AlipayGphone")` 发送 `SKIP_VERIFICATION`。

### P1-2 控制台日志区分「原始服务端验证」与「本地拦截」

当前 `RPC_VERIFICATION_REQUIRED` 只在 `VERIFICATION_REQUIRED_RESPONSE` 常量中出现，日志无法区分。建议在 `handleVerificationRequired(method, errorCode, errorMessage)` 里把原始码与原文一并落盘，便于事后核对误判率。

## 五、验收清单

- [ ] 单测：`isVerificationRequired("1009", "系统繁忙")` 返回 false；`isVerificationRequired(null, "请进行验证后继续")` 返回 true。
- [ ] 单测：标志写入后 advance 时间超 TTL，`onRpcBridgeReady()` 应清除并保持 `offline=false`。
- [ ] 单测：`resumeAfterManualVerification` 在 token 不匹配时仍清除标志并返回 true。
- [ ] 单测：`forceResume` 在无标志时返回 false，不误触发。
- [ ] 实机：制造一次 1009 业务拒绝，确认**不再**弹出「自动任务已暂停」。
- [ ] 实机：真实风控场景下，通知与对话框均出现「跳过并恢复」入口，点击后自动任务恢复。
- [ ] 实机：暂停后完全关闭支付宝再打开，30 分钟内保留暂停、超过 30 分钟自动解除。
- [ ] 回归：`./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`（注意 `ChouChouLeSchedulePolicyTest.kt` 仍编译失败，见 TODO.md P0-1）。

## 六、约束

- 不自动完成或绕过安全验证；「跳过并恢复」只解除模块自身的暂停状态，不伪造验证结果。
- 不承诺修复后绝不出现支付宝验证 —— 程序能控制的是判定准确性、状态可恢复性与引导文案。
- 保持「验证状态按账号作用域」的既有设计，不做跨账号串状态。

## 七、实施记录（2026-09-21）

已按 P0-1 ~ P0-4 与 P1-2 落地，P1-1 采用与计划不同的实现方式（见下）。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `hook/VerificationPausePolicy.kt` | **新增**。抽出纯判定逻辑（风控文案/专用码判定 + 标志超时判定 + 触发场景→应用入口映射），无 Android 依赖，遵循「判定逻辑抽成 `*Policy`」的项目硬规则 |
| `hook/RequestManager.kt` | 判定改走 `VerificationPausePolicy`；标志增写时间戳与触发入口并支持超时清除；`onRpcBridgeReady` 超时自愈；恢复逻辑合并为 `resumePausedTasks(intent, requireToken)`，**任何早退分支都不再保留标志**；新增 `forceResumeAfterVerification`；通知与对话框增加「跳过并恢复」并点明应打开哪个页面；日志区分原始风控与本地拦截 |
| `hook/ApplicationHook.kt` | `BroadcastActions` 新增 `SKIP_VERIFICATION` / `EXTRA_USER_ID` / `EXTRA_VERIFICATION_TOKEN`；接收分支新增跳过恢复；receiver 注册统一为 `RECEIVER_EXPORTED` |
| `util/Notify.kt` | `sendNewNotification` 增加可选 `skipAction` 参数与对应按钮 |
| `hook/rpc/bridge/NewRpcBridge.java`、`OldRpcBridge.java` | 调用点补齐 `errorCode` / `errorMessage`，便于日志定位误判 |
| `task/antFarm/AntFarmDonationResponse.java` | `requiresVerification` 移除 `1009`，只认 `RPC_VERIFICATION_REQUIRED` |
| `hook/VerificationPausePolicyTest.kt` | **新增** 8 项单测：1009 不判验证、文案判验证、专用码判定、2026-09-21 实测样本回归、RPC 域→入口映射、未识别不猜测、TTL 过期、旧版无时间戳标志视为过期 |
| `hook/RequestManagerTest.kt` | 新增「业务拒绝码不再单独触发验证暂停」 |
| `task/antFarm/AntFarmDonationResponseTest.java` | 断言反转为 `assertFalse`，并补充 `resultCode=1009` 用例 |

### 与计划的差异

- **P1-1 未新增模块 App 侧按钮。** 理由：P0-4 的对话框入口本就出现在用户看到弹窗的同一场景（支付宝前台），通知入口会随每次宿主启动重新下发，叠加 30 分钟 TTL，用户已有三条出路；再开一个跨进程广播入口只会扩大攻击面。改为**修正 receiver 导出级别的不一致**：原实现 API 33+ 用 `RECEIVER_EXPORTED`、以下用 `RECEIVER_NOT_EXPORTED`，导致模块 App 下发的「手动任务 / 重启」等既有指令在 Android 12 及以下收不到，属于顺带修掉的真实缺陷。
- **TTL 语义说明。** 超时解除的只是**本地熔断**，不是服务端风控。标记过期后模块会重新发起请求；若服务端仍在拦截，会立刻再次命中验证响应并重新暂停。因此不存在「绕过验证」——服务端始终是最终裁决者，这与既有网络熔断的自动重试语义一致。
- **令牌精度收敛（2026-09-23 修订）。** 原实现允许「跳过并恢复」不出示令牌就解除暂停，理由是给误判场景留一条自救通路。但接收器在 Android 12 及以下**必须导出**，这等于给外部应用留了一帧可强制解除的广播。现改为：**只要宿主私有存储里存有令牌，任何入口都必须出示它** —— 令牌是随机 UUID，只通过通知与对话框的 `PendingIntent` 下发，外部应用无从获知；而合法入口本就携带正确令牌，自救能力没有任何损失。仅在完全不存在令牌（从未落盘过标志）时才放行。

### 与硬规则 3 的关系（2026-09-23 补充）

`AGENTS.md` 硬规则 3 曾禁止「写入跨调用、跨当天的持久化暂停标记」，与本修复保留的 `shared_prefs` 暂停标志直接冲突。该冲突在 PR#3 评审中被指出，这里记录核实结论与最终决策。

**事实澄清：持久化标志不是本 PR 引入的。**

| 时间 | 提交 | 事实 |
| --- | --- | --- |
| 2026-08-01 | `9634797d` | 硬规则 3 的来源（能量雨 spec）落地 |
| 2026-09-05 | `2133ee86` | **把暂停从纯内存态改为落盘态**（新增 `VERIFICATION_PREFS`）——违反规则 3 的是这一次 |
| 2026-09-22 | 本 PR | 保留落盘，补 TTL 上界、启动自愈与自救入口 |

`2133ee86` 之前，`RequestManager` 的暂停只依赖 `recoveryPolicy.blockReason` 与 `setOffline`，进程重启即清，**完全符合当时的规则 3**。也就是说规则与代码已经不一致近三周，且因为规则只是自然语言、没有任何可执行检查而无人察觉。

**决策：保留有界落盘，同时把规则 3 改写为分层表述（路线 A）。**

理由是：跨重启保留暂停是 09-05 的既有产品判断（避免宿主被系统清理后立刻重新冲击服务端，反而恶化账号风控）；而硬规则 3 的原始范围来自能量雨 spec，该 spec 原文只处理「能量雨专用的当天标记」。让 RPC 链路的全局熔断去迁就一条为业务级标记写的规范，比把规则改成「分层 + 可执行约束」更不贴合实际。反面路线（彻底不落盘）会让宿主每次被清理后立刻放行请求，等于放弃 09-05 想达成的目标。

**本 PR 对四条硬约束的兑现位置：**

| 约束 | 实现 |
| --- | --- |
| (a) TTL 上界 ≤30 分钟 | `VerificationPausePolicy.VERIFICATION_TTL_MS` |
| (b) 启动必须自愈 | `VerificationPausePolicy.restoreActionFor` → `RequestManager.onRpcBridgeReady`（第九节真机已验证） |
| (c) 用户可见解除入口 | 通知与对话框两处均可「跳过并恢复」/「已验证，恢复任务」 |
| (d) 到期重新请求、不缓存结论 | TTL 只解除本地熔断，服务端始终是最终裁决者（见上方「TTL 语义说明」） |

**同步改动**：`AGENTS.md` 规则 3 改写为分层表述；能量雨 spec 补「适用范围」说明。

### 待实机确认

- [x] **已用 2026-09-21 实机日志核对完毕**，见下方第八节。结论：文案判定足以覆盖真实风控，无需回退。
- [x] **普通业务拒绝不再触发暂停**：2026-09-22 真机验证，13 条 `PROMISE_TODAY_FINISH_TIMES_LIMIT`（芝麻信用「当天完成任务次数超过限制」）均**未**触发暂停，模块继续跑其余任务。见第九节。
- [x] **旧版遗留的暂停标志自动解除**：实机命中 `[RequestManager]: 安全验证暂停标志已超时或来自旧版本，自动解除`，详见第九节。
- [ ] 实机验证 30 分钟 TTL 到期自动解除（本次只验证了「无 `<uid>_at` 的旧版遗留标志」判定为过期这一分支，未等到真实 30 分钟）。
- [ ] 验证「跳过并恢复」在通知与对话框两处均可点击生效。

## 八、2026-09-21 实机日志核对

数据来源：`.log/error_2026-09-21_22.43.09.txt`（用户实机，201 行，覆盖 17:24:07 ~ 22:26:29，约 5 小时）。

### 结论 1：`1009` 与风控文案同时返回，文案判定是正确判据

日志中服务端原始响应（非本地拦截）共 3 条：

| 行 | 方法 | 响应 |
| --- | --- | --- |
| 32 | `alipay.membertangram.biz.rpc.student.queryCheckInModel` | `{"error":3000,"errorMessage":"系统出错，正在排查"}` |
| 35 | 同上 | 同上 |
| 38 | **`com.alipay.antfarm.doFarmTask`** | `{"error":1009,"errorMessage":"为了保障您的操作安全，请进行验证后继续。","errorNo":3,"errorTip":"1009"}` |

**真实风控是「1009 + 风控文案」一起返回的**，而普通系统错误是 3000 + 非风控文案。因此：

- 剔除「1009 单码判定」不会漏检真实风控（文案仍命中）；
- 反而排除了把普通业务拒绝当风控的风险。

原计划里预留的「回退为 1009 且文案命中」分支**不需要**，文案判定严格更精确。已加入回归用例 `2026-09-21 实测样本仍能命中真实风控`。

### 结论 2：事故本身是「真风控 + 找不到验证页」，不是误判

时间线：

| 时间 | 事件 |
| --- | --- |
| 18:17:52.74 | `com.alipay.antfarm.doFarmTask` 触发风控（**蚂蚁庄园**场景） |
| 18:17:52.75 | 庄园/森林 R1 任务异常结束，暂停生效 |
| 19:03 ~ 22:26 | `AntForest.queryFriendHome` 蹲点循环持续刷 `RPC_VERIFICATION_REQUIRED`，共 138 条 |
| 22:43 | 用户放弃，导出日志 |

关键发现：**支付宝的安全验证页与触发它的业务场景绑定**。触发方是蚂蚁庄园，用户在支付宝首页 / 蚂蚁森林是看不到任何验证入口的 —— 这正是用户所说「支付宝并没有弹出安全验证」的成因，而不是模块判错。

由此补充修正（本次新增）：`VerificationPausePolicy.entryHintFor(method)` 把 RPC 域映射到应用入口（`antfarm`→蚂蚁庄园、`antforest`/`ecolife`→蚂蚁森林、`antocean`/`antaifish`→神奇海洋、`antdodo`/`antstall`→蚂蚁新村、`antsports`→运动、`antmember`/`alipaymember`/`membertangram`/`memberasset`/`amic.`→会员中心），无法识别时返回 null 不猜测。该入口名随暂停标志一起持久化（`<uid>_hint`），重启后恢复的通知与对话框都会带上它。

### 结论 3：暂停期间确实没有向服务端发请求

`executeRpc` 在 `ApplicationHook.offline` 时直接返回 `blockedResponse()`，不从 Bridge 发出。日志中 138 条 `queryFriendHome` 失败是蹲点循环在本地被拦截后继续 sleep-retry 产生的，时间间隔恒为 5 秒。**即本地熔断工作正常，没有在风控期间继续冲击服务端。**

### 遗留问题（本次未修，建议单独立项）

**暂停期间蹲点循环不停，日志持续刷屏。** `ModelTask.stopAllTask()` 停掉了主任务，但森林蹲点的长期子任务不在等待范围内，导致 19:03~22:26 每 5 秒一条日志。请求本身没发出去，属日志噪声与无效调度，但会淹没真正有用的信息。建议在蹲点循环的等待点补 `RequestManager.isVerificationPaused()` 检查后退出。

## 九、2026-09-22 真机部署与端到端验证

测试机：`25113PN0EC`（Android 17 / API 37，arm64-v8a，IP `192.168.1.51`，串号 `f34c5d30`）。
**无 root**（无 su / Magisk / KernelSU，SELinux Enforcing），框架为 **LSPatch v1.2 管理器模式**。

### 9.1 部署前提（影响流程的事实）

| 事实 | 依据 | 影响 |
| --- | --- | --- |
| 模块配置在**外部存储**，非应用私有目录 | `util/Files.kt::getMainDir()` → `/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/`；实机确认 `config/`、`log/`、`ModuleStatus.json` 均在该路径 | 卸载重装模块**不丢配置** |
| 框架是**管理器模式**，模块不嵌入支付宝 | 被 patch 的支付宝 `assets/lspatch/config.json`：`useManager=true`、`injectDex=false`，且无 `assets/lspatch/modules/` | 换模块 APK **立即生效**，无需重新 patch 支付宝 |
| 手机上原包为 CI 正式签名，本机为 debug 签名 | `app/build.gradle.kts` 中 debug/release 均用 `signingConfigs.getByName("debug")`；仓库内无 keystore | 必须先彻底卸载才能装本地包 |

### 9.2 安装路径的实际结论

安装过程中出现的两类失败，归属如下：

| 现象 | 真实原因 | 处理 |
| --- | --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` | CI 签名 ≠ 本地 debug 签名，硬冲突 | 彻底卸载后重装（`pm uninstall`，**不能加 `-k`**） |
| `Existing package ... signatures do not match`（用 `-k` 之后） | 保留数据会使 PackageManager 记住旧签名，等同「包还在」 | 必须彻底 `pm uninstall` |
| `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` | MIUI/HyperOS 把**签名变更**视为安全事件，要求机上一键确认 | 点一次「允许」即可，属一次性成本 |

受控实验（同签名升级，**全程无人点击手机**）：

| 命令 | 结果 |
| --- | --- |
| `pm install -r` | 2 秒 Success，无弹窗 |
| `adb install -r` | 8 秒 Success，无弹窗 |

**结论**：需要人工确认的是「签名变更」这个事件本身，而非某个安装命令。
`pm install` 与 `adb install` 在此场景行为等价，不存在「`pm install` 绕过 MIUI 拦截」这回事。
签名稳定后两条路径**全自动**，可脚本化。

### 9.3 部署后必须补的一步：LSPatch 作用域

卸载重装模块后，支付宝**主进程**一度拿到 `Module snapshot holds 0 module(s)`（而 `:push` 子进程仍是旧的 1 个），
表现为模块代码完全不执行、模块日志零输出。**在 LSPatch 管理器中重新为支付宝勾选该模块后恢复**（所有进程均 `holds 1 module(s)`）。

> 这是卸载重装的必然后果，需计入标准流程；判定方法统一为检查主进程的 `Module snapshot` 行。

### 9.4 验证结果（通过）

同一份 `record.log` 中，新旧版本行为相邻，构成天然对照：

```
 48: 22日 00:04:37.23  Loaded Sesame-VN 0.9.9✨                              ← 旧版（CI 包）
 49: 22日 00:04:37.24  [RequestManager]: 保留安全验证暂停状态，等待用户确认恢复   ← 死锁现场
117: 22日 00:23:40.71  Loaded Sesame-VN 0.9.9-debug✨                        ← 新版（本地修复包）
118: 22日 00:23:40.72  [RequestManager]: 安全验证暂停标志已超时或来自旧版本，自动解除 ← 自愈生效
119: 22日 00:23:40.73  [ModelTask]: 开始执行第1轮任务: 主任务                   ← 任务自动恢复
122: 22日 00:23:40.74  [CoroutineTaskRunner]: 🔄 [第 1/1 轮] 开始，共 8 个任务
```

- **启动自愈生效**：无需任何人工操作，暂停标志被自动解除，任务随即恢复调度。
  （用户此前正是靠重装支付宝才能恢复，现在重装模块 + 重启支付宝即可，且不再需要重装支付宝。）
- **重试循环停止**：`error.log` 中 `RPC_VERIFICATION_REQUIRED` 共 5 条，全部落在 21日 23:37–23:59（旧版），
  **重装后计数为 0**。原先被拦截的 `AntForest.queryFriendHome`（查询好友主页）已恢复正常执行。
- **普通业务拒绝不再误判**：重装后 `error.log` 新增 13 条 `[AntMember]` 的 `PROMISE_TODAY_FINISH_TIMES_LIMIT`
  （"当天完成任务次数超过限制，请隔天再加入"），属**普通业务限流而非风控**。
  模块**未**暂停，其余任务照常继续——这正是旧逻辑最容易误判的场景。
- **修复代码确已进包**：从设备拉回已安装 APK，其全部 dex 中可检出
  `VerificationPausePolicy`、`RPC_VERIFICATION_REQUIRED`、`sesame.skipVerification`、
  `跳过并恢复`、`安全验证暂停标志已超时或来自旧版本`。

### 9.5 本次未覆盖

- 30 分钟 TTL 的真实到期分支（仅验证了「旧版遗留标志 = 无 `<uid>_at` ⇒ 视为过期」这一分支）。
- 「跳过并恢复」在通知 / 对话框两处入口的实际点击。
- 第七节遗留的蹲点循环刷屏问题：本次日志中蹲点任务正常（`蹲点[...]将在X分钟后验证`），未复现，仍待处理。

---

## 十、2026-09-23 PR#3 评审核对与修复方案

> **来源**：外部评审报告（WorkBuddy 资料库 `pr-3-verification-pause-stuck`）的逐条核对。
> **核对基准**：`fix/verification-pause-stuck`（HEAD `0b0fbee`）vs `origin/main`（`9213348`）。
> **核对方式**：逐条回源码、回 git 历史验证，**不采信报告结论本身**。
>
> 本节原先是一份独立文档（`2026-09-23-pr3-review-verification.md`），2026-09-24 并入本文档 ——
> 它核对的就是**本功能**（PR#3 即本功能的合并），按「一个功能一对文档、后续轮次追加章节」的约定
> 不该另立一份；`plans/` 的文件名也不该绑在一次性 PR 编号上。

### 10.1 核对结论总览

| 章节 | 条目 | 评审结论 | 核对结论 | 严重度 |
| --- | --- | --- | --- | --- |
| 第 1 节 | 无影响变更 1–8 | 均无影响 | **全部成立** | — |
| 第 2 节 | A 广播导出级别副作用 | 建议后续收口 | **事实成立**；但建议的收口手段**不可落地** | 低 |
| 第 2 节 | B skip 路径校验放宽 | 有意设计、低危 | **成立** | 低 |
| 第 2 节 | C `runBlocking` 是否致 ANR | 可接受 | **成立且非问题**（已确认后台派发器） | 无 |
| 第 2 节 | D plan 未回应规则 3 | 建议补决策段 | **成立** | 文档 |
| 第 3 节 | 1 持久化暂停标志与规则 3 冲突 | 高 / 确定 / 请勿合入 | **冲突成立**，但来源与影响范围需两处修正 | 建议**高 → 中** |

**结论**：评审报告的事实描述经核对基本准确，对第 1、2 节的判断可以直接采信。需要修正的是第 3 节的**归因**与**影响链表述**（见 10.3），以及 A 项给出的**修复手段**（见 10.4 方案 2）。

### 10.2 逐条核对明细

#### 10.2.1 第 1 节「无影响变更」8 项 —— 全部成立

| # | 位置 | 核对方式 | 核对结果 |
| --- | --- | --- | --- |
| 1 | `AntFarmDonationResponse.requiresVerification` | `git diff main...HEAD` | 确认由「认 `RPC_VERIFICATION_REQUIRED` / `resultCode=1009` / `error=1009`」收紧为只认 `RPC_VERIFICATION_REQUIRED`；3 处调用方（`AntFarm.kt:1757/1776/1823`）原 `return false` 后落到 `ResChecker.checkRes` 失败分支，`Log.record(failureMessage(jo))` 兜底，**行为等价不丢功能** |
| 2 | `OldRpcBridge` / `NewRpcBridge` | 全仓库 grep 调用点 | `handleVerificationRequired` 签名由 `(method)` 扩为 `(method, errorCode, errorMessage)`，调用点仅 `NewRpcBridge.java:301`、`OldRpcBridge.java:152`，均已同步。`isVerificationRequired(errorCode, errorMessage)` **签名未变**（main 亦为 2 参），`EnergyRainCoroutine.kt:45` 不受编译影响 |
| 3 | `VerificationPausePolicy`（新增） | 读源码 + 读测试 | 无 Android 依赖，纯逻辑；`requiresVerification` / `entryHintFor` / `isMarkExpired` 三入口；硬规则 5 合规 |
| 4 | `RequestManager` 清除路径 | 读源码 | `markVerification` / `clearVerification` / `readHint` 三键（`<uid>` / `<uid>_at` / `<uid>_hint`）成对管理；`resumePausedTasks` 在账户一致的前提下**任何校验分支都不再早退不清标志**，确认修掉旧实现的核心缺陷 |
| 5 | `onRpcBridgeReady` | 读源码 + 追调用点 | 新增 TTL 校验分支；全仓库唯一调用点 `ApplicationHook.kt:643`，且其上一行 `ApplicationHook.kt:642` 已先把 `offline = false` 复位（见 10.2.4 核查） |
| 6 | `notificationText` / `dialogText` | 读源码 | 纯文案，点明触发入口；无功能风险 |
| 7 | `Notify.sendNewNotification` | 读源码 | `@JvmOverloads` + `resumeAction` / `skipAction` 默认 `null`，旧调用点不受影响 |
| 8 | 测试 3 文件 | 读测试源码 | `VerificationPausePolicyTest` 8 用例（三态 / 入口映射 / TTL / 旧版无时间戳 / 09-21 实测样本）；`RequestManagerTest` 新增「业务拒绝码不再单独触发验证暂停」；`AntFarmDonationResponseTest` 断言翻转 —— **与修复点对齐** |

#### 10.2.2 第 2 节 A/B/C/D

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

本文档 §七「与计划的差异」原先只论证了对**硬规则 2**（不绕过验证）的合规性、并说明令牌精度下调，**全文未出现对硬规则 3 的回应**。评审判断正确 —— 已由 §七「与硬规则 3 的关系（2026-09-23 补充）」闭合。

#### 10.2.3 第 3 节 问题 1（持久化暂停标志与规则 3 冲突）—— 冲突成立，归因需修正

冲突本身**成立**且无争议：`RequestManager.markVerification`（`:116-122`）把 `<uid>` / `<uid>_at` / `<uid>_hint` 三键写入 `shared_prefs`（`VERIFICATION_PREFS = "sesame_rpc_verification"`），`onRpcBridgeReady`（`:330-346`）在进程重启后读回并重建暂停 —— 确实构成「跨调用、跨进程重启存活」的持久化标记，与 `AGENTS.md:18` 规则 3 的字面要求直接冲突。

但**来源**与**影响链表述**需要修正，见 10.3。

#### 10.2.4 核查过但不成立的怀疑点（备查）

为确认评审是否有遗漏，另核查了三个可疑点，**均不成立**：

| 怀疑点 | 核查结论 |
| --- | --- |
| `onRpcBridgeReady` 过期分支只清标志、不复位 `offline`，可能残留离线态 | **不成立**。调用方 `ApplicationHook.kt:642` 在调用前已执行 `offline = false`，且该方法是全仓库唯一调用点（模块初始化路径，每进程一次） |
| `handleVerificationRequired` 在 `recoveryPolicy.onVerificationRequired()` 返回 `NONE` 时会早退，但此前已 `setOffline(true)`，可能留下无标志的悬挂离线态 | **不成立**。`RpcRecoveryPolicy.onVerificationRequired()` 无论返回值如何都会 `reason.set(VERIFICATION)`，拦截照常生效；且首次调用已完成写标志与通知，此处早退只是避免重复通知，属正确设计 |
| 通知与对话框的 `PendingIntent` 共用固定 request code，可能相互覆盖 | **不成立**。`:41-42` 定义了 `REQUEST_CODE_RESUME = 109` / `REQUEST_CODE_SKIP = 110`，两者分属不同动作，不冲突 |

### 10.3 对评审结论的修正

#### 10.3.1 修正 1：持久化标记不是本 PR 引入的

报告表述「本 PR **保留**了写进 `shared_prefs` 的跨调用持久化标志」——「保留」二字准确，未宣称本 PR 引入。但结合其「请勿直接合入」的结论，容易被读成「本 PR 是问题来源」。git 历史给出了明确答案：

| 时间 | 提交 | 事实 |
| --- | --- | --- |
| 2026-08-01 | `9634797d` 记录能量雨安全验证重试设计 | 规则 3 的来源文档落地 |
| **2026-09-05** | **`2133ee86` 修复开发分支安全验证暂停及会员游戏每日去重** | **新增 `VERIFICATION_PREFS`，把暂停从内存态改为落盘态 —— 违反规则 3 的是这一次改动** |
| 2026-09-22 | 本 PR | 保留落盘，但补 TTL 上界、启动自愈、自救入口 |

`2133ee86` 之前，`RequestManager` 的暂停**纯内存**（仅 `recoveryPolicy.blockReason == VERIFICATION` + `setOffline(true)`），进程重启即清，**完全符合规则 3**。也就是说：**规则 3 曾被满足，2026-09-05 被主动放弃，而 main 至今如此。**

这带出一个比「本 PR 是否合规」更重要的事实：**规则 3 与主干代码已经不一致近三周，且无人察觉**。原因是这条约束只以自然语言存在、没有任何可执行检查。据此，本次应当解决的是「规则与代码长期背离」这个根因，而不是让本 PR 单方面回退。

#### 10.3.2 修正 2：2026-08-01 spec 的适用范围被扩大引用

报告将 `2026-08-01-energy-rain-verification-retry-design.md` 与规则 3 并列，作为「同样禁止」的第二重依据。核对原文后需要收窄：

- 该 spec 的目标行确实写着「不再保存跨调用、跨当天的安全验证暂停状态」，**但**其实现章节明确限定为「删除**能量雨专用**的当天安全验证标记及所有读写」，全文未涉及 RPC 层的全局熔断。
- 该 spec 处理的是**业务功能级的「当天标记」**（能量雨当次不重试），与 **RPC 链路级的全局熔断**是两种机制、两种目的。

因此：冲突的有效依据**只有 `AGENTS.md:18` 规则 3 本身**。这不削弱结论（规则 3 的措辞确实是全局的），但会改变处理方式 —— 正确的解法是**给规则 3 划定范围**，而不是让 RPC 层去迁就一条本为业务级标记写的规范。

#### 10.3.3 修正 3：严重度建议由「高」下调为「中」

报告的影响链表述为「标志落盘 → 进程重启仍命中 → 即便 TTL 到期自愈，期间所有 RPC 被本地熔断 → 与规则 3 语义不一致」。其中「期间所有 RPC 被本地熔断」需要区分：

- **这恰恰是熔断的设计目标**，不是缺陷 —— 在风控未解除时继续冲击服务端才是要避免的（§八 结论 3 已实测确认暂停期间不发请求）。
- 真正的合规风险是**「跨调用」中的「用户已完成验证但模块仍拒绝发起请求」**这一段，而这正是本 PR 用 TTL + 启动自愈 + 两处自救入口在收敛的。

结合有界（30 分钟）、可自愈、有用户可见解除入口、服务端仍为最终裁决者、不产生数据丢失与安全问题，**建议严重度定为中**。真正意义上的「高」应保留给旧版那种**无界**卡死（用户只能卸载支付宝）—— 那是本 PR 修掉的问题。

#### 10.3.4 修正 4：A 项建议的收口手段不可落地

报告中「建议后续用「自定义签名级 permission」收口，而非裸 `EXPORTED`」在此项目结构下**无法实现**，原因是：

- 接收器是**运行期注册在宿主（支付宝）进程内**的，其可用的 `broadcastPermission` 必须能被**发送方**满足；
- 本场景的发送方有两类且分属**不同 uid**：模块 App（`fansirsqi.xposed.sesame` 进程）与宿主自身（对话框 `activity.sendBroadcast`、通知 `PendingIntent` 均由支付宝 uid 发出）；
- 任何由模块 App 定义的签名级 permission，**支付宝自身不持有** → 会把自己发出的恢复指令也拦掉；
- 而给宿主 manifest 新增权限又不可能（APK 已由原厂签名，patch 只改 code，不加权限声明）。

可落地的替代方案见 10.4 方案 2。

### 10.4 修复方案

#### 方案 1（阻塞项）：硬规则 3 的边界 —— 走路线 A，并改写规则

**建议：采用路线 A（修订规则），但不是无条件放宽，而是把「一刀切禁令」改成「分层 + 可执行约束」。**

理由（按权重排序）：

1. **规则 3 的原始适用范围本就窄于字面**：它源自能量雨 spec，而该 spec 只管业务级当天标记（10.3.2）。字面的全局禁令在写下时就没有真正覆盖 RPC 层。
2. **规则与代码的背离已存在近三周且无人察觉**（10.3.1）。一条长期与实际不符、且无可执行检查的规则，已不再是约束。继续保留只会让后续每个触及该区域的 PR 都「不合规」。
3. **路线 B 的真实代价被低估**：回到内存态后进程重启即遗忘暂停。MIUI 的后台清理十分常见，用户完成验证前若宿主被重启，模块会立刻重新发起请求 —— 这等于放弃 `2133ee86` 想达成的目标，而那个目标（让暂停跨重启存活）本身是合理的产品判断。
4. **规则 3 真正要防的三件事，可以用约束表达得更精确**：不要让暂停**无界**、不要让用户**无从得知入口**、不要让用户**无从解除**。这三条恰好是本 PR 补上的。

**修改位置与内容（均已落地）：**

1. **`AGENTS.md:18`** —— 规则 3 替换为分层表述（业务功能级禁止跨天标记 / RPC 链路级允许有界熔断 + 四条硬约束：TTL ≤30 分钟、启动自愈、用户可见解除入口、到期重查不缓存结论）。
2. **`docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md`** —— 「目标」章节补「适用范围」说明，明确只管业务功能级的当天标记，避免后续被扩大引用。
3. **本文档 §七** —— 新增「与硬规则 3 的关系」一节（同时闭合评审 #D）。

**验证方式**：

- 文档一致性：三处表述互不矛盾，且规则 3 的每条约束都能在 `RequestManager.kt` 中找到对应实现（TTL → `:35`；自愈 → `:336-340`；解除入口 → `:151-194`；到期重查 → `:336` 后 `reset()` + 不缓存结论）。
- 回归：`./gradlew :app:testDebugUnitTest` 保持通过（本方案只改文档，不改代码）。

#### 方案 2：A/B 广播按信任边界拆分接收器

> ⚠️ **本节方案随后被修正。** 原提议「自救接收器一律使用 `NOT_EXPORTED`」在 Android 8.0–12L 上会直接抛异常，
> 实际实现改为「13+ 用 `NOT_EXPORTED`、12L 及以下导出 + 令牌校验」。原因与证据见 10.6.1。

评审建议的签名级 permission 不可落地（10.3.4）。**可落地且更彻底的替代方案是按发送方信任边界拆成两个接收器**：

| 接收器 | 导出级别 | 承载动作 | 发送方 |
| --- | --- | --- | --- |
| 命令接收器 | `EXPORTED` | `RESTART` / `RE_LOGIN` / `MANUAL_TASK` / `RPC_TEST` / `STATUS` | 模块 App 自身进程（跨进程必需） |
| 自救接收器 | `NOT_EXPORTED` | `RESUME_VERIFIED` / `SKIP_VERIFICATION` | **仅宿主自身**（通知 `PendingIntent` + 宿主对话框） |

**修改位置**：
- `ApplicationHook.kt:821-853`（`registerBroadcastReceiver`）：构造两个 `IntentFilter`、注册两个 receiver 实例，分别按上表设置导出级别；`unregisterBroadcastReceiver`（`:855-865`）同步反注册两个。
- `ApplicationHook.kt:340-370`（`when` 分支）：动作分发按接收器归属拆到两个 `onReceive` 实现中（或共用一个处理函数、仅在注册处区分）。

**为什么可行**：`RESUME_VERIFIED` / `SKIP_VERIFICATION` 的发送方经全仓库 grep 确认**只有宿主自身**（见 10.2.2-A 第 2 点），同 uid 发送不受 `NOT_EXPORTED` 限制，因此拆出后自救链路完全不受影响，而外部 App 的伪造广播被系统直接拦掉。

**补充加固（可选，与上面正交）**：让 skip 路径也校验令牌 —— `RequestManager.kt:203-204` 改为 `requireToken = true`。由于 skip 的**唯一合法发送方**（通知与对话框）均通过 `skipIntent()`（`:143-149`）携带令牌，收紧不损失任何自救能力；而外部 App 拿不到令牌（它是写入宿主私有 `shared_prefs` 的随机 UUID）。需同步更新 `:201` 的 KDoc 与本文档 §七对「令牌精度下调」的说明。

**验证方式：**
- 模块 App 指令可达：`adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.restart`（应生效）
- 外部 App 伪造被拦：用任意第三方 App（或 `adb shell am broadcast` 以 `--user 0` 之外的 uid）发送 `...skipVerification`，**不应**解除暂停
- 自救链路不受影响：通知「跳过并恢复」、对话框「跳过并恢复」「已验证，恢复任务」三处点击均应生效
- **需在 Android 8.0–12L 实机复测**：该分支的 `ContextCompat.RECEIVER_NOT_EXPORTED` 依赖权限的同 uid 放行语义，是本次改动的风险集中区

#### 方案 3（对应 #D）：plan 补决策段

**修改位置**：本文档 §七「与计划的差异」之后。**内容**：说明为何保留持久化（跨重启存活是 09-05 的既有产品判断）、与规则 3 的边界如何划定、以及本次收敛了三件事（TTL 上界 / 启动自愈 / 两处自救入口）。与方案 1 的第 3 项为同一处改动，已合并执行。

#### 方案 4：TTL 到期分支的测试闭环

评审的 Todo 指出 plan 自承「30 分钟真实到期分支未实机验证」，建议补一条测试。核对后需要说明**残余风险实际很低**，并给出闭环路径：

- `VerificationPausePolicyTest` 已覆盖 `isMarkExpired` 的三种输入（TTL 内 `false`、TTL 外 `true`、无时间戳/负值 `true`）；
- `onRpcBridgeReady` 的「已过期」与「旧版本无时间戳」两个分支**共用同一段清除代码**（`:336-340`），其中「旧版本无时间戳」分支**已在 2026-09-22 真机验证通过**（日志命中「安全验证暂停标志已超时或来自旧版本，自动解除」）；
- 因此两者之差仅在 `isMarkExpired` 的返回值，而该函数已有单测。

**若要彻底闭环**，推荐做法（符合硬规则 5）：把 `onRpcBridgeReady` 的「读标志 → 判过期 → 决策（清除 / 重建暂停 / 无需动作）」抽成策略函数（例如并入 `VerificationPausePolicy`），`RequestManager` 只保留 prefs IO 与副作用调用。这样该分支可被纯 JVM 单测覆盖，无需 Android 环境。

**修改位置**：`VerificationPausePolicy.kt`（新增决策函数）+ `RequestManager.kt:330-346`（改为调用策略）+ `VerificationPausePolicyTest.kt`（补用例）。
**验证方式**：`./gradlew :app:testDebugUnitTest --tests "*VerificationPausePolicyTest*"`。

> 说明：由于宿主私有 `shared_prefs` 无 root 不可写，**无法**用 adb 直接伪造过期时间戳做端到端验证；策略抽取是唯一可自动化的闭环路径。

### 10.5 验证方式汇总

| 方案 | 类型 | 命令 / 操作 |
| --- | --- | --- |
| 1 规则改写 | 文档一致性 | 人工比对三处表述；`./gradlew :app:testDebugUnitTest` 保持通过 |
| 2 接收器拆分 | 单测 + 实机 | `adb shell am broadcast` 正反用例；Android 8–12L 实机复测自救三入口 |
| 3 plan 决策段 | 文档 | 人工评审 |
| 4 策略抽取 | 单测 | `./gradlew :app:testDebugUnitTest --tests "*VerificationPausePolicyTest*"` |

### 10.6 实施记录（2026-09-23）

#### 10.6.1 对方案 2 的修正：原方案在 Android 8.0–12L 上不可用

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

#### 10.6.2 实际实现

| 项 | 实现 | 位置 |
| --- | --- | --- |
| 接收器按信任边界拆分 | 命令通道：`RESTART` / `STATUS` / `RPC_TEST` / `MANUAL_TASK`；自救通道：`RESUME_VERIFIED` / `SKIP_VERIFICATION` / `RE_LOGIN` | `ApplicationHook.kt`：`registerBroadcastReceiver` / `registerDynamicReceiver` |
| 自救通道导出级别 | Android 13+ 用原生 `RECEIVER_NOT_EXPORTED`；12L 及以下无法使用（见 10.6.1），退化为导出并由令牌兜底 | 同上 |
| 令牌门槛 | 只要宿主已落盘令牌，任何恢复入口都必须出示它；完全无令牌时仍放行，保留自救能力 | `RequestManager.resumePausedTasks` |
| 恢复决策抽策略 | `restoreActionFor(hasMark, markedAt, now)` → `NONE` / `CLEAR_MARK` / `RESTORE_PAUSE` | `VerificationPausePolicy.kt`、`RequestManager.onRpcBridgeReady` |
| 规则 3 改写 | 分层：业务功能级禁止跨天标记；RPC 链路级允许有界熔断 + 四条硬约束 | `AGENTS.md` |
| spec 范围限定 | 能量雨 spec 补「适用范围」说明，避免被扩大引用 | `docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md` |
| plan 补决策段 | 新增「与硬规则 3 的关系」，含时间线证据 | 本文档 §七 |

**未采用**：评审建议的「自定义签名级 permission」。原因见 10.3.4 —— 接收器注册在宿主进程内，
宿主自身（对话框 / 通知 `PendingIntent`）用的是支付宝 uid，不持有模块定义的签名权限，会被一并拦掉。

#### 10.6.3 验证结果

| 项 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（含新增 `restoreActionFor` 用例） |
| `./gradlew :app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| 新增单测 | `启动时按标志状态决定清除标志还是重建暂停`、`旧版本遗留的无时间戳标志在启动时被清除` |

#### 10.6.4 仍需实机验证（两个 Android 版本区间的行为不同）

- **Android 13+**：外部 App 伪造 `SKIP_VERIFICATION` 应被系统直接丢弃；通知与对话框三处入口应正常。
- **Android 8.0–12L**：命令通道（模块 App 的「手动任务 / 重启」）应恢复可用；伪造的恢复广播应被令牌校验拒绝。

### 10.7 实机测试中发现的额外缺陷（2026-09-23，Android 10 设备）

为验证「Android 8.0–12L」区间，接入了一台已 root 的 MI 9（Android 10 / API 29）。环境探测中发现了一个**比导出级别更严重**的缺陷。

#### 10.7.1 缺陷：正式包里广播接收器从未注册

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

#### 10.7.2 这澄清了「指令收不到」的真正根因

PR 原先把这个现象归因于 `RECEIVER_NOT_EXPORTED` 的导出级别问题。实测澄清这是**两个独立缺陷**：

| 构建类型 | 现象 | 根因 |
| --- | --- | --- |
| 发行版（release） | 接收器压根没注册，**与 Android 版本无关** | 注册调用被 `BuildConfig.DEBUG` 门控 + R8 裁剪 |
| 调试版（debug） | 接收器会注册，但 Android 8.0–12L 上抛异常 | `ContextCompat.RECEIVER_NOT_EXPORTED` 需要宿主无法声明的权限 |

两者都要修，改导出级别并不能解决发行版的问题。

#### 10.7.3 修复

`ApplicationHook.initHandler()`：把注册移出调试分支改为无条件执行，调试分支只保留仅供本地调试的 HTTP 服务。

#### 10.7.4 验证：release 产物的静态证据

对 `assembleRelease` 产物（R8 已裁剪）检查 dex 字符串：

| 检查项 | 结果 | 含义 |
| --- | --- | --- |
| 调试服务密码 `ET3vB^#td87sQqKaY*eMUJXP` | **缺失** | 证实 R8 确实把 `BuildConfig.DEBUG` 分支整段裁掉了 |
| `BroadcastReceiver registered` | **存在** | 接收器注册已进入正式包 |
| `registerDynamicReceiver` / `mRescueReceiver` / `恢复指令令牌不匹配` | **存在** | 本次拆分与令牌门槛均在正式包中 |

由于旧实现里 `registerBroadcastReceiver` 的唯一调用点不可达，R8 必然把该方法一并剥离 ——
「调试密码缺失 + 注册日志存在」这一对结果即构成修复前后的对照证据。
另：`./gradlew :app:assembleRelease :app:testDebugUnitTest` 均 BUILD SUCCESSFUL。

#### 10.7.5 该设备的框架情况（供后续测试参考）

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

## 十一、2026-09-25 勾选口径核对与测试缺口

> 本节为**追加**记录，**不回填上文任何勾选**（按 `docs/development.md` §4：`plans/` 只追加、不回头改结论）。
> 起因：`TODO.md` 要引用本计划的状态，核对时发现勾选与实现不一致，故在此留档。

### 11.1 勾选与实现的实际差异

本计划现有 **3 勾 / 10 未勾**，但 10 项未勾中有一部分其实早已落地，另有一项**已被后续实现推翻**：

| 未勾项 | 实际状态 |
| --- | --- |
| 单测：`isVerificationRequired("1009", "系统繁忙")` → false | **已完成**。`VerificationPausePolicyTest` 的「通用业务拒绝码1009不再判定为需要安全验证」「2026-09-21 实测样本仍能命中真实风控」即此语义 |
| 单测：标志超 TTL 后 `onRpcBridgeReady()` 清除并保持 `offline=false` | **已完成，但方法名已变**：判定已抽成纯函数 `VerificationPausePolicy.isMarkExpired` / `restoreActionFor`，由「暂停标志超过保留时长后自动过期」「启动时按标志状态决定清除标志还是重建暂停」覆盖 |
| 单测：`resumeAfterManualVerification` 在 token 不匹配时**仍清除标志并返回 true** | ⛔ **预期已被实现推翻**，见 11.2 |
| 单测：`forceResume` 在无标志时返回 false | **未做**。启动路径的相邻语义已由 `restoreActionFor(hasMark = false) = NONE` 覆盖，但**广播路径无单测**，见 11.3 |
| 实机 × 5（1009 不再弹暂停、两处「跳过并恢复」、重开支付宝 30 分钟 TTL、TTL 到期自动解除、两处入口点击生效） | **未做** —— 仍是本计划的主要遗留，已在 `TODO.md` P1-1 登记 |
| 回归：`testDebugUnitTest + assembleDebug` | **已完成**。CI `build-and-test` 在每次 PR 与 main 推送都跑；2026-09-24 实测 **223 项 / 0 失败 / 3 跳过**。原文括注的「`ChouChouLeSchedulePolicyTest.kt` 仍编译失败」**已过期**（该阻塞 2026-09-22 已解，见 `TODO.md` P0-1） |

### 11.2 该单测项的预期已被推翻：恢复入口最终是 fail-closed

计划里写的是「token 不匹配时**仍清除标志并返回 true**」（fail-open：宁可放行也不卡死）。**最终实现选的是相反的 fail-closed**：

`RequestManager.kt:237-240` —— 令牌不匹配时 `Log.record("恢复指令令牌不匹配，已忽略")` 后 `return false`，且**保留**暂停标志。

理由见 `RequestManager.kt:216-221` 的注释：令牌是随机 UUID，只经通知与对话框的 `PendingIntent` 下发，外部应用无从获知；
而接收器在 Android 12 及以下必须导出，这道校验正是挡住**伪造「跳过并恢复」广播**的关键。即便真走到该分支，
也有 30 分钟 TTL 与启动自愈兜底，不再是「只能重装支付宝」的卡死路径。

⇒ **本条不能按字面补测**：照计划原文写断言会直接得到失败用例。它记录的是当时的设计意图，
已被后续轮次（10.4「收紧令牌校验并抽出恢复决策」）取代 —— 属**正常的设计演进**，不是漏做。

### 11.3 两条剩余单测项：不是「忘了写」，而是不可直接单测

`RequestManager.resumeAfterManualVerification`（`:198`）与 `forceResumeAfterVerification`（`:207`）
都只是 `resumePausedTasks(intent, requireToken = …)` 的薄封装，而后者（`:224-256`）把**判定与副作用交织在一起**：
从 `Intent` 取参、读 `UserMap.currentUid`、读宿主私有 SharedPreferences、`ModelTask.stopAllTaskAndJoin()`、
`ApplicationHook.setOffline()`。本项目的单测环境承载不了它：

- 测试依赖只有 `junit` 与 `org.json` —— **没有 mockk / mockito / Robolectric**；
- `app/build.gradle.kts` 的 `testOptions` **未开** `isReturnDefaultValues`；
- 全测试树**没有任何文件 `import android.*`** —— JVM 单测一律不碰 Android 类。

⇒ 要覆盖这两条，必须先按**硬规则 5** 把广播路径的判定抽成纯函数（`restoreActionFor` 就是这么抽的，
其 KDoc 明写「抽成纯函数，是为了让这条分支能被 JVM 单测覆盖」）。
**「抽 Policy」是一次独立改动**（会动 `RequestManager.kt` 与策略类），不宜在文档轮次里顺手做，建议单独立项。

### 11.4 本节结论

- 本计划的**代码与测试均已合入 `main`**（PR #3，含 10.6 / 10.7 的后续修复）；未完成的只有**实机 5 项**。
- 勾选未回填是历史事实，本节只作说明，**不回填勾选**。
- 本计划的权威状态以 `TODO.md` 正文为准。
