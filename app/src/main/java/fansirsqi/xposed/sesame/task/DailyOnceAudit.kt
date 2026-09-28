package fansirsqi.xposed.sesame.task

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 「每日一次」任务的完成台账。
 *
 * 为什么需要它：`Status.flagList` 里的当天标志**跨日会被整体清空**，
 * 第二天完全看不出「昨天有哪些任务被限制过、到底完成没有」。
 * 一旦某个任务被误判成「今天已完成」而跳过，它就会整天不再执行，且事后无从发现 ——
 * 这正是「限制了之后任务没彻底完成」的隐患。
 *
 * 所以每次**首次成功**都往 `config/<userId>/daily_once.json` 记一条，保留 7 天：
 * 第二天（或之后任意一天）都能逐条核对当天锁定的任务是否真的完成过，
 * 「今日完成」页面也会把当天的台账列出来。
 *
 * 落盘失败只记日志，绝不影响任务本身。
 */
object DailyOnceAudit {

    private const val TAG = "DailyOnceAudit"
    private const val FILE_NAME = "daily_once.json"

    /** 保留天数：够回溯一周，又不至于让文件无限膨胀 */
    const val KEEP_DAYS: Int = 7

    /** 台账里的一条记录 */
    data class Entry(
        val flag: String = "",
        /** 面向人的名字，如「庄园任务[秋叶月饼任务]」 */
        val label: String = "",
        /** 首次成功时间（毫秒） */
        val at: Long = 0
    )

    private val dayFormat: SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("GMT+8") }

    /** GMT+8 的自然日键，与 daily_tasks.json 保持一致 */
    @JvmStatic
    fun dayKey(now: Long): String = dayFormat.format(Date(now))

    /**
     * 把一条记录并入台账（纯逻辑，便于单测）。
     *
     * 同一天同一 [Entry.flag] 只保留**最早**那条：只有"今天第一次成功"才是锁定时刻。
     */
    @JvmStatic
    fun merge(
        existing: Map<String, List<Entry>>,
        day: String,
        entry: Entry
    ): Map<String, List<Entry>> {
        if (day.isEmpty() || entry.flag.isEmpty()) return existing
        val result = LinkedHashMap(existing)
        val list = (result[day] ?: emptyList()).toMutableList()
        val same = list.firstOrNull { it.flag == entry.flag }
        if (same == null) {
            list.add(entry)
        } else if (entry.at in 1 until same.at) {
            list[list.indexOf(same)] = entry
        }
        result[day] = list
        return trim(result)
    }

    /** 只保留最近 [keepDays] 天（按日期字符串倒序取前 N 个）。 */
    @JvmStatic
    fun trim(days: Map<String, List<Entry>>, keepDays: Int = KEEP_DAYS): Map<String, List<Entry>> {
        if (days.size <= keepDays) return days
        val kept = days.keys.sortedDescending().take(keepDays).toSortedSet()
        return LinkedHashMap(days.filterKeys { it in kept })
    }

    // ------------------------------------------------------------------ 落盘

    /** 记录一次「首次成功」。 */
    @JvmStatic
    @JvmOverloads
    fun record(userId: String?, flag: String, label: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val day = dayKey(now)
            val merged = merge(load(userId), day, Entry(flag = flag, label = label, at = now))
            save(userId, merged)
            Log.record(TAG, "📌 每日一次已锁定：$label | flag=$flag | day=$day")
        }.onFailure { Log.printStackTrace(TAG, "记录每日一次台账失败: $flag", it) }
    }

    /** 某天的台账（按首次成功时间升序）。 */
    @JvmStatic
    fun entriesOf(userId: String?, day: String): List<Entry> =
        runCatching { load(userId)[day].orEmpty().sortedBy { it.at } }.getOrDefault(emptyList())

    private fun load(userId: String?): Map<String, List<Entry>> {
        val file = Files.getTargetFileofUser(userId, FILE_NAME) ?: return emptyMap()
        val body = runCatching { Files.readFromFile(file) }.getOrNull()
        if (body.isNullOrEmpty()) return emptyMap()
        return runCatching {
            JsonUtil.parseObject(
                body,
                object : TypeReference<Map<String, List<Entry>>>() {}
            ) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun save(userId: String?, days: Map<String, List<Entry>>) {
        val file = Files.getTargetFileofUser(userId, FILE_NAME) ?: return
        Files.write2File(JsonUtil.formatJson(days), file)
    }

    /** 供界面使用：当天的自然日（便于与 json 里的键对齐） */
    @JvmStatic
    fun today(): String = dayKey(Calendar.getInstance().timeInMillis)
}
