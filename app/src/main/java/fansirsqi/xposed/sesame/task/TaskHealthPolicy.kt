package fansirsqi.xposed.sesame.task

/**
 * 任务健康状态。
 *
 * 命名刻意避开 `TaskStatus` —— 那个枚举是**支付宝自己的**任务状态（TODO/FINISHED/RECEIVED），
 * 两者语义完全不同，混用会看不懂。
 */
enum class TaskHealthState {
    /** 今天还没跑过 */
    IDLE,

    /** 正在跑（有进展） */
    RUNNING,

    /** 等待中（例如等能量成熟，属于正常状态） */
    WAITING,

    /** 最近一次成功 */
    OK,

    /** 最近一次失败 */
    FAILED,

    /** 被离线/安全验证暂停挡住（不是任务本身有问题） */
    BLOCKED,

    /** 进行中但长时间没有任何进展 —— 这才是真正要盯的「卡住」 */
    STALLED
}

/**
 * 一个受监测任务的健康快照。
 *
 * 时间戳全部由**宿主进程**（任务真正运行的地方）写入并落盘，
 * 状态则由界面按时间戳**现算**（见 [TaskHealthPolicy.evaluate]）——
 * 这样连「宿主进程被杀、状态文件停在 RUNNING」这种最典型的卡死也能如实反映出来。
 */
data class TaskHealthSnapshot(
    val id: String = "",
    val label: String = "",
    /** 宿主进程最后一次写入时认为的状态 */
    val state: TaskHealthState = TaskHealthState.IDLE,
    val lastStartAt: Long = 0L,
    val lastProgressAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    /** 非空表示被离线/安全验证暂停挡住 */
    val blockedReason: String = "",
    /** 最近一次进展的说明，如「收取 阿锐|*梓锐」「批次 3/8」 */
    val detail: String = ""
)

/**
 * 任务健康状态的判定规则（纯逻辑，可单测）。
 *
 * 「卡住」的口径：**进行中/等待中，但超过阈值时长没有任何进展**。
 * 只看「无进展」，不看「有没有失败」—— 失败会照常记 FAILED，
 * 而卡住的特征恰恰是「既没失败也没进展」，这类沉默的停摆最难发现（实测有一次静默 52 分钟）。
 */
object TaskHealthPolicy {

    /** 无进展多久算卡住（分钟）。可在全局设置里改 */
    const val DEFAULT_STALL_TIMEOUT_MINUTES = 5
    const val MIN_STALL_TIMEOUT_MINUTES = 1
    const val MAX_STALL_TIMEOUT_MINUTES = 120

    @JvmStatic
    fun normalizeTimeoutMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_STALL_TIMEOUT_MINUTES, MAX_STALL_TIMEOUT_MINUTES)

    @JvmStatic
    fun stallTimeoutMs(minutes: Int): Long = normalizeTimeoutMinutes(minutes) * 60_000L

    /**
     * 现算当前状态。
     *
     * 优先级：**被暂停 > 卡住 > 记录的状态**。
     * 被暂停时不能报「卡住」—— 那是离线/验证导致的，行动方案完全不同（前者等自愈、后者要人工验证）。
     *
     * @param stallTimeoutMs 无进展超时阈值
     */
    @JvmStatic
    fun evaluate(
        snapshot: TaskHealthSnapshot,
        now: Long,
        stallTimeoutMs: Long
    ): TaskHealthState {
        // 还没开始过的任务不该因为「模块被暂停」而显示成被暂停
        if (snapshot.blockedReason.isNotEmpty() && snapshot.state != TaskHealthState.IDLE) {
            return TaskHealthState.BLOCKED
        }
        val state = snapshot.state
        if (state != TaskHealthState.RUNNING && state != TaskHealthState.WAITING) return state

        val lastAliveAt = maxOf(snapshot.lastProgressAt, snapshot.lastStartAt)
        if (lastAliveAt <= 0L) return state
        return if (now - lastAliveAt > stallTimeoutMs) TaskHealthState.STALLED else state
    }

    /** 状态的中文短标签（界面与日志共用，避免两处各写一套） */
    @JvmStatic
    fun labelOf(state: TaskHealthState): String = when (state) {
        TaskHealthState.IDLE -> "未开始"
        TaskHealthState.RUNNING -> "进行中"
        TaskHealthState.WAITING -> "等待中"
        TaskHealthState.OK -> "正常"
        TaskHealthState.FAILED -> "失败"
        TaskHealthState.BLOCKED -> "已暂停"
        TaskHealthState.STALLED -> "卡住"
    }

    /**
     * 一句话说明，供界面与日志使用。
     *
     * @return 例如「已 7 分钟无进展 · 上次成功 12:05」；无可用信息时返回空串
     */
    @JvmStatic
    fun describe(snapshot: TaskHealthSnapshot, state: TaskHealthState, now: Long): String {
        val parts = mutableListOf<String>()
        if (state == TaskHealthState.BLOCKED && snapshot.blockedReason.isNotEmpty()) {
            parts.add(snapshot.blockedReason)
        }
        if (state == TaskHealthState.STALLED) {
            val lastAliveAt = maxOf(snapshot.lastProgressAt, snapshot.lastStartAt)
            parts.add("已 ${minutesOf(now - lastAliveAt)} 分钟无进展")
        }
        if (snapshot.detail.isNotEmpty()) parts.add(snapshot.detail)
        if (snapshot.lastSuccessAt > 0L) {
            parts.add("上次成功 ${timeOf(snapshot.lastSuccessAt)}")
        } else if (snapshot.lastStartAt > 0L) {
            parts.add("今天尚未成功过")
        }
        if (snapshot.consecutiveFailures > 0) {
            parts.add("连续失败 ${snapshot.consecutiveFailures} 次")
        }
        return parts.joinToString(" · ")
    }

    /**
     * 这条记录属不属于「今天」。
     *
     * 两个用途：宿主进程重启后把磁盘上属于今天的记录**接过来**继续用（否则会抹成「未开始」），
     * 以及界面跨过零点后不再展示昨天的「正常」。
     *
     * 判定用记录里**最新的那个时间戳**；全为 0 视为「还没跑过」，按属于今天处理
     * （界面上会显示成 IDLE，不会被误判成跨天残留）。
     */
    @JvmStatic
    @JvmOverloads
    fun belongsToToday(snapshot: TaskHealthSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        val latest = maxOf(
            snapshot.lastStartAt,
            snapshot.lastProgressAt,
            snapshot.lastSuccessAt,
            snapshot.lastFailureAt
        )
        if (latest <= 0L) return true
        return dayKey(latest) == dayKey(now)
    }

    /** 自然日键（yyyy-MM-dd），与日志/台账同一套口径（设备默认时区） */
    @JvmStatic
    @JvmOverloads
    fun dayKey(now: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun minutesOf(millis: Long): Long = (millis / 60_000L).coerceAtLeast(0L)

    private fun timeOf(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            java.util.Locale.US, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)
        )
    }
}
