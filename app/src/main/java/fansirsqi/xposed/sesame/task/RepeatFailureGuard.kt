package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.model.RepeatFailureKind
import fansirsqi.xposed.sesame.model.RepeatFailurePolicy
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap

/**
 * 「同一目标当天失败到上限就停止尝试」的接线点。
 *
 * 判定逻辑在纯策略 [RepeatFailurePolicy]（有契约测试）；这里只负责三件事：
 * 读写 `Status` 的当天计数、在**恰好达到上限**的那一刻播报一次、写一条每日台账。
 *
 * 用法（调用方）：
 * ```kotlin
 * if (RepeatFailureGuard.shouldSkipToday(KEY)) return          // 调用前：今天已放弃就静默跳过
 * val resp = RpcCall.xxx()
 * if (!ResChecker.checkRes(TAG, resp)) {
 *     RepeatFailureGuard.recordFailure(KEY, "信用2101 账户查询", resp)   // 记失败 + 可能播报
 * }
 * ```
 *
 * ⚠️ 限制见 `docs/failure-give-up.md`：阈值对"确定性失败"与"服务端不可用"是同一个值，
 * 即不可用类同样是**当天放弃**而不是退避；接入前请确认该目标"一天最多成功一次"。
 */
object RepeatFailureGuard {
    private const val TAG = "RepeatFailureGuard"

    /**
     * 今天该目标是否已放弃（失败次数达到 [RepeatFailurePolicy.DAILY_FAILURE_LIMIT]）。
     * 调用方命中时**静默跳过**即可 —— 播报已经在达到上限那一刻做过一次。
     */
    @JvmStatic
    fun shouldSkipToday(key: String): Boolean =
        RepeatFailurePolicy.shouldGiveUp(Status.getFailureCountToday(key))

    /**
     * 记一次失败。
     *
     * @param key         稳定标识（建议 `模块::动作`，如 `credit2101::queryAccountAsset`）
     * @param displayName 给用户看的名字（台账与日志用，避免出现裸 uid / 类名）
     * @param hints       用于归类失败类别的原始文本（错误码 / resultDesc / 整段响应）
     * @return 是否**恰好**在本次达到上限（调用方一般不需要用它，播报已在这里完成）
     */
    @JvmStatic
    fun recordFailure(key: String, displayName: String, vararg hints: String?): Boolean {
        // ⚠️ 安全验证响应与"空响应"占位**不计入预算**：它们不是业务失败，
        // 而验证是"暂停 + TTL 自愈"、离线是"自愈后应重试"。若把它们算进去，
        // 几轮之后就会写出"该功能今天不再尝试"——项目硬规则 3 明确列为禁忌。
        if (!RepeatFailurePolicy.isCountable(*hints)) return false

        val count = try {
            Status.markFailureToday(key)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "记录失败次数失败: $key", t)
            return false
        }
        // 只在"恰好达到上限"那一次播报，避免每轮重复写日志（这正是要收敛的噪音）
        if (count != RepeatFailurePolicy.DAILY_FAILURE_LIMIT) return false

        val kind: RepeatFailureKind = RepeatFailurePolicy.kindOf(*hints)
        val label = "$displayName[今日失败${count}次已停止尝试·${kind.label}]"
        Log.record(TAG, label)
        try {
            DailyOnceAudit.record(
                UserMap.currentUid,
                "fail::" + key,
                "失败放弃：" + label,
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "写失败放弃台账失败: $key", t)
        }
        return true
    }
}
