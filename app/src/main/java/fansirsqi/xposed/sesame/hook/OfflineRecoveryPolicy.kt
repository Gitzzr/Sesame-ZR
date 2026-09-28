package fansirsqi.xposed.sesame.hook

/**
 * 离线自愈的节奏策略。
 *
 * 背景（2026-09-28 小米 17 实测）：网络抖动触发 `46/48 当前网络不可用` → 连续失败到阈值 →
 * 模块进入离线；而离线期间**没有任何东西再去探测**（主任务见 offline 直接返回、蹲点又被门控拦下），
 * 于是从 19:08 一直静默到 19:59 才因为宿主重建而恢复，中间一个能量球都没收。
 *
 * 这里给出「进入离线后多久探测一次」与「最多打扰用户几次」的规则：
 * 探测是**轻量 RPC**（与正常轮次同一种请求，只是更稀疏），不会额外增加风控暴露面。
 */
object OfflineRecoveryPolicy {

    /** 第 n 次探测前的等待（毫秒）：2 分钟 → 5 → 15 → 30，之后固定 30 分钟 */
    private val PROBE_DELAYS_MS = longArrayOf(120_000L, 300_000L, 900_000L, 1_800_000L)

    /** 允许「重开支付宝到前台」的最大次数：一次离线周期内最多打扰用户这么多次 */
    const val MAX_REOPEN_ATTEMPTS: Int = 1

    /** 超过这个次数就不再提示「仍未恢复」，避免刷日志（探测本身继续，只是静默） */
    const val QUIET_AFTER_ATTEMPTS: Int = 5

    /**
     * 第 [attempt] 次探测前应等待多久（attempt 从 1 开始）。
     *
     * @return 毫秒。attempt ≤ 0 时按第一次算
     */
    @JvmStatic
    fun probeDelayMs(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, PROBE_DELAYS_MS.size - 1)
        return PROBE_DELAYS_MS[index]
    }

    /** 本次探测失败后是否要顺带把支付宝拉到前台（仅前 [MAX_REOPEN_ATTEMPTS] 次） */
    @JvmStatic
    fun shouldReopenApp(attempt: Int): Boolean = attempt <= MAX_REOPEN_ATTEMPTS

    /** 探测次数超过 [QUIET_AFTER_ATTEMPTS] 后不再逐次记日志 */
    @JvmStatic
    fun shouldLogProbe(attempt: Int): Boolean = attempt <= QUIET_AFTER_ATTEMPTS
}
