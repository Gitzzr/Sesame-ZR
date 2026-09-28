package fansirsqi.xposed.sesame.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LogIndexBuilderTest {

    private fun build(text: String, startOffset: Long = 0L, finish: Boolean = true): LogIndexBuilder {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return LogIndexBuilder(startOffset).apply {
            feed(bytes)
            if (finish) finish()
        }
    }

    @Test
    fun `换行即记一行，偏移与旧实现一致`() {
        val b = build("a\nb\nc\n")
        // 与旧扫描器一致：每个 \n 之后记一行，文件结尾的 \n 不再多记一行
        assertEquals(listOf(0L, 2L, 4L), b.offsets())
    }

    @Test
    fun `不调用 finish 时末尾半行不入账`() {
        val b = build("aaa\nbbb", finish = false)
        assertEquals(listOf(0L), b.offsets())
        // 补上 finish 才会记最后一行
        b.finish()
        assertEquals(listOf(0L, 4L), b.offsets())
    }

    @Test
    fun `行首附近的方括号被当作 tag`() {
        val b = build("28日 09:27:02.59 [RequestManager]: 触发安全验证\n")
        assertEquals("RequestManager", b.tags()[0L])
    }

    @Test
    fun `正文里的方括号不会被当成 tag`() {
        val b = build("28日 09:27:02.59 没有 tag 前缀，只是正文里有个 [json] 片段\n")
        assertEquals("", b.tags()[0L])
    }

    @Test
    fun `错误行按标记计入 errorOffsets`() {
        val text = "28日 09:00:00.00 [A]: 正常一行\n" +
            "28日 09:00:01.00 [A]: 查询失败\n" +
            "28日 09:00:02.00 [B]: Exception in thread\n" +
            "28日 09:00:03.00 [B]: ❌ 出错\n"
        val b = build(text)
        assertEquals(3, b.errorOffsets().size)
        assertFalse(b.errorOffsets().contains(0L))
    }

    @Test
    fun `多字节中文被分块喂入也能正确判定`() {
        // 「失败」的 UTF-8 字节可能被切在两次 feed 之间，行缓冲必须能拼回来
        val bytes = "28日 09:00:01.00 [A]: 失败\n".toByteArray(StandardCharsets.UTF_8)
        val b = LogIndexBuilder(0L)
        b.feed(bytes, 0, 12)
        b.feed(bytes, 12, bytes.size - 12)
        b.finish()
        assertEquals(1, b.errorOffsets().size)
        assertEquals(listOf(0L), b.offsets())
    }

    @Test
    fun `起始偏移用于增量追加`() {
        val b = build("x\ny\n", startOffset = 1000L)
        assertEquals(listOf(1000L, 1002L), b.offsets())
    }

    @Test
    fun `超长行被截断也不影响后续行的偏移`() {
        val longLine = "z".repeat(20_000)
        val b = build("$longLine\n尾\n")
        // 第一行 20000 字节 + 1 个换行 → 第二行从 20001 开始
        assertEquals(listOf(0L, 20_001L), b.offsets())
    }

    @Test
    fun `空行也会被计入，保持行号连续`() {
        val b = build("a\n\nb\n")
        assertEquals(listOf(0L, 2L, 3L), b.offsets())
        assertTrue(b.errorOffsets().isEmpty())
    }

    @Test
    fun `十万行一次扫描即可完成`() {
        // 不追求精确耗时（CI 机器差异大），只拦住「退化回逐行读文件 / 二次复杂度」这类回归：
        // 旧实现建 tag 索引要对每行 seek+readLine 一次，10 万行在真机上是几十秒。
        val text = buildString {
            repeat(100_000) { i ->
                append("28日 09:00:00.00 [Tag").append(i % 20).append("]: 第 ")
                append(i).append(" 行内容\n")
            }
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val start = System.nanoTime()
        val b = LogIndexBuilder(0L)
        b.feed(bytes)
        b.finish()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(100_000, b.offsets().size)
        assertEquals(20, b.tags().values.filter { it.isNotEmpty() }.distinct().size)
        assertTrue("10 万行索引耗时 ${elapsedMs}ms，疑似退化", elapsedMs < 5_000)
    }
}
