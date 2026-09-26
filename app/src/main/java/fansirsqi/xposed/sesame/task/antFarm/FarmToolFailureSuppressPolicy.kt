package fansirsqi.xposed.sesame.task.antFarm

/**
 * 道具使用失败后的退避策略（按道具维度）。
 *
 * 背景：加饭卡的失败日志 `⚠️使用道具🎭[加饭卡]失败…` 在 2026-09-26 一天出现 **44 次**。
 * 实机统计来源后确认**不是「同一轮内反复重试」**，而是每次调用各失败一次：
 *
 * | 来源 | 次数/天 | 说明 |
 * | --- | --- | --- |
 * | 蹲点投喂任务触发 | 31 | 独立子任务，按服务器喂食倒计时触发（不走 `runSuspend`） |
 * | 主任务轮次内 | 13 | 每个主轮次 1 次 |
 *
 * 所以「同轮抑制」抓不到真实模式 —— 每次都在不同的调用里。这里改为**按时间冷却**：
 * 某道具失败后 [cooldownMs] 内不再尝试，冷却按时间自愈。
 *
 * 另外失败的真实原因常常不是「卡片不足」：`useFarmTool` 会先调 `listFarmTool()`，
 * 该请求被风控拒绝时同样返回 false。冷却能顺带减少对已被拒接口的重复请求。
 *
 * **不落盘、不跨天**，冷却时长也刻意小于 30 分钟，符合「不写长时间暂停标记」的既有约定。
 */
internal class FarmToolFailureSuppressPolicy(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {

    /** 道具名 → 最近一次失败时刻。 */
    private val failedAtMs = mutableMapOf<String, Long>()

    /** 当前是否允许尝试该道具。 */
    @Synchronized
    fun shouldAttempt(toolKey: String, nowMs: Long): Boolean {
        val failedAt = failedAtMs[toolKey] ?: return true
        return nowMs - failedAt >= cooldownMs
    }

    /** 记录一次失败：该道具进入冷却。 */
    @Synchronized
    fun markFailed(toolKey: String, nowMs: Long) {
        failedAtMs[toolKey] = nowMs
    }

    /** 剩余冷却时长（毫秒），未在冷却中时为 0。 */
    @Synchronized
    fun remainingCooldownMs(toolKey: String, nowMs: Long): Long {
        val failedAt = failedAtMs[toolKey] ?: return 0L
        return maxOf(0L, cooldownMs - (nowMs - failedAt))
    }

    /** 当前处于冷却中的道具（诊断 / 测试用）。 */
    @Synchronized
    fun failedTools(nowMs: Long): Set<String> =
        failedAtMs.filterValues { nowMs - it < cooldownMs }.keys.toSet()

    companion object {
        /** 失败后的冷却时长：30 分钟。 */
        const val DEFAULT_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
