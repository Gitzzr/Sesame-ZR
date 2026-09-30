package fansirsqi.xposed.sesame.task

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务状态机：把「任务现在到底在不在正常跑」变成可看、可查的状态。
 *
 * 起因是几次排查都卡在同一件事上 —— 明明有能量却不动、日志里却看不出任务处于什么状态
 * （有一次静默停摆 52 分钟、另一次蹲点按 60 秒空转刷了 1700 行）。已有的观测手段都是**事后计数**
 * （`TaskRunCounter` 的逐次结果、`TaskStatisticsRecorder` 的当日汇总、`DailyTaskLogRecorder` 的完成台账），
 * 缺的是「此刻健康吗」。
 *
 * 设计要点：
 *
 * 1. **宿主进程写事件，界面现算状态**。任务跑在支付宝进程、界面在模块 App 进程，
 *    内存态跨不过去，所以只把**时间戳与计数**落盘（`config/<uid>/task_health.json`），
 *    状态由界面按 [TaskHealthPolicy.evaluate] 现算 —— 连「宿主进程被杀、文件停在 RUNNING」
 *    这种最典型的卡死也能显示成「已 N 分钟无进展」。
 * 2. **只观测，不干预**。这里不做任何重试、取消、重启 —— 判定错了最多是显示不准，
 *    不会把正常任务打断（`StallActionPolicy` 那类自动处置留给以后，且必须有用户开关）。
 * 3. **状态变化才写日志**，进展事件只更新内存并**节流落盘**，不给日志和存储添乱。
 */
object TaskHealthMonitor {

    private const val TAG = "TaskHealthMonitor"
    private const val FILE_NAME = "task_health.json"

    /** 同一状态下、进展事件的落盘最小间隔（毫秒）：避免按人/按行写文件 */
    private const val MIN_WRITE_INTERVAL_MS = 3_000L

    /** 森林主任务整体（一轮是否跑完） */
    const val ID_FOREST_MAIN = "forest.main"

    /** 收能量（自收 + 好友收取） */
    const val ID_COLLECT = "forest.collect"

    /** 蹲点收取（能量成熟等待与重试） */
    const val ID_WAITING = "forest.waiting"

    private val LABELS = mapOf(
        ID_FOREST_MAIN to "森林主任务",
        ID_COLLECT to "收能量",
        ID_WAITING to "蹲点收取"
    )

    /** 展示顺序固定，界面不用再排 */
    private val ORDER = listOf(ID_FOREST_MAIN, ID_COLLECT, ID_WAITING)

    private val tasks = ConcurrentHashMap<String, TaskHealthSnapshot>()
    private val lastWriteAt = AtomicLong(0L)

    /** 被离线/安全验证暂停的原因（全局），为空表示没被暂停 */
    @Volatile
    private var blockedReason: String = ""

    // ------------------------------------------------------------------ 事件入口

    /** 任务开始（进入进行中） */
    @JvmStatic
    @JvmOverloads
    fun onStart(id: String, detail: String = "") = update(id) { snap, now ->
        snap.copy(
            state = TaskHealthState.RUNNING,
            lastStartAt = now,
            lastProgressAt = now,
            detail = detail
        )
    }

    /** 有进展（还活着）；不改变「进行中/等待中」的区分 */
    @JvmStatic
    @JvmOverloads
    fun onProgress(id: String, detail: String = "") = update(id) { snap, now ->
        snap.copy(lastProgressAt = now, detail = detail.ifEmpty { snap.detail })
    }

    /**
     * 进入等待（例如等能量成熟/等蹲点时间到）——这是正常状态，不算卡住。
     *
     * @param expectedAt 预计该动作的时刻（毫秒）；>0 时判定会以它为基准（过了它才可能算卡住），
     *                   0 表示没有预计时间，退回"按无进展判定"。
     */
    @JvmStatic
    @JvmOverloads
    fun onWaiting(id: String, detail: String = "", expectedAt: Long = 0L) = update(id) { snap, now ->
        snap.copy(
            state = TaskHealthState.WAITING,
            lastProgressAt = now,
            detail = detail,
            expectedAt = expectedAt
        )
    }

    /** 成功 */
    @JvmStatic
    @JvmOverloads
    fun onSuccess(id: String, detail: String = "") = update(id) { snap, now ->
        snap.copy(
            state = TaskHealthState.OK,
            lastSuccessAt = now,
            lastProgressAt = now,
            consecutiveFailures = 0,
            detail = detail
        )
    }

    /** 失败（保留 detail 里的原因） */
    @JvmStatic
    @JvmOverloads
    fun onFailure(id: String, detail: String = "") = update(id) { snap, now ->
        snap.copy(
            state = TaskHealthState.FAILED,
            lastFailureAt = now,
            lastProgressAt = now,
            consecutiveFailures = snap.consecutiveFailures + 1,
            detail = detail
        )
    }

