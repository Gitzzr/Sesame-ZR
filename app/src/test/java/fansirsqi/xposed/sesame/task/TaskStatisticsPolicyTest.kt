package fansirsqi.xposed.sesame.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计合并策略的单元测试 —— 纯逻辑，无 Android 依赖，可直接 JVM 运行。
 */
class TaskStatisticsPolicyTest {

    private fun run(
        timeMs: Long = 1000,
        completed: Int = 1,
        startedBackground: Int = 0,
        timedOut: Int = 0,
        skipped: Int = 0,
        failed: Int = 0
    ) = RunStatRecord(timeMs, completed, startedBackground, timedOut, skipped, failed)

    @Test
    fun `首次合并会初始化当日统计`() {
        val now = 1_700_000_000_000L

        val result = TaskStatisticsPolicy.mergeRun(
            prev = null,
            userId = "2088",
            dayKey = "2026-09-23",
            run = run(timeMs = 5000, completed = 3, failed = 1),
            now = now
        )

        assertEquals(TaskStatisticsPolicy.VERSION, result.version)
        assertEquals("2088", result.userId)
        assertEquals(now, result.updatedAt)

        val day = result.days["2026-09-23"]
        assertNotNull(day)
        assertEquals(1, day!!.runs)
        assertEquals(5000L, day.totalTimeMs)
        assertEquals(3, day.completed)
        assertEquals(1, day.failed)
        assertEquals(now, day.firstAt)
        assertEquals(now, day.lastAt)
    }

    @Test
    fun `同日多次执行会累加且保留首次时间`() {
        val t1 = 1_700_000_000_000L
        val t2 = t1 + 60_000

        val first = TaskStatisticsPolicy.mergeRun(
            prev = null, userId = "2088", dayKey = "2026-09-23",
            run = run(timeMs = 1000, completed = 2), now = t1
        )
        val second = TaskStatisticsPolicy.mergeRun(
            prev = first, userId = "2088", dayKey = "2026-09-23",
            run = run(timeMs = 2000, completed = 3, skipped = 1), now = t2
        )

        val day = second.days["2026-09-23"]!!
        assertEquals(2, day.runs)
        assertEquals(3000L, day.totalTimeMs)
        assertEquals(5, day.completed)
        assertEquals(1, day.skipped)
        assertEquals(t1, day.firstAt) // 首次时间保持不变
        assertEquals(t2, day.lastAt)  // 最后时间更新
        assertEquals(t2, second.updatedAt)
    }

    @Test
    fun `不同日期各自独立统计`() {
        val t = 1_700_000_000_000L

        val d1 = TaskStatisticsPolicy.mergeRun(null, "2088", "2026-09-22", run(completed = 2), t)
        val d2 = TaskStatisticsPolicy.mergeRun(d1, "2088", "2026-09-23", run(completed = 5), t)

        assertEquals(2, d2.days.size)
        assertEquals(2, d2.days["2026-09-22"]!!.completed)
        assertEquals(5, d2.days["2026-09-23"]!!.completed)
    }

    @Test
    fun `超出保留窗口时裁剪最旧日期`() {
        val days = (1..35).associate { i ->
            val key = "2026-08-${i.toString().padStart(2, '0')}"
            key to DayStatRecord(runs = 1)
        }

        val pruned = TaskStatisticsPolicy.prune(days, keep = 30)

        assertEquals(30, pruned.size)
        assertTrue(pruned.containsKey("2026-08-35"))
        assertTrue(pruned.containsKey("2026-08-06")) // 35 - 30 + 1
        assertTrue(!pruned.containsKey("2026-08-05"))
    }

    @Test
    fun `未超出保留窗口时不裁剪`() {
        val days = (1..10).associate { i ->
            "2026-09-${i.toString().padStart(2, '0')}" to DayStatRecord(runs = 1)
        }

        assertEquals(days, TaskStatisticsPolicy.prune(days, keep = 30))
    }

    @Test
    fun `合并时保留历史其它日期`() {
        val t = 1_700_000_000_000L
        val prev = TaskStatisticsRecord(
            userId = "2088",
            updatedAt = t - 1000,
            days = mapOf("2026-09-20" to DayStatRecord(runs = 4, completed = 9))
        )

        val result = TaskStatisticsPolicy.mergeRun(prev, "2088", "2026-09-23", run(completed = 1), t)

        assertEquals(4, result.days["2026-09-20"]!!.runs)
        assertEquals(9, result.days["2026-09-20"]!!.completed)
        assertEquals(1, result.days["2026-09-23"]!!.runs)
    }

    @Test
    fun `mergeRun 原样保留传入的账号`() {
        val result = TaskStatisticsPolicy.mergeRun(null, "", "2026-09-23", run(), 1L)
        assertEquals("", result.userId)
        assertEquals(1, result.days["2026-09-23"]!!.runs)
    }
}
