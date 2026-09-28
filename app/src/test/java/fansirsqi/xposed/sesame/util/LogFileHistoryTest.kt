package fansirsqi.xposed.sesame.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogFileHistoryTest {

    private lateinit var logDir: File

    @Before
    fun setUp() {
        logDir = File(System.getProperty("java.io.tmpdir"), "loghist-${System.nanoTime()}").apply {
            mkdirs()
            File(this, "bak").mkdirs()
        }
    }

    @After
    fun tearDown() {
        logDir.deleteRecursively()
    }

    private fun shard(name: String) = File(File(logDir, "bak"), name).apply { writeText("x\n") }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `解析合法分片名`() {
        val p = LogFileHistory.parseShardName("record-2026-09-24.58.log")
        assertEquals("record", p!!.logName)
        assertEquals("2026-09-24", p.date)
        assertEquals(58, p.index)
    }

    @Test
    fun `拒绝非分片名与非法日期`() {
        assertNull(LogFileHistory.parseShardName("record.log"))            // 活动文件
        assertNull(LogFileHistory.parseShardName("record-2026-09-24.log")) // 缺 %i
        assertNull(LogFileHistory.parseShardName("record-2026-13-40.0.log")) // 非法日期
        assertNull(LogFileHistory.parseShardName("随便一个文件.txt"))
    }

    @Test
    fun `按 logName 过滤`() {
        assertNull(LogFileHistory.parseShardName("error-2026-09-24.0.log", "record"))
        assertEquals(
            "error",
            LogFileHistory.parseShardName("error-2026-09-24.0.log", "error")!!.logName
        )
    }

    // ------------------------------------------------------------------ 排序

    @Test
    fun `分片按序号数值升序而不是字典序`() {
        listOf(0, 1, 2, 9, 10, 60, 61).forEach { shard("error-2026-09-24.$it.log") }
        val parts = LogFileHistory.partitionsOf(logDir, "error", "2026-09-24")
        assertEquals(listOf(0, 1, 2, 9, 10, 60, 61), parts.map { it.shardIndex })
        // 字典序会把 .9 排到 .60 之后，这里明确守住数值序
        assertEquals("error-2026-09-24.9.log", parts[3].file.name)
    }

    // ------------------------------------------------------------------ 日期列表

    @Test
    fun `可用日期倒序且只含有该 logName 数据的日期`() {
        shard("record-2026-09-23.0.log")
        shard("record-2026-09-27.0.log")
        shard("error-2026-09-26.0.log")      // 别的 logName，不该出现
        shard("record-2026-09-27.1.log")     // 同一天多分片只算一天
        File(logDir, "record.log").writeText("today\n")

        val dates = LogFileHistory.availableDates(logDir, "record")
        val today = LogFileHistory.dayKey()
        // 不假设「今天必然最新」（测试跑在哪一年都成立）
        val expected = listOf(today, "2026-09-27", "2026-09-23").sortedDescending()
        assertEquals(expected, dates)
        assertEquals(3, dates.size)   // 同一天多分片只算一天
    }

    @Test
    fun `没有活动文件也没有分片时日期为空`() {
        assertTrue(LogFileHistory.availableDates(logDir, "record").isEmpty())
    }

    // ------------------------------------------------------------------ 分区列表

    @Test
    fun `历史日期只含 bak 分片，今天把活动文件排在最后`() {
        shard("record-2026-09-27.0.log")
        shard("record-2026-09-27.1.log")
        File(logDir, "record.log").writeText("today\n")

        val history = LogFileHistory.partitionsOf(logDir, "record", "2026-09-27")
        assertEquals(listOf(0, 1), history.map { it.shardIndex })

        val today = LogFileHistory.dayKey()
        shard("record-$today.0.log")
        val todayParts = LogFileHistory.partitionsOf(logDir, "record", today)
        assertEquals(listOf(0, -1), todayParts.map { it.shardIndex })
        assertEquals("record.log", todayParts.last().file.name)
    }

    @Test
    fun `当天已有分片但活动文件不存在时仍能列出分片`() {
        val today = LogFileHistory.dayKey()
        shard("record-$today.3.log")
        val parts = LogFileHistory.partitionsOf(logDir, "record", today)
        assertEquals(listOf(3), parts.map { it.shardIndex })
    }

    // ------------------------------------------------------------------ 目录回退

    @Test
    fun `按候选顺序挑出可读且含日志的目录`() {
        val empty = File(System.getProperty("java.io.tmpdir"), "loghist-empty-${System.nanoTime()}")
        empty.mkdirs()
        val fallback = File(System.getProperty("java.io.tmpdir"), "loghist-fb-${System.nanoTime()}")
        File(fallback, "bak").mkdirs()
        File(File(fallback, "bak"), "record-2026-09-27.0.log").writeText("x\n")

        try {
            val picked = LogFileHistory.resolveReadableLogDir(
                listOf(empty, logDir, fallback), "record"
            )
            assertEquals(fallback, picked)

            // 第一个候选就有数据时优先用它
            File(logDir, "record.log").writeText("today\n")
            assertEquals(logDir, LogFileHistory.resolveReadableLogDir(listOf(logDir, fallback), "record"))

            // 都不含该 logName → null
            assertNull(LogFileHistory.resolveReadableLogDir(listOf(empty), "record"))
        } finally {
            empty.deleteRecursively()
            fallback.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ 日键

    @Test
    fun `日键与 isToday 用设备时区`() {
        // 与 Logback 文件名同源：都取 JVM 默认时区，所以「此刻的自然日」必须等于 dayKey(now)
        val now = System.currentTimeMillis()
        val key = LogFileHistory.dayKey(now)
        assertTrue(LogFileHistory.isToday(key, now))
        assertFalse(LogFileHistory.isToday("2000-01-01", now))
        assertEquals(10, key.length)
    }
}
