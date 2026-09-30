package fansirsqi.xposed.sesame.task.antFarm

/** 抽抽乐「拉一次列表 → 做完再拉一次」循环的下一步动作。 */
internal enum class ChouChouLeLoopAction {
    /** 本轮任务状态确有推进，继续下一轮 */
    CONTINUE,

    /** 本轮没有任何成功调用（任务都不可做 / 服务端报错）→ 停止，与原行为一致 */
    STOP_NO_ACTION,

    /** 有成功调用，但任务状态与剩余次数完全没变 → 服务端"收下但不认账"，停止 */
    STOP_NO_PROGRESS,

    /** 达到轮次上限 → 停止（兜底） */
    STOP_MAX_ROUNDS
}

/**
 * 抽抽乐循环的退出判定。
 *
 * 背景（2026-09-30 小米17 实测）：原实现是 `do { ... } while (doubleCheck)`，
 * 而 `doubleCheck` 只表示「这一轮有 RPC 返回成功」，**不代表任务状态或剩余次数有推进**。
 * 当天任务「游戏充值得10次机会」一直回成功但状态不变，于是循环以 **1 次/秒** 跑了 8.4 小时
 * （同一句日志刷了 19,120 行、横跨 504 个分钟），期间只抽到 1 次奖 —— 纯空转，
 * 也是当天最大的请求量来源（估算 ≥3.8 万次 RPC）。
 *
 * 判定优先级：
 * 1. 本轮没有任何成功调用 → [ChouChouLeLoopAction.STOP_NO_ACTION]；
 * 2. 任务快照与上一轮完全相同 → [ChouChouLeLoopAction.STOP_NO_PROGRESS]（治本）；
 * 3. 轮次达到 [MAX_ROUNDS] → [ChouChouLeLoopAction.STOP_MAX_ROUNDS]（兜底，
 *    防止快照来回抖动导致的长循环）。
 */
internal object ChouChouLeLoopPolicy {

    /** 单次执行最多几轮。正常一轮就能做完所有任务，上限只作兜底。 */
    const val MAX_ROUNDS: Int = 5

    fun actionFor(
        round: Int,
        previousSignature: String?,
        currentSignature: String,
        hadSuccessfulAction: Boolean
    ): ChouChouLeLoopAction {
        if (!hadSuccessfulAction) return ChouChouLeLoopAction.STOP_NO_ACTION
        if (previousSignature != null && previousSignature == currentSignature) {
            return ChouChouLeLoopAction.STOP_NO_PROGRESS
        }
        if (round >= MAX_ROUNDS) return ChouChouLeLoopAction.STOP_MAX_ROUNDS
        return ChouChouLeLoopAction.CONTINUE
    }

    /**
     * 任务快照签名：只取「任务在做什么 + 还剩多少次」，与任务顺序无关。
     *
     * 用它可以判断"这一轮到底有没有真的推进" —— 只要服务端把某个任务的状态或剩余次数改了，
     * 签名就会变。
     */
    fun signatureOf(entries: Iterable<String>): String = entries.sorted().joinToString("|")
}
