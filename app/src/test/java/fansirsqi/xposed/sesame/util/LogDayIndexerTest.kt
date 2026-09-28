package fansirsqi.xposed.sesame.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogDayIndexerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "logday-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun part(name: String, content: String, index: Int): LogFileHistory.Partition =
        LogFileHistory.Partition(File(dir, name).apply { writeText(content) }, index)

    private fun lines(vararg texts: String) = texts.joinToString("") { "$it\n" }

    @Test
    fun `多分片偏移全局升序且落在各自基址区间`() {
        val p0 = part("a-2026-09-24.0.log", lines("28日 09:00:00.00 [A]: 一号", "28日 09:00:01.00 [A]: 二号"), 0)
        val p1 = part("a-2026-09-24.1.log", lines("28日 09:00:02.00 [B]: 三号"), 1)

        val r = LogDayIndexer.scan(listOf(p0, p1), bufferBytes = 1024, maxLines = 100)
        assertEquals(3, r.offsets.size)
        assertEquals(r.offsets.sorted(), r.offsets)          // 升序
        assertFalse(r.truncated)
        // 前两行在第 0 分片区间，第三行落在第 1 分片基址之后
        assertTrue(LogSource.seqOf(r.offsets[0]) == 0 && LogSource.seqOf(r.offsets[1]) == 0)
        assertEquals(1, LogSource.seqOf(r.offsets[2]))
        assertEquals("A", r.tags[r.offsets[0]])
        assertEquals("B", r.tags[r.offsets[2]])
    }

    @Test
    fun `错误行偏移跨分片合并`() {
        val p0 = part("a-2026-09-24.0.log", lines("28日 09:00:00.00 [A]: 正常", "28日 09:00:01.00 [A]: 查询失败"), 0)
        val p1 = part("a-2026-09-24.1.log", lines("28日 09:00:02.00 [B]: exception"), 1)

        val r = LogDayIndexer.scan(listOf(p0, p1), bufferBytes = 1024, maxLines = 100)
        assertEquals(2, r.errorOffsets.size)
        assertTrue(r.errorOffsets.contains(r.offsets[1]))
        assertTrue(r.errorOffsets.contains(r.offsets[2]))
    }

    @Test
    fun `超过上限时保留最新行并标记截断`() {
        // 3 个分片，每片 4 行；上限 5 → 应保留最新的 5 行（最后 2 片的部分）
        val parts = (0 until 3).map { i ->
            part("a-2026-09-24.$i.log", lines("行$i-1", "行$i-2", "行$i-3", "行$i-4"), i)
        }
        val r = LogDayIndexer.scan(parts, bufferBytes = 1024, maxLines = 5)
        assertEquals(5, r.offsets.size)
        assertTrue(r.truncated)
        // 保留的偏移必须全部属于最新的分片（最旧的 0 号分片整片被丢掉）
        assertTrue(r.offsets.none { LogSource.seqOf(it) == 0 })
    }

    @Test
    fun `凑够上限后不再读更旧的分片`() {
        // 第 1、2 片各有 5 行，上限 5：从最新往前读，第 1 片就已凑满 → 第 0 片不应被读
        val p0 = part("a-2026-09-24.0.log", lines("old-1", "old-2", "old-3", "old-4", "old-5"), 0)
        val p1 = part("a-2026-09-24.1.log", lines("mid-1", "mid-2", "mid-3", "mid-4", "mid-5"), 1)
        val p2 = part("a-2026-09-24.2.log", lines("new-1", "new-2", "new-3", "new-4", "new-5"), 2)

        val r = LogDayIndexer.scan(listOf(p0, p1, p2), bufferBytes = 1024, maxLines = 5)
        assertEquals(5, r.offsets.size)
        assertTrue(r.truncated)
        assertEquals(setOf(2), r.offsets.map { LogSource.seqOf(it) }.toSet())
    }

    @Test
    fun `取消时中止扫描且不抛异常`() {
        val parts = (0 until 5).map { i -> part("a-2026-09-24.$i.log", lines("行$i"), i) }
        var calls = 0
        val r = LogDayIndexer.scan(parts, bufferBytes = 1024, maxLines = 100) {
            calls++
            calls < 3          // 第三次调用时返回 false（已取消）
        }
        // 部分结果可以返回，但必须已中止（不可能扫完全部分片）
        assertTrue(r.offsets.size < parts.size)
    }

    @Test
    fun `尾部索引只取最后一个分片的尾部`() {
        val p0 = part("a-2026-09-24.0.log", lines("旧一", "旧二"), 0)
        val p1 = part("a-2026-09-24.1.log", lines("新一", "新二", "新三"), 1)

        val r = LogDayIndexer.readTail(listOf(p0, p1), tailBytes = 4096)
        assertEquals(3, r.offsets.size)
        // 尾部偏移全部属于最后一个分片（基址为 1）
        assertTrue(r.offsets.all { LogSource.seqOf(it) == 1 })
        assertFalse(r.truncated)
    }

    @Test
    fun `空分区列表返回空结果`() {
        val r = LogDayIndexer.scan(emptyList(), bufferBytes = 1024, maxLines = 100)
        assertEquals(0, r.offsets.size)
        assertFalse(r.truncated)
    }
}
