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
- **令牌精度下调。** `requireToken=true` 时令牌不匹配仍返回 false 且不清标志，但此时必有携带新令牌的通知在场，且 TTL 与跳过入口都能兜底，不再构成卡死路径。

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
