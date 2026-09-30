package fansirsqi.xposed.sesame.model

/**
 * 重复失败的类别。**当前只影响日志/台账的措辞，不影响放弃阈值**（见 [RepeatFailurePolicy.DAILY_FAILURE_LIMIT]）。
 */
enum class RepeatFailureKind(val label: String) {
    /** 确定性失败：服务端明确拒绝（参数非法 / 条件未满足 / 契约不存在），重试没有意义 */
    DETERMINISTIC("确定性失败"),

    /** 服务端不可用或繁忙：可能是临时故障，达到上限前应继续尝试 */
    TRANSIENT("服务端不可用"),

    /** 未能判定：按"继续尝试"一侧处理（宁可多试，不要误放弃） */
    UNKNOWN("未判定"),
}

/**
 * 「同一目标当天失败到上限就停止尝试」的判定（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么需要
 *
 * 2026-09-30 两天的真机日志显示，有一批任务**每轮都失败、当天从未成功**：
 *
 * | 目标 | 当天失败次数 | 服务端返回 |
 * | --- | --- | --- |
 * | 信用2101 账户查询 | 39 | 返回为空或非 SUCCESS |
 * | 运动竞猜 participate | 39 | `error 3000 系统出错，正在排查` |
 * | 会员宝箱 triggerSignFloatingBall | 39 | `PARAM_ILLEGAL` / 系统繁忙 |
 * | 芝麻炼金 joinActivity | 32 | `生活记录模板不存在` |
 * | 学生签到 queryCheckInModel | 25 | Check failed |
 *
 * 它们占 K50 当天 error.log 的 89%（428/479 条），既是无效请求也是日志噪音。
 * 主任务轮询节奏决定了这些调用每天会被重复几十次，而**服务端的态度当天并没有变化**。
 *
 * ## 机制
 *
 * 同一目标（由调用方给出稳定 key）**当天累计失败达到 [DAILY_FAILURE_LIMIT] 次后不再调用**，
 * 静默跳过；跨天自动恢复（计数存在 `Status` 的当天字段里，随 `unload()` 清零）。
 *
 * ## ⚠️ 明确的限制（详见 `docs/failure-give-up.md`）
 *
 * 1. **阈值对两类失败是同一个值**（[DAILY_FAILURE_LIMIT]）：确定性失败与"服务端不可用"
 *    都在失败 5 次后停止 —— 也就是说"不可用类"**不是退避而是同样当天放弃**。
 *    代价：若服务端故障在当天晚些时候恢复，这次收益就丢了（换来的是几十次无效请求与噪音）。
 *    之所以不区分阈值，是因为"临时故障 vs 长期不可用"在客户端**无法可靠区分**
 *    （实测 `error 3000` 恰恰是全天 39/39 全失败），阈值分开只会制造"看起来更聪明其实更冒险"的假象。
 * 2. 计数器是"当天 + 按 key"，**不理解业务语义**：若某任务一天本该成功多次，
 *    调用方就不该接入本机制（见文档的接入清单）。
 * 3. 只统计"失败次数"，不统计"连续失败"：白天失败 4 次、晚上再失败 1 次也会触发放弃。
 */
object RepeatFailurePolicy {

    /** 同一目标当天允许的失败次数上限；达到即当天不再尝试。 */
    const val DAILY_FAILURE_LIMIT: Int = 5

    /**
     * 确定性失败的判据（**大小写不敏感**，命中任一即判为 [RepeatFailureKind.DETERMINISTIC]）。
     *
     * 取值来自真机日志里实际出现过的服务端回应；新增判据时请同步更新
     * `docs/failure-give-up.md` 的判定表与 `RepeatFailurePolicyTest`。
     */
    private val DETERMINISTIC_HINTS = listOf(
        "param_illegal",              // 参数非法（会员宝箱：PARAM_ILLEGAL）
        "生活记录模板不存在",           // 芝麻炼金：模板契约不存在
        "模板不存在",
        "任务还没有完成",              // 会员任务结算：条件未满足
        "返回为空或非 success",        // 信用2101 账户查询
        "不支持rpc完成",              // 明确声明不支持 RPC 完成的任务
        "quota_user_not_enough",      // 已达上限
        "i07", "i09",                 // 森林抽抽乐里已知的确定性错误码
    )

    /**
     * 服务端不可用/繁忙的判据（大小写不敏感）。
     *
     * ⚠️ 命中这些**同样**在达到上限后停止尝试，措辞标为"服务端不可用"以便次日核对。
     */
    private val TRANSIENT_HINTS = listOf(
        "系统繁忙", "繁忙",
        "系统出错",
        "error\":3000", "\"error\": 3000",
        "请稍后重试",
        "限流", "网络", "超时", "timed out",
    )

    /**
     * 归类一条失败回应。参数可以传错误码、`resultDesc`、整段响应文本等，
     * 命中顺序为：确定性 → 不可用 → 未判定。
     */
    fun kindOf(vararg hints: String?): RepeatFailureKind {
        val text = hints.filterNotNull().joinToString(" ").lowercase()
        if (text.isBlank()) return RepeatFailureKind.UNKNOWN
        if (DETERMINISTIC_HINTS.any { it.lowercase() in text }) return RepeatFailureKind.DETERMINISTIC
        if (TRANSIENT_HINTS.any { it.lowercase() in text }) return RepeatFailureKind.TRANSIENT
        return RepeatFailureKind.UNKNOWN
    }

    /** 今天该目标是否已经放弃（失败次数达到上限）。 */
    fun shouldGiveUp(failureCountToday: Int): Boolean = failureCountToday >= DAILY_FAILURE_LIMIT
}
