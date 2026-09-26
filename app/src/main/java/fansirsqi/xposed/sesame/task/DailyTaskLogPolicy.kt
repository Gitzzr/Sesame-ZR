package fansirsqi.xposed.sesame.task

/**
 * 今日任务完成核对的记录结构与合并策略。
 *
 * 纯逻辑，无 IO、无 Android 依赖，可直接单元测试。
 * 落盘由 [DailyTaskLogRecorder] 负责，位置是
 * `sesame-TK/config/<userId>/daily_tasks.json`，与 statistics.json 同目录、按账号隔离。
 *
 * 与 statistics.json 的区别：那份只累计「一轮跑了多少任务」，
 * 这里记录具体业务事件——能量雨、能量赠送、任务奖励——用来回答
 * 「今天做了没、做了几次、送给谁、领了什么」。
 */

/** 一次能量雨结算。 */
data class EnergyRainEntry(
    /** 发生时间戳（毫秒） */
    val at: Long = 0,
    /** 本次收获的能量克数，失败时为 0 */
    val grams: Int = 0,
    val success: Boolean = false,
    /** 失败原因；成功时为 null */
    val reason: String? = null
)

/** 一次能量赠送：浇水、能量雨机会或道具，方向恒为「当前账号 → 对方」。 */
data class GiftEntry(
    val at: Long = 0,
    /** water / rainChance / prop */
    val kind: String = "",
    /** 接收方账号 */
    val targetUserId: String = "",
    /** 接收方显示名（掩码），便于直接阅读 */
    val targetName: String = "",
    /** 数量描述，如 "66g"、"1次机会"、道具名 */
    val amount: String = "",
    val success: Boolean = false,
    val reason: String? = null
)

/** 一次任务奖励领取，覆盖森林、庄园、海洋。 */
data class RewardEntry(
    val at: Long = 0,
    /** forest / farm / ocean */
    val module: String = "",
    /** 奖励或任务名称 */
    val title: String = "",
    /** 数量描述，如 "5活力值"、"3g"、"2拼图" */
    val detail: String = "",
    val success: Boolean = false,
    val reason: String? = null
)

/**
 * 单个账号当天的任务记录。
 *
 * 只保留一天：跨日后首次写入会丢弃前一天的内容，因此文件始终只反映「今天」。
 */
data class DailyTaskLog(
    val version: Int = DailyTaskLogPolicy.VERSION,
    val userId: String = "",
    /** 记录所属日期，yyyy-MM-dd（GMT+8） */
    val day: String = "",
    val energyRain: List<EnergyRainEntry> = emptyList(),
    val gifts: List<GiftEntry> = emptyList(),
    val rewards: List<RewardEntry> = emptyList(),
    val updatedAt: Long = 0
)

/** 赠送按接收方聚合后的计数，供汇总展示。 */
data class GiftTally(
    val kind: String,
    val targetUserId: String,
    val targetName: String,
    val successCount: Int,
    val failCount: Int
)

/** 一个预期项的核对结论。 */
data class ExpectedTaskStatus(
    /** 展示名，如「能量雨」「好友浇水」 */
    val name: String,
    /** 今日成功次数 */
    val successCount: Int,
    /** 今日失败次数 */
    val failCount: Int,
    /** 最近一次失败原因；没有失败时为 null */
    val lastFailReason: String?
) {
    /** 开启了开关、但今天既没成功也没失败。 */
    val missing: Boolean get() = successCount == 0 && failCount == 0
}

/**
 * 合并与汇总策略。
 *
 * 记录是追加的：同一天的新事件拼到列表末尾；日期变了就从空列表重新开始。
 */
object DailyTaskLogPolicy {
    const val VERSION: Int = 1

    const val GIFT_WATER: String = "water"
    const val GIFT_RAIN_CHANCE: String = "rainChance"
    const val GIFT_PROP: String = "prop"

    const val MODULE_FOREST: String = "forest"
    const val MODULE_FARM: String = "farm"
    const val MODULE_OCEAN: String = "ocean"

    /** 同一天沿用已有列表，跨日清空。 */
    private fun base(prev: DailyTaskLog?, userId: String, day: String): DailyTaskLog {
        return if (prev != null && prev.day == day) prev else DailyTaskLog(userId = userId, day = day)
    }

    fun addEnergyRain(
        prev: DailyTaskLog?,
        userId: String,
        day: String,
        entry: EnergyRainEntry,
        now: Long
    ): DailyTaskLog {
        val b = base(prev, userId, day)
        return b.copy(userId = userId, energyRain = b.energyRain + entry, updatedAt = now)
    }

    fun addGift(
        prev: DailyTaskLog?,
        userId: String,
        day: String,
        entry: GiftEntry,
        now: Long
    ): DailyTaskLog {
        val b = base(prev, userId, day)
        return b.copy(userId = userId, gifts = b.gifts + entry, updatedAt = now)
    }

    fun addReward(
        prev: DailyTaskLog?,
        userId: String,
        day: String,
        entry: RewardEntry,
        now: Long
    ): DailyTaskLog {
        val b = base(prev, userId, day)
        return b.copy(userId = userId, rewards = b.rewards + entry, updatedAt = now)
    }

    /** 赠送按「类型 + 接收方」分组计数，接收方为空的失败记录单独成组。 */
    fun giftTallies(log: DailyTaskLog): List<GiftTally> {
        return log.gifts
            .groupBy { it.kind to it.targetUserId }
            .map { (key, entries) ->
                GiftTally(
                    kind = key.first,
                    targetUserId = key.second,
                    targetName = entries.lastOrNull { it.targetName.isNotEmpty() }?.targetName ?: "",
                    successCount = entries.count { it.success },
                    failCount = entries.count { !it.success }
                )
            }
            .sortedWith(compareBy({ it.kind }, { it.targetName }))
    }

    /**
     * 核对一组预期任务。
     *
     * @param enabled 各预期项今天是否开了开关；只核对值为 true 的项
     * @param counter 由预期项名称算出 (成功次数, 失败次数, 最近失败原因)
     */
    fun expectedStatus(
        enabled: Map<String, Boolean>,
        counter: (name: String) -> Triple<Int, Int, String?>
    ): List<ExpectedTaskStatus> {
        return enabled
            .filterValues { it }
            .keys
            .map { name ->
                val (ok, bad, reason) = counter(name)
                ExpectedTaskStatus(name, ok, bad, reason)
            }
    }
}
