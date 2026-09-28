package fansirsqi.xposed.sesame.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogSourceTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "logsource-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    // ------------------------------------------------------------------ 打包

    @Test
    fun `偏移打包与解包往返`() {
        listOf(0 to 0L, 1 to 12345L, 61 to 7_000_000L, 100 to LogSource.OFF_MASK).forEach { (seq, off) ->
            val packed = LogSource.baseOf(seq) or (off and LogSource.OFF_MASK)
            assertEquals(seq, LogSource.seqOf(packed))
            assertEquals(off, LogSource.offOf(packed))
        }
    }

    @Test
    fun `打包值按序号升序，且第 0 分片与原始偏移等价`() {
        // 单分片（今天只有活动文件）时，打包值必须等于文件内偏移 —— 保证改造前后行为一致
        assertEquals(0L, LogSource.baseOf(0))
        assertEquals(1024L, LogSource.baseOf(0) or 1024L)
        assertTrue(LogSource.baseOf(1) > LogSource.baseOf(0) or LogSource.OFF_MASK)
    }

    // ------------------------------------------------------------------ 读行

    @Test
    fun `跨分片按打包偏移读回原始行`() {
        val a = file("record-2026-09-24.0.log", "第一行\n第二行\n")
        val b = file("record-2026-09-24.1.log", "第三行\n")
        val parts = listOf(
            LogFileHistory.Partition(a, 0),
            LogFileHistory.Partition(b, 1)
        )
        val source = LogSource(parts)
        try {
            // 第一分片：base(0) + 0 → "第一行"；base(0) + "第一行\n" 的长度 → "第二行"
            val firstBytes = "第一行\n".toByteArray(Charsets.UTF_8).size.toLong()
            assertEquals("第一行", source.readLineAt(LogSource.baseOf(0)))
            assertEquals("第二行", source.readLineAt(LogSource.baseOf(0) or firstBytes))
            // 第二分片的第一行
            assertEquals("第三行", source.readLineAt(LogSource.baseOf(1)))
            assertEquals(2, source.size)
            assertEquals(listOf(a, b), source.files())
        } finally {
            source.close()
        }
    }

    @Test
    fun `越界偏移与不存在的分片返回 null 而不抛异常`() {
        val a = file("record-2026-09-24.0.log", "只有一行\n")
        val source = LogSource(listOf(LogFileHistory.Partition(a, 0)))
        try {
            assertNull(source.readLineAt(LogSource.baseOf(9)))          // 分片越界
            assertNull(source.readLineAt(LogSource.baseOf(0) or 1_000L)) // 偏移越界
        } finally {
            source.close()
        }
    }

    @Test
    fun `句柄按上限回收，超过上限后仍能继续读`() {
        val parts = (0 until 10).map { i ->
            LogFileHistory.Partition(file("record-2026-09-24.$i.log", "行$i\n"), i)
        }
        val source = LogSource(parts, maxOpen = 2)
        try {
            // 逆序读，强制反复换出/换入句柄
            (9 downTo 0).forEach { seq ->
                assertEquals("行$seq", source.readLineAt(LogSource.baseOf(seq)))
            }
            // 再顺序读一遍，确认真回收后还能重开
            (0 until 10).forEach { seq ->
                assertEquals("行$seq", source.readLineAt(LogSource.baseOf(seq)))
            }
        } finally {
            source.close()
            // close 后仍可重复调用（幂等）
            source.close()
        }
    }
}
