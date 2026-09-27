package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonHelper
import fansirsqi.xposed.sesame.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * daily_tasks.json 的读写编排（IO 层）。
 *
 * 纯合并逻辑在 [DailyTaskLogPolicy]；本类只负责
 * 「读文件 → 反序列化 → 交给 Policy 追加 → 序列化写回」。
 *
 * 存储位置：`.../sesame-TK/config/<userId>/daily_tasks.json`，与 statistics.json 同目录、按账号隔离。
 * 任何写入失败只记日志，不向外抛，避免统计影响任务本身。
 */
object DailyTaskLogRecorder {
    private const val TAG = "DailyTaskLog"

    /** 落盘文件名 */
    const val FILE_NAME: String = "daily_tasks.json"

    /** 记录一次能量雨结算。发送方即 [userId]。 */
    @Synchronized
    fun recordEnergyRain(userId: String?, entry: EnergyRainEntry, now: Long = System.currentTimeMillis()) {
        update(userId, now) { prev, day -> DailyTaskLogPolicy.addEnergyRain(prev, userId!!, day, entry, now) }
    }

    /** 记录一次能量赠送（浇水 / 能量雨机会 / 道具）。 */
    @Synchronized
    fun recordGift(userId: String?, entry: GiftEntry, now: Long = System.currentTimeMillis()) {
        update(userId, now) { prev, day -> DailyTaskLogPolicy.addGift(prev, userId!!, day, entry, now) }
    }

    /** 记录一次有日次数上限的动作（任务、打卡、道具使用、道具兑换）。 */
    @Synchronized
    fun recordAction(userId: String?, entry: ActionEntry, now: Long = System.currentTimeMillis()) {
        update(userId, now) { prev, day -> DailyTaskLogPolicy.addAction(prev, userId!!, day, entry, now) }
    }

    /** 记录一次任务奖励领取（森林 / 庄园 / 海洋）。 */
    @Synchronized
    fun recordReward(userId: String?, entry: RewardEntry, now: Long = System.currentTimeMillis()) {
        update(userId, now) { prev, day -> DailyTaskLogPolicy.addReward(prev, userId!!, day, entry, now) }
    }

    /** 读取当前账号今天的记录；没有或解析失败时返回空记录。 */
    fun readToday(userId: String?, now: Long = System.currentTimeMillis()): DailyTaskLog {
        val day = dayKey(now)
        if (userId.isNullOrEmpty()) return DailyTaskLog(day = day)
        val file = Files.getTargetFileofUser(userId, FILE_NAME) ?: return DailyTaskLog(userId = userId, day = day)
        val loaded = read(file)
        return if (loaded != null && loaded.day == day) loaded else DailyTaskLog(userId = userId, day = day)
    }

    /**
     * 读盘 → 合并 → 写回的公共骨架。
     *
     * 任何一步失败都只记日志：统计落盘不应影响任务本身的执行。
     *
     * @param merge 把已有记录与 [day]（自然日键）合成新记录，由 [DailyTaskLogPolicy] 提供
     */
    private fun update(userId: String?, now: Long, merge: (DailyTaskLog?, String) -> DailyTaskLog) {
        if (userId.isNullOrEmpty()) {
            Log.record(TAG, "跳过今日任务落盘：当前账号为空")
            return
        }
        try {
            val file = Files.getTargetFileofUser(userId, FILE_NAME)
            if (file == null) {
                Log.error(TAG, "今日任务落盘失败：无法获取文件句柄")
                return
            }
            val merged = merge(read(file), dayKey(now))
            if (!Files.write2File(serialize(merged), file)) {
                Log.error(TAG, "今日任务落盘失败：$FILE_NAME 写入被拒")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "今日任务落盘异常", e)
        }
    }

    /** 读取已有记录；文件不存在、为空或解析失败时返回 null（调用方按空记录重建）。 */
    fun read(file: File): DailyTaskLog? {
        val raw = Files.readFromFile(file)
        if (raw.isBlank()) return null
        return try {
            JsonHelper.fromJson(raw)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "今日任务记录解析失败，将按空记录重建", e)
            null
        }
    }

    /** 序列化为带缩进的 JSON，便于人工排查。 */
    fun serialize(log: DailyTaskLog): String {
        return JsonHelper.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(log)
    }

    /**
     * 记录所属的自然日，固定用 GMT+8，避免设备时区不同导致「今天」错位。
     *
     * 不复用 [fansirsqi.xposed.sesame.util.TimeUtil.getDateStr]：它不接受时间戳，
     * 且格式是带中文的本地化日期，不能做跨日比较。
     */
    fun dayKey(now: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        return format.format(Date(now))
    }
}
