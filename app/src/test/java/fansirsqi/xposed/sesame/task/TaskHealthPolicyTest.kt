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
        failures: Int = 0,
        expectedAt: Long = 0
    ) = TaskHealthSnapshot(
        id = "forest.waiting", label = "蹲点收取", state = state,
        lastStartAt = startAt, lastProgressAt = progressAt, lastSuccessAt = successAt,
        blockedReason = blocked, detail = detail, consecutiveFailures = failures,
        expectedAt = expectedAt
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
    fun `等预计时刻的蹲点不会因为久无进展被判卡住`() {
        // 2026-10-01 实测：蹲点等待 23 分钟被判「卡住」，而它在等能量成熟 —— 是误报。
        val waitingLong = snap(
            TaskHealthState.WAITING,
            progressAt = now - 60 * 60_000L,      // 一小时没进展
            expectedAt = now + 30 * 60_000L,      // 但预计 30 分钟后才该动作
        )
        assertEquals(TaskHealthState.WAITING, TaskHealthPolicy.evaluate(waitingLong, now, timeout5min))
    }

    @Test
    fun `过了预计时刻但在宽限内仍算等待`() {
        // ⚠️ progressAt 故意放到 30 分钟前：这样只有"expectedAt 参与取基准"时才会得到 WAITING。
        // 若省略它，旧逻辑会在 lastAliveAt<=0 处直接 return WAITING，改不改都能过 —— 那是假断言。
        val justPassed = snap(
            TaskHealthState.WAITING,
            progressAt = now - 30 * 60_000L,
            expectedAt = now - 2 * 60_000L
        )
        assertEquals(TaskHealthState.WAITING, TaskHealthPolicy.evaluate(justPassed, now, timeout5min))
    }

    @Test
    fun `预计时刻已过但期间还有上报就不算卡住`() {
        // 回归防护：第一版把 expectedAt 写成"优先级更高的独立规则"，
        // expectedAt 一过期，之后的上报全被吞掉 —— 蹲点收取失败后每 5 秒一次的重试、
        // 「已终止」的上报都不顶用，任务明明在跑却稳定显示「卡住」，比改之前更糟。
        // 现在 expectedAt 只是"参与取最新"，被更近的上报顶掉就退回旧口径。
        val retrying = snap(
            TaskHealthState.WAITING,
            progressAt = now - 30_000L,       // 30 秒前刚重试过
            expectedAt = now - 20 * 60_000L   // 预计时刻已过 20 分钟
        )
        assertEquals(TaskHealthState.WAITING, TaskHealthPolicy.evaluate(retrying, now, timeout5min))
    }

    @Test
    fun `进行中不会被残留的预计时刻豁免卡住判定`() {
        // expectedAt 只有等待态才认：否则一条残留着"未来预计时刻"的记录，
        // 会让一个真在跑、却 20 分钟没进展的任务永远判不出卡住。
        val s = snap(
            TaskHealthState.RUNNING,
            progressAt = now - 20 * 60_000L,
            expectedAt = now + 30 * 60_000L
        )
        assertEquals(TaskHealthState.STALLED, TaskHealthPolicy.evaluate(s, now, timeout5min))
    }

    @Test
    fun `过了预计时刻且超出宽限才算卡住`() {
        val overdue = snap(TaskHealthState.WAITING, expectedAt = now - 6 * 60_000L)
        assertEquals(TaskHealthState.STALLED, TaskHealthPolicy.evaluate(overdue, now, timeout5min))
    }

    @Test
    fun `没有预计时刻的等待仍按无进展判定`() {
        // 兼容历史记录与其它来源的等待态：expectedAt=0 → 退回旧逻辑
        val stale = snap(TaskHealthState.WAITING, progressAt = now - 20 * 60_000L)
        assertEquals(TaskHealthState.STALLED, TaskHealthPolicy.evaluate(stale, now, timeout5min))
    }

    @Test
    fun `等待中的说明会给出预计收取时间`() {
        val s = snap(
            TaskHealthState.WAITING,
            detail = "7 个待收 · [某某]",
            expectedAt = now + 10 * 60_000L,
        )
        val text = TaskHealthPolicy.describe(s, TaskHealthPolicy.evaluate(s, now, timeout5min), now)
        // 断言到具体格式而不是只含「预计」：只断言关键词时，格式被改坏（少个冒号、多个字）也照样通过
        assertTrue(
            "应给出「预计 HH:MM 收取」，实际：$text",
            Regex("预计 \\d{2}:\\d{2} 收取").containsMatchIn(text)
        )
    }

    @Test
    fun `卡住时的说明与判定用的是同一个基准`() {
        // 预计时刻虽已过，但之后还有更近的上报 → 基准应是那条上报，
        // 不能出现「按 9 分钟判卡住、却按预计时刻说超了 40 分钟」的错配（describe 与 evaluate 同基准）。
        val s = snap(
            TaskHealthState.WAITING,
            progressAt = now - 9 * 60_000L,
            expectedAt = now - 40 * 60_000L
        )
        val state = TaskHealthPolicy.evaluate(s, now, timeout5min)
        assertEquals(TaskHealthState.STALLED, state)
        val text = TaskHealthPolicy.describe(s, state, now)
        assertTrue("基准应是最近的上报，实际：$text", text.contains("已 9 分钟无进展"))
        assertFalse("预计时刻已被顶掉，不该再按它描述，实际：$text", text.contains("已超预计收取时间"))
    }

    @Test
    fun `超时未收时说明给的是超出预计时间多久`() {
        val s = snap(TaskHealthState.WAITING, expectedAt = now - 40 * 60_000L)
        val text = TaskHealthPolicy.describe(s, TaskHealthPolicy.evaluate(s, now, timeout5min), now)
        assertTrue("应说明超预计时间多久，实际：$text", text.contains("已超预计收取时间 40 分钟"))
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
    fun `在线时不该保留任何暂停原因`() {
        // 2026-10-01 实测：暂停原因落盘后被接续，离线解除只清全局 → 盘上留残影 →
        // 界面永远显示「已暂停（离线中）」，而任务其实在跑。
        assertTrue(TaskHealthPolicy.shouldClearStaleBlock("离线中", offline = false))
        assertTrue(TaskHealthPolicy.shouldClearStaleBlock("安全验证暂停中", offline = false))
        // 确实离线时要保留，否则又变成"看不到已暂停"
        assertFalse(TaskHealthPolicy.shouldClearStaleBlock("离线中", offline = true))
        // 本来就没有暂停原因：无事可做
        assertFalse(TaskHealthPolicy.shouldClearStaleBlock("", offline = false))
        assertFalse(TaskHealthPolicy.shouldClearStaleBlock("", offline = true))
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
