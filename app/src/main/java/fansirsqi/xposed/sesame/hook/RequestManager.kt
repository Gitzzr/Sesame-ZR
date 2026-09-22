package fansirsqi.xposed.sesame.hook

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import fansirsqi.xposed.sesame.entity.RpcEntity
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.CoroutineUtils
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.NetworkUtils
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import java.util.UUID

/**
 * RPC 请求管理器 (带熔断与兜底机制)
 */
object RequestManager {

    private const val TAG = "RequestManager"
    const val EMPTY_RPC_RESPONSE =
        """{"success":false,"resultCode":"EMPTY_RPC_RESPONSE","resultDesc":"RPC返回为空"}"""
    const val VERIFICATION_REQUIRED_RESPONSE =
        """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}"""

    private const val VERIFICATION_PREFS = "sesame_rpc_verification"

    /** 记录标志写入时间戳的后缀，用于超时自愈。 */
    private const val KEY_MARKED_AT_SUFFIX = "_at"

    /** 记录触发风控的 RPC 方法所对应入口的后缀，用于提示用户去哪个页面找验证页。 */
    private const val KEY_HINT_SUFFIX = "_hint"

    private const val REQUEST_CODE_RESUME = 109
    private const val REQUEST_CODE_SKIP = 110

    private const val TITLE_VERIFICATION_PAUSE = "自动任务已暂停"

    private val recoveryPolicy = RpcRecoveryPolicy()
    private var resumeDialogVisible = false

    private fun verificationPrefs() = ApplicationHook.appContext
        ?.getSharedPreferences(VERIFICATION_PREFS, Context.MODE_PRIVATE)

    /**
     * 通知文案。
     *
     * 必须点明去哪个页面找验证页：支付宝的安全验证页与触发它的业务场景绑定，
     * 只写「去支付宝完成验证」会让用户在错误的页面里找不到任何入口。
     */
    private fun notificationText(hint: String?): String {
        val scene = if (hint == null) "支付宝" else "「$hint」"
        return "在$scene 触发风控。请打开支付宝进入$scene 完成验证；若没有验证页，点「跳过并恢复」"
    }

    private fun dialogText(hint: String?): String {
        val sceneLine = if (hint == null) "" else "触发场景：「$hint」\n\n"
        return "检测到支付宝风控拦截，自动任务已暂停。\n\n" +
            sceneLine +
            "· 验证页与该场景绑定：请打开支付宝并进入上述页面，查看是否出现安全验证；" +
            "完成验证后点「已验证，恢复任务」\n" +
            "· 若该页面没有任何验证入口：点「跳过并恢复」即可继续自动任务"
    }

    @JvmStatic
    fun isVerificationPaused(): Boolean = recoveryPolicy.blockReason == RpcBlockReason.VERIFICATION

    @JvmStatic
    fun isEmptyRpcResponse(result: String?): Boolean {
        return result.isNullOrBlank()
    }

    /**
     * 判断服务端响应是否要求人工安全验证。
     *
     * 判定收敛在 [VerificationPausePolicy]：只认风控文案与专用码。
     * 像 `1009` 这类通用业务拒绝码不再单独判定 —— 否则任务会被无谓暂停，
     * 而支付宝根本不会弹出验证页，用户无从操作。
     */
    @JvmStatic
    fun isVerificationRequired(errorCode: String?, errorMessage: String?): Boolean {
        if (VerificationPausePolicy.requiresVerification(errorCode, errorMessage)) {
            return true
        }
        if (errorCode == VerificationPausePolicy.BUSINESS_REJECT_CODE) {
            Log.record(
                TAG,
                "收到 ${errorCode}（业务拒绝），未判定为安全验证 | msg=${errorMessage.orEmpty()}"
            )
        }
        return false
    }

    @JvmStatic
    fun handleVerificationRequired(method: String?, errorCode: String?, errorMessage: String?) {
        Log.record(TAG, "触发安全验证 | method=$method | code=$errorCode | msg=$errorMessage")
        ApplicationHook.setOffline(true)
        if (recoveryPolicy.onVerificationRequired() != RecoveryDecision.WAIT_FOR_MANUAL_VERIFICATION) {
            return
        }

        val hint = VerificationPausePolicy.entryHintFor(method)
        UserMap.currentUid?.let { uid -> markVerification(uid, hint) }
        ModelTask.stopAllTask()
        Log.record(TAG, "检测到安全验证，暂停后续RPC请求: $method | 建议入口: ${hint ?: "未知"}")
        notifyVerificationPause()
    }