    /**
     * 整个模块被离线/安全验证暂停。
     *
     * 与「卡住」区分开：暂停是我们自己拦下的（等自愈或人工验证），不是任务出了问题。
     */
    @JvmStatic
    fun onBlocked(reason: String) {
        blockedReason = reason.ifEmpty { "已暂停" }
        persistNow()
    }

    /** 暂停解除 */
    @JvmStatic
    fun onBlockedCleared() {
        if (blockedReason.isEmpty()) return
        blockedReason = ""
        persistNow()
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 宿主进程内直接取（含现算状态），用于日志与测试。
     */
    @JvmStatic
    @JvmOverloads
    fun snapshots(now: Long = System.currentTimeMillis(), stallTimeoutMs: Long = -1L): List<TaskHealthSnapshot> {
        val timeout = if (stallTimeoutMs > 0) stallTimeoutMs
        else TaskHealthPolicy.stallTimeoutMs(TaskHealthPolicy.DEFAULT_STALL_TIMEOUT_MINUTES)
        return ordered().map { snap ->
            snap.copy(state = TaskHealthPolicy.evaluate(snap, now, timeout))
        }
    }

    /**
     * 给界面用：从 `config/<uid>/task_health.json` 读回宿主写入的快照并现算状态。
     *
     * @param stallTimeoutMinutes 无进展判定阈值（分钟），由界面传入设置项的值
     */
    @JvmStatic
    @JvmOverloads
    fun readAll(
        userId: String?,
        stallTimeoutMinutes: Int = TaskHealthPolicy.DEFAULT_STALL_TIMEOUT_MINUTES,
        now: Long = System.currentTimeMillis()
    ): List<TaskHealthSnapshot> {
        // 与宿主侧同一套口径：不是今天的一律不展示，否则跨过零点会看到昨天的「正常」
        val stored = readStored(userId).filter { TaskHealthPolicy.belongsToToday(it, now) }
        val timeout = TaskHealthPolicy.stallTimeoutMs(stallTimeoutMinutes)
        val byId = stored.associateBy { it.id }
        return ORDER.map { id ->
            val snap = byId[id] ?: TaskHealthSnapshot(
                id = id,
                label = LABELS[id] ?: id
            )
            val labeled = snap.copy(label = LABELS[snap.id] ?: snap.id.ifEmpty { id })
            labeled.copy(state = TaskHealthPolicy.evaluate(labeled, now, timeout))
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 状态机核心：先接续磁盘 → 改字段 → 状态变化打日志 → 节流落盘 */
    private inline fun update(id: String, mutate: (TaskHealthSnapshot, Long) -> TaskHealthSnapshot) {
        // 放在这里（而不是某个具体任务里）是刻意的：不管今天先跑的是哪一项，第一件事都是
        // 先把磁盘上属于**今天**的记录接过来，否则支付宝一重启（内存是空的）就会把
        // 已经跑完的状态抹成「未开始」—— 而「重启支付宝看看」正是最常用的排查动作
        val now = System.currentTimeMillis()
        syncDailyState(now)
        healStaleBlock()
        val before = tasks[id] ?: TaskHealthSnapshot(id = id, label = LABELS[id] ?: id)
        val after = mutate(before, now)
        tasks[id] = after

        if (before.state != after.state) {
            Log.record(
                TAG,
                "任务状态：${after.label} ${TaskHealthPolicy.labelOf(before.state)} → " +
                        "${TaskHealthPolicy.labelOf(after.state)}" +
                        if (after.detail.isNotEmpty()) "（${after.detail}）" else ""
            )
            persistNow()
            return
        }
        // 同状态下的进展事件：内存即时更新，落盘节流
        val last = lastWriteAt.get()
        if (now - last >= MIN_WRITE_INTERVAL_MS) persistNow()
    }

    /**
     * 按固定顺序取当前所有快照。
     *
     * `blockedReason` 是**全局**的（离线拦的是所有 RPC），但它只活在内存里 ——
     * 必须在这里给每一项带上再落盘，否则界面读文件时拿不到它，「已暂停」就永远显示不出来。
     */
    private fun ordered(): List<TaskHealthSnapshot> {
        val known = tasks.values.associateBy { it.id }
        return ORDER.map { id ->
            val snap = known[id] ?: TaskHealthSnapshot(id = id, label = LABELS[id] ?: id)
            // ⚠️ **始终**以全局值覆盖（为空就写空）。
            // 旧写法是"全局为空时保留快照自带的值"，于是解除离线后清不掉、留成永久残影 ——
            // 界面按「被暂停优先」显示「已暂停（离线中）」，而任务其实在跑（2026-10-01 实测）。
            snap.copy(blockedReason = blockedReason)
        }
    }

    /**
     * 找到要读的状态文件。
     *
     * 模块 App 进程里没有支付宝的登录态，`UserMap.currentUid` 是空的 —— 直接按 uid 拼路径
     * 会读到一个空文件，页面就永远显示「还没有记录」。这里在 uid 缺失时退回扫一遍
     * `config/` 下的账号目录，取**最近改过**的那份（多账号时只显示正在跑的那个），
     * 因此界面不依赖宿主进程的内存态。
     */
    private fun resolveHealthFile(userId: String?): File? {
        if (!userId.isNullOrEmpty()) return Files.getTargetFileofUser(userId, FILE_NAME)
        val root = Files.CONFIG_DIR
        val latest = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> File(dir, FILE_NAME).takeIf { f -> f.isFile && f.length() > 0 } }
            ?.maxByOrNull { it.lastModified() }
        return latest ?: Files.getTargetFileofUser(userId, FILE_NAME)
    }

    /**
     * 自愈：模块**在线**时不该留着「暂停原因」。
     *
     * 暂停原因会落盘并被跨进程接续，所以只靠「解除时清一次」不够稳（进程在暂停期间被杀、
     * 或接续路径变化都会留残影）。这里给一条不变量：**在线 ⇒ 没有任何暂停原因**。
     */
    private fun healStaleBlock() {
        if (!TaskHealthPolicy.shouldClearStaleBlock(blockedReason, ApplicationHook.offline)) return
        val stale = blockedReason
        blockedReason = ""
        Log.record(TAG, "已清除陈旧的暂停标记：$stale（当前并未离线）")
    }

    /** 立即落盘（状态变化、暂停标记变化时调用） */
    private fun persistNow() {
        val now = System.currentTimeMillis()
        lastWriteAt.set(now)
        syncDailyState(now)
        healStaleBlock()
        runCatching {
            val uid = UserMap.currentUid ?: return
            val file = Files.getTargetFileofUser(uid, FILE_NAME) ?: return
            Files.write2File(JsonUtil.formatJson(ordered()), file)
        }.onFailure { Log.printStackTrace(TAG, "写入任务状态失败", it) }
    }

    /** 仅供测试/排障：清空内存态 */
    @JvmStatic
    fun resetForTest() {
        tasks.clear()
        blockedReason = ""
        lastWriteAt.set(0L)
        loadedDay = ""
    }

    /**
     * 把磁盘上的记录接进内存，顺带完成跨天清痕。
     *
     * 两件必须同时成立的事：
     *
     * 1. **跨进程接续**：宿主进程一重启内存就是空的，直接落盘会把昨天/刚才跑出来的状态冲掉。
     *    而「重启支付宝看看」是最常用的排查动作，抹掉历史等于自断线索。
     * 2. **跨天清痕**：`Status.hasFlagToday` 那套 `flagList` 跨天会自己清，这里的文件不会，
     *    所以要以**记录里的最新时间戳**判定归属 —— 不是今天的丢弃，是今天的接着用。
     *
     * 同一个自然日内只会真正读一次盘，之后靠 `loadedDay` 短路。
     */
    @JvmStatic
    fun syncDailyState(now: Long = System.currentTimeMillis()) {
        val today = TaskHealthPolicy.dayKey(now)
        if (loadedDay == today) return
        loadedDay = today
        val kept = readStored(UserMap.currentUid).filter { TaskHealthPolicy.belongsToToday(it, now) }

        // 跨进程接续「暂停原因」：它写在每一项里（写盘时统一覆盖），重启后从盘上取回。
        // 取回前后都要过一遍自愈校验 —— 若此刻并不离线，说明是残影，宁可丢掉也不误报「已暂停」。
        if (blockedReason.isEmpty()) {
            val carried = kept.firstOrNull { it.blockedReason.isNotEmpty() }?.blockedReason.orEmpty()
            if (carried.isNotEmpty()) {
                if (TaskHealthPolicy.shouldClearStaleBlock(carried, ApplicationHook.offline)) {
                    Log.record(TAG, "丢弃陈旧的暂停标记：$carried（当前并未离线）")
                } else {
                    blockedReason = carried
                }
            }
        }

        tasks.clear()
        // 条目里的 blockedReason 只是"写盘时的快照"（写盘时统一覆盖），内存里不再自带，
        // 避免它成为第二个真相来源
        kept.forEach { snap -> tasks[snap.id] = snap.copy(label = LABELS[snap.id] ?: snap.id, blockedReason = "") }
    }

    /** 读回磁盘上已存的快照（读不出来就当没有，不影响主流程） */
    private fun readStored(userId: String?): List<TaskHealthSnapshot> = runCatching {
        val file = resolveHealthFile(userId) ?: return emptyList()
        val body = Files.readFromFile(file)
        if (body.isNullOrEmpty()) return emptyList()
        JsonUtil.parseObject(body, object : TypeReference<List<TaskHealthSnapshot>>() {})
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** `syncDailyState` 已经接续过的自然日；同一天内不再重复读盘 */
    private var loadedDay: String = ""
}
