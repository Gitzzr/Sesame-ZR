package fansirsqi.xposed.sesame.task.antForest

/**
 * 背包查询（`alipay.antforest.forest.h5.queryPropList`）的失败退避策略。
 *
 * 背景：该接口在触发风控时会被持续拒绝。2026-09-23 ~ 09-26 实测 **8552 次**
 * `[AntForest]: 刷新背包失败: 触发安全验证，请人工验证后继续`，其中一段是
 * **0.6 次/秒持续 40 分钟** —— 而这些请求并没有换来任何有效数据，只是持续暴露风控。
 *
 * 两件事分开：
 * - 「被拒的风控识别与熔断」由 `VerificationPausePolicy` + `RequestManager` 负责（归因问题）；
 * - **本策略负责「连续失败就别再问了」**：连续失败达阈值后进入冷却，
 *   冷却期内直接返回 null，调用方按既有逻辑跳过需要背包的道具 —— **收能量本身不受影响**。
 *
 * 冷却时长刻意小于 `VerificationPausePolicy.VERIFICATION_TTL_MS`（30 分钟），
 * 以满足「不得写入超过 30 分钟的暂停/标记」的既有约定；冷却按时间自愈，不落盘、不跨天。
 */
internal class PropQueryBackoffPolicy(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {

    private var consecutiveFailures = 0
    private var cooldownUntilMs = 0L

    /** 当前是否允许发起背包查询。 */
    @Synchronized
    fun canQuery(nowMs: Long): Boolean = nowMs >= cooldownUntilMs

    /**
     * 记录一次失败。
     *
     * @return 本次失败是否**刚刚**触发退避（用于只记录一条日志，避免冷却期内反复打日志）
     */
    @Synchronized
    fun recordFailure(nowMs: Long): Boolean {
        if (nowMs < cooldownUntilMs) {
            // 已在冷却中（正常路径会被 canQuery 挡住，这里兜底）
            return false
        }
        consecutiveFailures += 1
        if (consecutiveFailures < failureThreshold) {
            return false
        }
        consecutiveFailures = 0
        cooldownUntilMs = nowMs + cooldownMs
        return true
    }

    /** 记录一次成功：立即解除退避。 */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        cooldownUntilMs = 0L
    }

    /** 剩余冷却时长（毫秒），未在冷却中时为 0。 */
    @Synchronized
    fun remainingCooldownMs(nowMs: Long): Long = maxOf(0L, cooldownUntilMs - nowMs)

    companion object {
        /** 连续失败多少次后进入退避。 */
        const val DEFAULT_FAILURE_THRESHOLD = 2

        /** 退避时长：15 分钟（< 30 分钟 TTL 上界）。 */
        const val DEFAULT_COOLDOWN_MS = 15 * 60 * 1000L
    }
}
