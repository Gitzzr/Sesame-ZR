package fansirsqi.xposed.sesame.task

/**
 * 单次任务执行（一轮 [CoroutineTaskRunner.run]）的结构化结果。
 *
 * 对应原先只以文本形式打印的「📈 === 执行统计 (并发模式) ===」，
 * 这里把它变成可落盘、可分析的结构化数据。
 */
data class RunStatRecord(
    /** 本次执行总耗时（毫秒） */
    val timeMs: Long = 0,
    /** 正常完成的任务数 */
    val completed: Int = 0,
    /** 后台启动（未等待完成）的任务数 */
    val startedBackground: Int = 0,
    /** 超时终止的任务数 */
    val timedOut: Int = 0,
    /** 被跳过的任务数（离线 / 被过滤 / 仍在运行，合并计数） */
    val skipped: Int = 0,
    /** 异常结束的任务数 */
    val failed: Int = 0
) {
    companion object {
        /** 由轮次计数器快照构造一条执行记录 */
        fun from(snapshot: TaskRunSnapshot, timeMs: Long): RunStatRecord = RunStatRecord(
            timeMs = timeMs,
            completed = snapshot.completed,
            startedBackground = snapshot.startedBackground,
            timedOut = snapshot.timedOut,
            skipped = snapshot.skipped,
            failed = snapshot.failed
        )
    }
}

/**
 * 单个自然日（yyyy-MM-dd）的累计统计。
 *
 * 一天内可能执行多轮任务，这里把每轮的结果累加。
 */
data class DayStatRecord(
    /** 当日执行轮次 */
    val runs: Int = 0,
    /** 当日累计耗时（毫秒） */
    val totalTimeMs: Long = 0,
    val completed: Int = 0,
    val startedBackground: Int = 0,
    val timedOut: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 当日首轮执行的完成时间戳 */
    val firstAt: Long = 0,
    /** 当日最后一轮执行的完成时间戳 */
    val lastAt: Long = 0
)

/**
 * statistics.json 的根结构 —— 按账号一份，跨日累积。
 *
 * 存储位置：`.../sesame-TK/config/<userId>/statistics.json`
 */
data class TaskStatisticsRecord(
    /** 结构版本，便于后续迁移 */
    val version: Int = TaskStatisticsPolicy.VERSION,
    val userId: String = "",
    /** 最近一次写入时间戳 */
    val updatedAt: Long = 0,
    /** 日期(yyyy-MM-dd) -> 当日统计 */
    val days: Map<String, DayStatRecord> = emptyMap()
)

/**
 * 统计合并策略 —— 纯逻辑，无 IO、无 Android 依赖，可直接单元测试。
 *
 * 职责单一：把「一次执行的结果」合并进「按账号、按日累积的统计结构」，
 * 并裁剪掉超出保留窗口的历史日期。
 */
object TaskStatisticsPolicy {
    /** 当前结构版本 */
    const val VERSION: Int = 1

    /** 保留的最近天数（超出的历史日期会被裁剪） */
    const val KEEP_DAYS: Int = 30

    /**
     * 把一次执行结果合并进统计结构。
     *
     * @param prev   已有统计（首次为 null）
     * @param userId 当前账号
     * @param dayKey 日期键，格式 yyyy-MM-dd
     * @param run    本次执行结果
     * @param now    本次写入时间戳（作为 updatedAt / lastAt，首次时也作为 firstAt）
     */
    fun mergeRun(
        prev: TaskStatisticsRecord?,
        userId: String,
        dayKey: String,
        run: RunStatRecord,
        now: Long
    ): TaskStatisticsRecord {
        val base = prev ?: TaskStatisticsRecord(userId = userId)
        val old = base.days[dayKey] ?: DayStatRecord()

        val day = DayStatRecord(
            runs = old.runs + 1,
            totalTimeMs = old.totalTimeMs + run.timeMs,
            completed = old.completed + run.completed,
            startedBackground = old.startedBackground + run.startedBackground,
            timedOut = old.timedOut + run.timedOut,
            skipped = old.skipped + run.skipped,
            failed = old.failed + run.failed,
            firstAt = if (old.runs == 0) now else old.firstAt,
            lastAt = now
        )

        val merged = base.days.toMutableMap().apply { this[dayKey] = day }
        return TaskStatisticsRecord(
            version = VERSION,
            userId = userId,
            updatedAt = now,
            days = prune(merged, KEEP_DAYS)
        )
    }

    /**
     * 只保留最近 [keep] 天的记录。
     *
     * 日期键是 yyyy-MM-dd，字典序等价于时间序，按 key 降序取前 keep 条即可。
     */
    fun prune(days: Map<String, DayStatRecord>, keep: Int = KEEP_DAYS): Map<String, DayStatRecord> {
        if (keep <= 0 || days.size <= keep) return days
        return days.entries
            .sortedByDescending { it.key }
            .take(keep)
            .associate { it.key to it.value }
    }
}
