package fansirsqi.xposed.sesame.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHealthPolicyTest {

    private val now = 1_790_000_000_000L
    private val timeout5min = TaskHealthPolicy.stallTimeoutMs(5)

    private fun snap(
        state: TaskHealthState,
        startAt: Long = 0,
        progressAt: Long = 0,
        successAt: Long = 0,
        blocked: String = "",
        detail: String = "",
        failures: Int = 0
    ) = TaskHealthSnapshot(
        id = "forest.collect", label = "收能量", state = state,
        lastStartAt = startAt, lastProgressAt = progressAt, lastSuccessAt = successAt,
        blockedReason = blocked, detail = detail, consecutiveFailures = failures
    )

    @Test
    fun `未开始与已完成都按原状态返回`() {
        assertEquals(
            TaskHealthState.IDLE,
            TaskHealthPolicy.evaluate(snap(TaskHealthState.IDLE), now, timeout5min)
        )
        assertEquals(
            TaskHealthState.OK,
            TaskHealthPolicy.evaluate(
                snap(TaskHealthState.OK, progressAt = now - 60 * 60_000L),
                now, timeout5min
            )
        )
    }

    @Test
    fun `进行中且近期有进展就是进行中`() {
        val s = snap(TaskHealthState.RUNNING, startAt = now - 60_000L, progressAt = now - 10_000L)
        assertEquals(TaskHealthState.RUNNING, TaskHealthPolicy.evaluate(s, now, timeout5min))
    }

    @Test
    fun `进行中但超过阈值没有进展就是卡住`() {
        val s = snap(TaskHealthState.RUNNING, startAt = now - 30 * 60_000L, progressAt = now - 6 * 60_000L)
        assertEquals(TaskHealthState.STALLED, TaskHealthPolicy.evaluate(s, now, timeout5min))
    }

    @Test
    fun `等待中同样按无进展判定`() {
        val fresh = snap(TaskHealthState.WAITING, progressAt = now - 60_000L)
        assertEquals(TaskHealthState.WAITING, TaskHealthPolicy.evaluate(fresh, now, timeout5min))

        val stale = snap(TaskHealthState.WAITING, progressAt = now - 20 * 60_000L)
        assertEquals(TaskHealthState.STALLED, TaskHealthPolicy.evaluate(stale, now, timeout5min))
    }

    @Test
    fun `被暂停优先于卡住`() {
        val s = snap(
            TaskHealthState.RUNNING,
            progressAt = now - 30 * 60_000L,
            blocked = "离线中"
        )
        assertEquals(TaskHealthState.BLOCKED, TaskHealthPolicy.evaluate(s, now, timeout5min))
    }

    @Test
    fun `还没开始过的任务不会因为模块被暂停而显示为已暂停`() {
        val s = snap(TaskHealthState.IDLE, blocked = "离线中")
        assertEquals(TaskHealthState.IDLE, TaskHealthPolicy.evaluate(s, now, timeout5min))
    }

    @Test
    fun `阈值可配置且有上下限`() {
        assertEquals(1, TaskHealthPolicy.normalizeTimeoutMinutes(0))
        assertEquals(5, TaskHealthPolicy.normalizeTimeoutMinutes(5))
        assertEquals(120, TaskHealthPolicy.normalizeTimeoutMinutes(9999))
        assertEquals(5 * 60_000L, TaskHealthPolicy.stallTimeoutMs(5))
    }

    @Test
    fun `说明文案能说清卡住与上次成功`() {
        val s = snap(
            TaskHealthState.RUNNING,
            startAt = now - 30 * 60_000L,
            progressAt = now - 7 * 60_000L,
            successAt = now - 40 * 60_000L,
            detail = "收取 阿锐",
            failures = 2
        )
        val state = TaskHealthPolicy.evaluate(s, now, timeout5min)
        val text = TaskHealthPolicy.describe(s, state, now)
        assertTrue(text.contains("已 7 分钟无进展"))
        assertTrue(text.contains("上次成功"))
        assertTrue(text.contains("连续失败 2 次"))
        assertTrue(text.contains("收取 阿锐"))
    }

    @Test
    fun `未成功过时说明里点出来`() {
        val s = snap(TaskHealthState.RUNNING, startAt = now - 60_000L, progressAt = now - 60_000L)
        val text = TaskHealthPolicy.describe(s, TaskHealthState.RUNNING, now)
        assertTrue(text.contains("今天尚未成功过"))
        assertFalse(text.contains("上次成功"))
    }

    /** 取「base 那一天 ± dayOffset 天」的中午 12:00，避免踩到跨天边界 */
    private fun atNoon(dayOffset: Int, base: Long = now): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = base
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
        }
        return cal.timeInMillis
    }

    @Test
    fun `今天的记录属于今天`() {
        val s = snap(TaskHealthState.OK, startAt = atNoon(0), progressAt = atNoon(0))
        assertTrue(TaskHealthPolicy.belongsToToday(s, now))
    }

    @Test
    fun `昨天的记录不属于今天`() {
        val s = snap(TaskHealthState.OK, startAt = atNoon(-1), progressAt = atNoon(-1))
        assertFalse(TaskHealthPolicy.belongsToToday(s, now))
    }

    @Test
    fun `归属看的是最新的那个时间戳`() {
        // 三天前开始、但今天成功过 —— 仍然算今天的记录
        val s = snap(TaskHealthState.OK, startAt = atNoon(-3), successAt = atNoon(0))
        assertTrue(TaskHealthPolicy.belongsToToday(s, now))
    }

    @Test
    fun `全零记录按属于今天处理`() {
        assertTrue(TaskHealthPolicy.belongsToToday(snap(TaskHealthState.IDLE), now))
    }
}