    private fun markVerification(uid: String, hint: String?) {
        verificationPrefs()?.edit()
            ?.putString(uid, UUID.randomUUID().toString())
            ?.putLong(uid + KEY_MARKED_AT_SUFFIX, System.currentTimeMillis())
            ?.putString(uid + KEY_HINT_SUFFIX, hint.orEmpty())
            ?.commit()
    }

    private fun clearVerification(uid: String) {
        verificationPrefs()?.edit()
            ?.remove(uid)
            ?.remove(uid + KEY_MARKED_AT_SUFFIX)
            ?.remove(uid + KEY_HINT_SUFFIX)
            ?.commit()
    }

    private fun readHint(uid: String): String? =
        verificationPrefs()?.getString(uid + KEY_HINT_SUFFIX, null)?.takeIf { it.isNotEmpty() }

    private fun resumeIntent(context: Context, uid: String): Intent? {
        val token = verificationPrefs()?.getString(uid, null) ?: return null
        return Intent(ApplicationHook.BroadcastActions.RESUME_VERIFIED)
            .setPackage(context.packageName)
            .putExtra(ApplicationHook.BroadcastActions.EXTRA_USER_ID, uid)
            .putExtra(ApplicationHook.BroadcastActions.EXTRA_VERIFICATION_TOKEN, token)
    }

    private fun skipIntent(context: Context, uid: String): Intent? {
        val token = verificationPrefs()?.getString(uid, null)
        return Intent(ApplicationHook.BroadcastActions.SKIP_VERIFICATION)
            .setPackage(context.packageName)
            .putExtra(ApplicationHook.BroadcastActions.EXTRA_USER_ID, uid)
            .putExtra(ApplicationHook.BroadcastActions.EXTRA_VERIFICATION_TOKEN, token.orEmpty())
    }

