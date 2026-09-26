package fansirsqi.xposed.sesame.util

/**
 * Toast 节流策略（纯逻辑，无 Android 依赖；时间由调用方注入，便于单测）。
 *
 * 背景：蚂蚁森林「找能量」每收一个能量球都会弹一条 Toast。Android 的 Toast 配额是**按包**统计的，
 * 超过配额后系统 `NotificationService` 会直接丢弃，日志里表现为
 * `Package ... is above allowed toast quota, the following toast was blocked and discarded`。
 * 2026-09-26 15:23 起实测 12.5 分钟内被丢弃 397 条（31.6 条/分钟），
 * 用户侧感知就是「同一个内容反复弹」。
 *
 * 这里做两级抑制：
 * 1. **同文本去重**：相同文案在 [DEDUPE_WINDOW_MS] 内只放行一次；
 * 2. **滑动窗口限流**：任意 [RATE_WINDOW_MS] 窗口内最多放行 [RATE_LIMIT] 条，留出余量给宿主自身的 Toast。
 *
 * 说明：被抑制的文案**不需要在这里补日志** —— 自动化路径的 Toast 内容已由调用方
 * （如 `AntForest.collectEnergy` 的 `Log.forest(...)`）记录，弹窗侧再记一遍只会制造新的日志噪声。
 * 抑制条数可通过 [suppressedCount] 读取。
 */
internal class ToastThrottlePolicy(
    private val dedupeWindowMs: Long = DEDUPE_WINDOW_MS,
    private val rateWindowMs: Long = RATE_WINDOW_MS,
    private val rateLimit: Int = RATE_LIMIT
) {
    /** 最近放行的时间戳，用于滑动窗口限流。 */
    private val recentAllowedAt = ArrayDeque<Long>()

    private var lastMessage: String? = null
    private var lastMessageAt: Long = Long.MIN_VALUE
    private var suppressed = 0

    /**
     * 是否允许显示这条 Toast。
     *
     * @param nowMs 当前时间（毫秒）
     * @param message 已拼接前缀的最终文案；为 null 时不放行
     */
    @Synchronized
    fun shouldShow(nowMs: Long, message: String?): Boolean {
        val text = message ?: return false

        // 1) 同文本去重
        if (text == lastMessage && nowMs - lastMessageAt < dedupeWindowMs) {
            suppressed++
            return false
        }

        // 2) 滑动窗口限流（先淘汰过期记录）
        while (recentAllowedAt.isNotEmpty() && nowMs - recentAllowedAt.first() >= rateWindowMs) {
            recentAllowedAt.removeFirst()
        }
        if (recentAllowedAt.size >= rateLimit) {
            suppressed++
            return false
        }

        // 3) 放行并记录
        recentAllowedAt.addLast(nowMs)
        lastMessage = text
        lastMessageAt = nowMs
        return true
    }

    /** 已抑制的条数（诊断用）。 */
    @Synchronized
    fun suppressedCount(): Int = suppressed

    /** 清空全部状态（切换用户 / 手动重置时调用）。 */
    @Synchronized
    fun reset() {
        recentAllowedAt.clear()
        lastMessage = null
        lastMessageAt = Long.MIN_VALUE
        suppressed = 0
    }

    companion object {
        /** 相同文案的去重窗口。 */
        const val DEDUPE_WINDOW_MS = 2_000L
        /** 限流窗口长度。 */
        const val RATE_WINDOW_MS = 30_000L
        /** 单个窗口内最多放行的条数。 */
        const val RATE_LIMIT = 6

        /**
         * 全局共享实例。
         *
         * 系统 Toast 配额按包统计，因此 `hook.Toast`（自动化路径）与 `ToastUtil`（界面路径）
         * 必须共用同一把尺子，否则两边各自限流、加起来仍会超配额。
         */
        val shared = ToastThrottlePolicy()
    }
}
