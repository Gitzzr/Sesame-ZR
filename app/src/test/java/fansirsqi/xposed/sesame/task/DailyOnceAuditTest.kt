package fansirsqi.xposed.sesame.task

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyOnceAuditTest {

    private fun e(flag: String, at: Long, label: String = flag) =
        DailyOnceAudit.Entry(flag = flag, label = label, at = at)

    @Test
    fun `同一天同一 flag 只保留最早一次`() {
        var days = DailyOnceAudit.merge(emptyMap(), "2026-09-29", e("farm::x", 200))
        days = DailyOnceAudit.merge(days, "2026-09-29", e("farm::x", 100))
        days = DailyOnceAudit.merge(days, "2026-09-29", e("farm::x", 300))
        assertEquals(1, days["2026-09-29"]!!.size)
        assertEquals(100L, days["2026-09-29"]!![0].at)
    }

    @Test
    fun `不同 flag 与不同日期各自成条`() {
        var days = DailyOnceAudit.merge(emptyMap(), "2026-09-29", e("farm::x", 100))
        days = DailyOnceAudit.merge(days, "2026-09-29", e("farm::y", 200))
        days = DailyOnceAudit.merge(days, "2026-09-30", e("farm::x", 300))
        assertEquals(2, days["2026-09-29"]!!.size)
        assertEquals(1, days["2026-09-30"]!!.size)
    }

    @Test
    fun `空值不写入`() {
        assertEquals(0, DailyOnceAudit.merge(emptyMap(), "", e("x", 1)).size)
        assertEquals(0, DailyOnceAudit.merge(emptyMap(), "2026-09-29", e("", 1)).size)
    }

    @Test
    fun `只保留最近七天`() {
        val days = (1..10).associate { d ->
            "2026-09-%02d".format(d) to listOf(e("f$d", d.toLong()))
        }
        val trimmed = DailyOnceAudit.trim(days)
        assertEquals(DailyOnceAudit.KEEP_DAYS, trimmed.size)
        assertEquals(false, trimmed.containsKey("2026-09-03"))
        assertEquals(true, trimmed.containsKey("2026-09-10"))
    }

    @Test
    fun `自然日键用 GMT+8`() {
        // 2026-09-29 00:30 (GMT+8) 落在 GMT+8 的 29 日（同一时刻在 UTC 还是 28 日）
        assertEquals("2026-09-29", DailyOnceAudit.dayKey(1790613000000L))
    }
}