    private fun notifyVerificationPause() {
        val context = ApplicationHook.appContext ?: return
        val uid = UserMap.currentUid ?: return
        val resume = resumeIntent(context, uid) ?: return
        val resumeAction = PendingIntent.getBroadcast(
            context, REQUEST_CODE_RESUME,
            resume, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skip = skipIntent(context, uid)
        val skipAction = if (skip != null) {
            PendingIntent.getBroadcast(
                context, REQUEST_CODE_SKIP,
                skip, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }
        Notify.sendNewNotification(
            TITLE_VERIFICATION_PAUSE,
            notificationText(readHint(uid)),
            resumeAction,
            skipAction
        )
    }

    fun showVerificationResumeDialog(activity: Activity) {
        if (!isVerificationPaused() || resumeDialogVisible || activity.isFinishing || activity.isDestroyed) return
        val uid = UserMap.currentUid ?: return
        val resume = resumeIntent(activity, uid) ?: return
        val skip = skipIntent(activity, uid)
        resumeDialogVisible = true
        AlertDialog.Builder(activity)
            .setTitle(TITLE_VERIFICATION_PAUSE)
            .setMessage(dialogText(readHint(uid)))
            .setNegativeButton("保持暂停", null)
            .setNeutralButton("跳过并恢复") { _, _ ->
                skip?.let { activity.sendBroadcast(it) }
            }
            .setPositiveButton("已验证，恢复任务") { _, _ ->
                activity.sendBroadcast(resume)
            }
            .setOnDismissListener { resumeDialogVisible = false }
            .show()
    }

    /** 「已验证，恢复任务」入口：校验一次性令牌。 */
    @JvmStatic
    fun resumeAfterManualVerification(intent: Intent): Boolean =
        resumePausedTasks(intent, requireToken = true)

    /** 「跳过并恢复」入口：不校验令牌，给误判场景留一条自救通路。 */
    @JvmStatic
    fun forceResumeAfterVerification(intent: Intent): Boolean =
        resumePausedTasks(intent, requireToken = false)

    /**
     * 解除安全验证暂停。
     *
     * [requireToken] 为 true 时代表用户从「已验证」入口触发，需令牌匹配；
     * 为 false 时代表用户主动「跳过并恢复」。
     *
     * 关键约束：只要账户一致就清除暂停标志，**任何校验分支都不得把标志留在原地** ——
     * 旧实现正是在各早退分支里 `return false` 而不清标志，导致状态永久卡死、
     * 每次启动支付宝都被重新暂停，用户只能卸载支付宝才能恢复。
     */
    @Synchronized
    private fun resumePausedTasks(intent: Intent, requireToken: Boolean): Boolean {
        val uid = UserMap.currentUid ?: return false
        val intentUid = intent.getStringExtra(ApplicationHook.BroadcastActions.EXTRA_USER_ID)
        if (intentUid != null && intentUid != uid) return false

        val expected = verificationPrefs()?.getString(uid, null)
        val tokenMatched =
            intent.getStringExtra(ApplicationHook.BroadcastActions.EXTRA_VERIFICATION_TOKEN) == expected
        if (requireToken && !tokenMatched) return false
        if (expected == null && !isVerificationPaused()) {
            Log.record(TAG, "当前未处于安全验证暂停，忽略恢复指令")
            return false
        }

        Log.record(TAG, "收到恢复指令（跳过验证=${!requireToken}，令牌匹配=$tokenMatched），等待已暂停任务退出")
        kotlinx.coroutines.runBlocking { ModelTask.stopAllTaskAndJoin() }
        if (UserMap.currentUid != uid) return false

        clearVerification(uid)
        recoveryPolicy.reset()
        ApplicationHook.setOffline(false)
        Log.record(TAG, "已解除安全验证暂停，恢复自动任务")
        return true
    }

    /**
     * 核心执行函数 (内联优化)
     * 流程：离线检查 -> 获取 Bridge -> 执行请求 -> 结果校验 -> 错误计数/重置
     */
    private inline fun executeRpc(methodLog: String?, block: (RpcBridge) -> String?): String {
        // 1. 【前置检查】如果已经离线，直接中断并尝试恢复
        if (ApplicationHook.offline) {
            recoveryPolicy.handleExternalOffline(::handleOfflineRecovery)
            return blockedResponse()
        }

        // 2. 获取 Bridge (包含网络检查)
        // 如果这里获取失败，也视为一次错误
        val bridge = getRpcBridge()
        if (bridge == null) {
            handleFailure("Network/Bridge Unavailable", "网络或Bridge不可用")
            return EMPTY_RPC_RESPONSE
        }

        // 3. 执行请求
        val result = try {
            block(bridge)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "RPC 执行异常: $methodLog", e)
            null // 异常视为 null，触发失败逻辑
        }

        // 在途请求可能在验证后才结束，不能按普通空响应处理。
        if (ApplicationHook.offline) return blockedResponse()

        // 4. 结果校验与状态维护
        if (isEmptyRpcResponse(result)) {
            // 失败：增加计数，检查兜底
            handleFailure(methodLog ?: "Unknown", "返回数据为空")
            return EMPTY_RPC_RESPONSE
        }

        if (result == VERIFICATION_REQUIRED_RESPONSE || ApplicationHook.offline) {
            return blockedResponse()
        }

        val hadFailure = recoveryPolicy.failureCount > 0 ||
            recoveryPolicy.blockReason != RpcBlockReason.NONE
        recoveryPolicy.onSuccess()
        if (isVerificationPaused()) return blockedResponse()
        if (hadFailure) {
            Log.record(TAG, "RPC 恢复正常，错误计数重置")
        }
        return result.orEmpty()
    }

    /**
     * 处理失败逻辑：计数、报警、熔断
     */
    private fun handleFailure(method: String, reason: String) {
        val maxCount = BaseModel.setMaxErrorCount.value
        val decision = recoveryPolicy.onNetworkFailure(maxCount)
        val currentCount = recoveryPolicy.failureCount

        Log.error(TAG, "RPC 失败 ($currentCount/$maxCount) | Method: $method | Reason: $reason")

        if (decision == RecoveryDecision.SCHEDULE_REOPEN) {
            Log.record(TAG, "🔴 连续失败次数达到阈值，触发熔断兜底机制！")
            ApplicationHook.setOffline(true)
            if (BaseModel.errNotify.value) {
                val msg = "${TimeUtil.getTimeStr()} | 网络异常次数超过阈值[$maxCount]"
                Notify.sendNewNotification(msg, "RPC 连续失败，脚本已暂停")
            }
            handleOfflineRecovery()
        }
    }

    private fun blockedResponse(): String {
        recoveryPolicy.onBlockedRequest()
        return if (recoveryPolicy.blockReason == RpcBlockReason.VERIFICATION) {
            VERIFICATION_REQUIRED_RESPONSE
        } else {
            EMPTY_RPC_RESPONSE
        }
    }

    /**
     * 桥接就绪时恢复暂停状态。
     *
     * 暂停标志按账号持久化在宿主私有存储里，进程重启后需要重建；但标志必须带超时，
     * 否则一次误判就会让用户每次打开支付宝都被暂停，且模块侧没有清除入口。
     */
    @JvmStatic
    fun onRpcBridgeReady() {
        val uid = UserMap.currentUid
        recoveryPolicy.reset()
        if (uid == null || verificationPrefs()?.contains(uid) != true) return

        val markedAt = verificationPrefs()?.getLong(uid + KEY_MARKED_AT_SUFFIX, 0L) ?: 0L
        if (VerificationPausePolicy.isMarkExpired(markedAt, System.currentTimeMillis())) {
            clearVerification(uid)
            Log.record(TAG, "安全验证暂停标志已超时或来自旧版本，自动解除")
            return
        }

        recoveryPolicy.onVerificationRequired()
        ApplicationHook.setOffline(true)
        Log.record(TAG, "保留安全验证暂停状态，等待用户确认恢复")
        notifyVerificationPause()
    }

    /**
     * 处理离线恢复逻辑
     * 可以是发送广播、拉起 App 等
     */
    private fun handleOfflineRecovery() {
        // 防止短时间内频繁触发恢复逻辑 (可选)
        // 这里简单实现：尝试拉起支付宝或发送重登录广播

        Log.record(TAG, "正在尝试执行离线恢复策略...")
        // 策略 A: 重新拉起 App (推荐)
        ApplicationHook.reOpenApp()
        // 策略 B: 发送重登录广播 (如果宿主还能响应广播)
        // ApplicationHook.reLoginByBroadcast()
    }

    /**
     * 获取 RpcBridge 实例
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getRpcBridge(): RpcBridge? {
        if (!NetworkUtils.isNetworkAvailable()) {
            Log.record(TAG, "网络不可用，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            if (!NetworkUtils.isNetworkAvailable()) {
                return null
            }
        }

        var bridge = ApplicationHook.rpcBridge
        if (bridge == null) {
            Log.record(TAG, "RpcBridge 未初始化，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            bridge = ApplicationHook.rpcBridge
        }

        return bridge
    }

    // ================== 公开 API (保持不变) ==================

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity): String {
        return executeRpc(rpcEntity.methodName) { bridge ->
            bridge.requestString(rpcEntity, 3, 1200)
        }
    }

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): String {
        return executeRpc(rpcEntity.methodName) { bridge ->
            bridge.requestString(rpcEntity, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, relation: String?): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, relation)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        appName: String?,
        methodName: String?,
        facadeName: String?
    ): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, appName, methodName, facadeName)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, tryCount: Int, retryInterval: Int): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        relation: String?,
        tryCount: Int,
        retryInterval: Int
    ): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, relation, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestObject(rpcEntity: RpcEntity?, tryCount: Int, retryInterval: Int) {
        if (rpcEntity == null) return
        // requestObject 不涉及返回值判断，但同样需要离线检查
        if (ApplicationHook.offline) {
            recoveryPolicy.handleExternalOffline(::handleOfflineRecovery)
            return
        }

        val bridge = getRpcBridge()
        if (bridge == null) {
            handleFailure("requestObject", "Bridge Unavailable")
            return
        }

        try {
            bridge.requestObject(rpcEntity, tryCount, retryInterval)
            recoveryPolicy.onRequestCompletedWithoutResponse()
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "requestObject 异常: ${rpcEntity.methodName}", e)
            handleFailure(rpcEntity.methodName ?: "Unknown", "Exception")
        }
    }
}
