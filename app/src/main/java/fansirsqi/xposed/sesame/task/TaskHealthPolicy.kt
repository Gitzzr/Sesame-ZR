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
    val detail: String = "",
    /**
     * **预计该动作的时刻**（毫秒），0 表示"没有预计时间"。
     *
     * 目前只有蹲点收取会填：它在等能量的成熟时刻（`calculatePreciseCollectTime`），
     * 等待期间**本来就不会有进展** —— 若按"多久没进展"判卡住，蹲点等得越久越像卡住
     * （2026-10-01 实测：等待 23 分钟后被判「卡住」，而它其实正常）。
     *
     * 它作为候选之一参与 [TaskHealthPolicy.aliveBaseOf] 的取最大值，
     * **不是**一条优先级更高的独立规则（那样会把预计时刻之后的上报全部吞掉）。
     */
    val expectedAt: Long = 0L
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

        val lastAliveAt = aliveBaseOf(snapshot)
        if (lastAliveAt <= 0L) return state
        return if (now - lastAliveAt > stallTimeoutMs) TaskHealthState.STALLED else state
    }

    /**
     * 「最后一次还活着的证据」时刻（毫秒）—— [evaluate] 与 [describe] 共用同一个基准，
     * 否则会出现「按进展判卡住、却按预计时刻算超时」的错配。
     *
     * 取三个候选里**最新**的那个：最近进展、最近开始、以及**预计动作时刻**（[TaskHealthSnapshot.expectedAt]）。
     *
     * expectedAt 混进来是有意为之：蹲点在等待期间本来就不上报，只看最近进展会「等得越久越像卡住」
     * （2026-10-01 实测误报 23 分钟）。
     *
     * 但它是**参与取最大值**，而不是单独成立一条优先级更高的规则 —— 这一点踩过坑：
     * 若写成"等待态有 expectedAt 就只看 expectedAt"，那么 expectedAt 一旦落到过去，
     * 之后的所有上报都被吞掉（蹲点收取失败后的 5 秒重试、`已终止`的上报），
     * 任务明明在跑却一直显示「卡住」，比改之前更糟。
     * 参与 maxOf 之后三种情形都对：
     *
     * - 预计时刻在**未来** → 基准是未来，永远判不出卡住（修掉上述误报）
     * - 预计时刻**已过且无后续动作** → 按"预计时刻 + 阈值"判卡住（保留这次的意图）
     * - 预计时刻**已过但期间有上报** → 基准被更近的上报顶掉，退回旧口径（不该判卡住就不判）
     *
     * 注：`expectedAt` 只在**等待态**参与取值（见实现里的门控）—— 等待态之外的任务
     * 不该因为快照里残留着一个旧的预计时刻而获得"未来才到期"的基准。
     */
    @JvmStatic
    fun aliveBaseOf(snapshot: TaskHealthSnapshot): Long {
        // expectedAt 只有**等待态**才认（目前也只有蹲点收取会上报它）。
        // 这道状态门控是刻意的：若某条记录上残留着旧的 expectedAt，
        // 一个正在进行中的任务会凭空拿到"未来才到期"的基准 → 永远判不出卡住。
        val expectedAt =
            if (snapshot.state == TaskHealthState.WAITING) snapshot.expectedAt else 0L
        return maxOf(snapshot.lastProgressAt, snapshot.lastStartAt, expectedAt)
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
        if (state == TaskHealthState.WAITING && snapshot.expectedAt > now) {
            parts.add("预计 ${timeOf(snapshot.expectedAt)} 收取")
        }
        if (state == TaskHealthState.STALLED) {
            // 与 evaluate 同一个基准（[aliveBaseOf]）：基准落在谁的头上，就按谁的口径描述
            val lastAliveAt = aliveBaseOf(snapshot)
            parts.add(
                if (snapshot.expectedAt > 0L && now > snapshot.expectedAt && lastAliveAt == snapshot.expectedAt) {
                    // 等预计时刻的那类：说"超时多久"比说"多久没进展"有意义
                    "已超预计收取时间 ${minutesOf(now - snapshot.expectedAt)} 分钟"
                } else {
                    "已 ${minutesOf(now - lastAliveAt)} 分钟无进展"
                }
            )
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

    /**
     * 是否应当把陈旧的「暂停原因」就地清掉。
     *
     * 背景（2026-10-01 实测）：暂停原因会被**写进每一项并落盘**，而磁盘是跨进程的。
     * 若解除离线时只清了内存里的全局值，盘上的「离线中」会在下次接续时被读回来 →
     * 界面按「被暂停优先」的规则永远显示「已暂停」，可任务其实在跑（切机重启后尤其明显）。
     *
     * 判据很直接：**只要模块此刻不在离线状态，就不该存在任何暂停原因**。
     *
     * ⚠️ **这条判据成立的前提是一条外部不变量**：「暂停原因」有且只有一个写入来源
     * [TaskHealthMonitor.onBlocked]，而它必然伴随"进入离线"（详见那里的 KDoc）。
     * 因此「此刻在暂停中」与「ApplicationHook.offline == true」当前是同一件事。
     *
     * **将来若新增一种不经过 `setOffline(true)` 的暂停**（例如只挡某个业务的开关），
     * 必须同步改动这里 —— 否则那种暂停会在上报后立刻被这条判据抹掉，
     * 界面永远显示不出来，日志里只剩一句「已清除陈旧的暂停标记」，极难反查。
     * 届时正确做法是给暂停原因带上来源标记、按同一来源判定是否还成立，
     * 而不是继续用"全局离线"这一个布尔量代指所有暂停。
     */
    @JvmStatic
    fun shouldClearStaleBlock(reason: String, offline: Boolean): Boolean =
        reason.isNotEmpty() && !offline

    private fun minutesOf(millis: Long): Long = (millis / 60_000L).coerceAtLeast(0L)

    private fun timeOf(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            java.util.Locale.US, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)
        )
    }
}
