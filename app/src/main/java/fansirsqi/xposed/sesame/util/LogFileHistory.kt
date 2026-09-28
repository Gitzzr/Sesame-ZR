package fansirsqi.xposed.sesame.util

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * 日志文件的历史枚举与解析（纯逻辑，无 Android 依赖，可直接 JVM 单测）。
 *
 * 背景：Logback 用的是 `SizeAndTimeBasedRollingPolicy`，当天写 `log/<name>.log`，
 * 滚动出来的历史分片写 `log/bak/<name>-<yyyy-MM-dd>.<i>.log`，保留 7 天。
 * 所以「看历史某一天的日志」不需要新的落盘机制，只需要把盘上的分片按天正确地找出来。
 *
 * 两个必须守住的点：
 * 1. `%i` 是**同一天内的分片序号**，排序必须按数值 —— 字典序下 `.9` 会排到 `.60` 之后；
 * 2. 一天通常是**多个分片**（实测 `error-2026-09-24` 有 62 个），所以「一天」= 一组文件。
 */
object LogFileHistory {

    /** 一天里的一个分区（分片或活动文件）。[shardIndex] 为 -1 表示当天的活动文件。 */
    data class Partition(val file: File, val shardIndex: Int)

    /** 从 bak 文件名解析出的信息 */
    data class ParsedShard(val logName: String, val date: String, val index: Int)

    /** `record-2026-09-24.58.log` 形态；logName 允许含点以外的任意字符 */
    private val SHARD_NAME = Regex("""^(.+)-(\d{4}-\d{2}-\d{2})\.(\d+)\.log$""")

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 与 Logback 的 `%d{yyyy-MM-dd}` 同源：都取 JVM 默认时区，避免跨时区差一天 */
    private fun zone(): ZoneId = ZoneId.of(TimeZone.getDefault().id)

    /** 自然日键（yyyy-MM-dd） */
    @JvmStatic
    @JvmOverloads
    fun dayKey(now: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().format(DAY_FORMAT)

    @JvmStatic
    @JvmOverloads
    fun isToday(date: String, now: Long = System.currentTimeMillis()): Boolean =
        date == dayKey(now)

    /**
     * 解析 bak 分片文件名。
     *
     * @param expectLogName 非空时要求前缀一致，否则返回 null（用于按 logName 过滤）
     * @return 解析失败（不是分片名、日期非法）返回 null
     */
    @JvmStatic
    @JvmOverloads
    fun parseShardName(fileName: String, expectLogName: String? = null): ParsedShard? {
        val m = SHARD_NAME.matchEntire(fileName) ?: return null
        val logName = m.groupValues[1]
        val date = m.groupValues[2]
        val index = m.groupValues[3].toIntOrNull() ?: return null
        if (expectLogName != null && logName != expectLogName) return null
        if (!isValidDate(date)) return null
        return ParsedShard(logName, date, index)
    }

    /** 严格校验 yyyy-MM-dd，拒绝 `2026-13-40` 这类改过名的文件 */
    private fun isValidDate(date: String): Boolean =
        runCatching { LocalDate.parse(date, DAY_FORMAT) }.isSuccess

    /** bak 目录（可能不存在） */
    @JvmStatic
    fun bakDirOf(logDir: File): File = File(logDir, "bak")

    /**
     * 某 logName 有数据的日期，倒序（今天在最前）。
     *
     * 只列**盘上真实存在**的日期：保留策略本身保证 ≤7 天，按实际文件列比硬编码窗口更稳。
     * 今天在「活动文件存在」或「已有今天的分片」时计入。
     */
    @JvmStatic
    @JvmOverloads
    fun availableDates(
        logDir: File,
        logName: String,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        val dates = sortedSetOf<String>()
        runCatching {
            bakDirOf(logDir).listFiles()?.forEach { f ->
                parseShardName(f.name, logName)?.let { dates.add(it.date) }
            }
        }
        val today = dayKey(now)
        if (partitionsOf(logDir, logName, today, now).isNotEmpty()) dates.add(today)
        return dates.sortedDescending()
    }

    /**
     * 某 logName 某天的分区，**按读取顺序**（时间升序）排列：
     * bak 分片按 [ParsedShard.index] **数值**升序，活动文件排在最后（它是最新的）。
     */
    @JvmStatic
    @JvmOverloads
    fun partitionsOf(
        logDir: File,
        logName: String,
        date: String,
        now: Long = System.currentTimeMillis()
    ): List<Partition> {
        val shards = runCatching {
            bakDirOf(logDir).listFiles().orEmpty()
                .mapNotNull { f ->
                    parseShardName(f.name, logName)
                        ?.takeIf { it.date == date }
                        ?.let { Partition(f, it.index) }
                }
                .sortedBy { it.shardIndex }   // 数值排序，不能用文件名字典序
        }.getOrDefault(emptyList())

        val active = File(logDir, "$logName.log")
        val parts = shards.toMutableList()
        if (date == dayKey(now) && active.exists()) parts.add(Partition(active, -1))
        return parts
    }

    /**
     * 找一个**可读且含该 logName 日志**的日志目录。
     *
     * 必须兼容 Logback 的回退逻辑（`Logback.resolveLogDir`）：`Files.LOG_DIR` 不可写时，
     * 日志会落到 `context.getExternalFilesDir("logs")` 或 `filesDir/logs`，
     * 此时按 `Files.LOG_DIR` 枚举历史会一个文件都找不到。
     *
     * @param candidates 按优先级排列的候选目录（调用方提供，含回退路径）
     */
    @JvmStatic
    fun resolveReadableLogDir(candidates: List<File>, logName: String): File? =
        candidates.firstOrNull { dir ->
            if (!dir.isDirectory || !dir.canRead()) return@firstOrNull false
            val hasActive = File(dir, "$logName.log").exists()
            val hasShard = runCatching {
                bakDirOf(dir).listFiles()?.any { parseShardName(it.name, logName) != null } == true
            }.getOrDefault(false)
            hasActive || hasShard
        }
}
