package fansirsqi.xposed.sesame.task.antFarm

/**
 * 「同一轮内某道具使用失败过就不再重试」的判定。
 *
 * 背景：加饭卡的每日上限只统计**成功**次数（DataStore 计数），失败不计数，
 * 于是「卡片不足 / 状态异常」时一轮内会被反复重试 —— 实测四天出现 99 条失败日志
 * （`⚠️使用道具🎭[加饭卡]失败，可能卡片不足或状态异常~`）。
 *
 * 这里按轮次记录失败过的道具，同一轮内不再尝试；轮次切换时清空。
 * **不落盘、不跨天**，符合「安全/异常不写跨天暂停标记」的既有约定。
 *
 * 与 `AntForestShieldRetryPolicy` 同一思路（同轮去重 + 轮次重置），只是键换成道具名。
 */
internal class FarmToolFailureSuppressPolicy {

    private val failedThisRound = mutableSetOf<String>()

    /** 本轮是否还允许尝试该道具。 */
    @Synchronized
    fun shouldAttempt(toolKey: String): Boolean = toolKey !in failedThisRound

    /** 记录一次失败：本轮不再尝试该道具。 */
    @Synchronized
    fun markFailed(toolKey: String) {
        failedThisRound.add(toolKey)
    }

    /** 进入新一轮，清空记录。 */
    @Synchronized
    fun startRound() {
        failedThisRound.clear()
    }

    /** 本轮已失败的道具（诊断 / 测试用）。 */
    @Synchronized
    fun failedTools(): Set<String> = failedThisRound.toSet()
}
