package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonHelper
import fansirsqi.xposed.sesame.util.Log
import java.io.File

/**
 * statistics.json 的读写编排（IO 层）。
 *
 * 纯合并逻辑在 [TaskStatisticsPolicy]；本类只负责
 * 「读文件 → 反序列化 → 交给 Policy 合并 → 序列化写回」这一段副作用。
 *
 * 存储位置：`.../sesame-TK/config/<userId>/statistics.json`，与 status.json 同目录、按账号隔离。
 */
object TaskStatisticsRecorder {
    private const val TAG = "TaskStatistics"

    /** 落盘文件名 */
    const val FILE_NAME: String = "statistics.json"

    /**
     * 记录一次任务执行的结果。
     *
     * **必须在非主线程调用**（内部为同步文件 IO）。
     *
     * @param userId 当前账号（为空则跳过）
     * @param dayKey 日期键，格式 yyyy-MM-dd
     * @param run    本次执行结果
     * @param now    写入时间戳
     */
    @Synchronized
    fun recordRun(
        userId: String?,
        dayKey: String,
        run: RunStatRecord,
        now: Long = System.currentTimeMillis()
    ) {
        if (userId.isNullOrEmpty()) {
            Log.record(TAG, "跳过统计落盘：当前账号为空")
            return
        }
        try {
            val file = Files.getTargetFileofUser(userId, FILE_NAME)
            if (file == null) {
                Log.error(TAG, "统计落盘失败：无法获取文件句柄")
                return
            }
            val merged = TaskStatisticsPolicy.mergeRun(read(file), userId, dayKey, run, now)
            if (Files.write2File(serialize(merged), file)) {
                val day = merged.days[dayKey]
                Log.record(
                    TAG,
                    "统计已落盘 [$dayKey] runs=${day?.runs} " +
                        "completed=${day?.completed} failed=${day?.failed} " +
                        "timedOut=${day?.timedOut} skipped=${day?.skipped}"
                )
            } else {
                Log.error(TAG, "统计落盘失败：$FILE_NAME 写入被拒")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "统计落盘异常", e)
        }
    }

    /**
     * 读取已有统计；文件不存在、为空或解析失败时返回 null（调用方按空统计重建）。
     */
    fun read(file: File): TaskStatisticsRecord? {
        val raw = Files.readFromFile(file)
        if (raw.isBlank()) return null
        return try {
            JsonHelper.fromJson(raw)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "统计文件解析失败，将按空统计重建", e)
            null
        }
    }

    /** 序列化为带缩进的 JSON，便于用户直接查看 */
    fun serialize(record: TaskStatisticsRecord): String {
        return JsonHelper.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record)
    }
}
