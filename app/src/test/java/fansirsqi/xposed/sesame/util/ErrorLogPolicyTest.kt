package fansirsqi.xposed.sesame.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ErrorLogPolicyTest {

    // ---------- 去重语义 ----------

    @Test
    fun `前两次完整打印第三次打印并提示后续不再打印`() {
        val policy = ErrorLogPolicy()
        assertEquals(ErrorLogAction.PRINT, policy.actionFor("NullPointerException:null"))
        assertEquals(ErrorLogAction.PRINT, policy.actionFor("NullPointerException:null"))
        assertEquals(ErrorLogAction.PRINT_AND_ANNOUNCE, policy.actionFor("NullPointerException:null"))
        assertEquals(3, policy.countOf("NullPointerException:null"))
    }

    @Test
    fun `第四次起不再打印堆栈`() {
        val policy = ErrorLogPolicy()
        repeat(3) { policy.actionFor("SameError") }
        repeat(50) { assertEquals(ErrorLogAction.SUPPRESS, policy.actionFor("SameError")) }
        assertEquals(53, policy.countOf("SameError"))
    }

    @Test
    fun `不同特征各自独立计数`() {
        val policy = ErrorLogPolicy()
        repeat(3) { policy.actionFor("ErrorA") }
        assertEquals(ErrorLogAction.SUPPRESS, policy.actionFor("ErrorA"))
        assertEquals(ErrorLogAction.PRINT, policy.actionFor("ErrorB"))
    }

    @Test
    fun `自定义上限生效`() {
        val policy = ErrorLogPolicy(maxDuplicates = 1)
        assertEquals(ErrorLogAction.PRINT_AND_ANNOUNCE, policy.actionFor("X"))
        assertEquals(ErrorLogAction.SUPPRESS, policy.actionFor("X"))
    }

    @Test
    fun `重置后重新开始计数`() {
        val policy = ErrorLogPolicy()
        repeat(5) { policy.actionFor("X") }
        policy.reset()
        assertEquals(0, policy.countOf("X"))
        assertEquals(ErrorLogAction.PRINT, policy.actionFor("X"))
    }

    // ---------- 特征提取 ----------

    @Test
    fun `空响应类 JSON 异常归并为同一特征`() {
        val a = ErrorLogPolicy.signatureOf(org.json.JSONException("End of input at character 0 of "))
        val b = ErrorLogPolicy.signatureOf(org.json.JSONException("End of input at character 0 of {\"x\":1}"))
        assertEquals("JSONException:EmptyResponse", a)
        assertEquals(a, b)
    }

    @Test
    fun `无消息异常也能生成特征`() {
        assertEquals("NullPointerException:null", ErrorLogPolicy.signatureOf(NullPointerException()))
    }

    // ---------- 栈帧截断 ----------

    private fun fakeStack(frames: Int): String = buildString {
        append("java.lang.NullPointerException: boom\n")
        repeat(frames) { append("\tat fansirsqi.xposed.sesame.Fake.method$it(Fake.kt:$it)\n") }
    }

    @Test
    fun `栈深未超限时原样返回`() {
        val raw = fakeStack(10)
        assertEquals(raw, StackTraceFormatter.truncateFrames(raw))
    }

    @Test
    fun `栈深超限时保留栈顶并标注总帧数`() {
        val raw = fakeStack(100)
        val out = StackTraceFormatter.truncateFrames(raw)
        val kept = out.lines().count { it.trimStart().startsWith("at ") }

        assertEquals(StackTraceFormatter.MAX_FRAMES, kept)
        assertTrue("应标注总帧数", out.contains("（已截断") || out.contains("(已截断"))
        assertTrue(out.contains("100"))
        assertTrue("必须保留异常首行", out.startsWith("java.lang.NullPointerException: boom"))
        // 保留的应是栈顶（method0 起始），而不是栈底
        assertTrue(out.contains("method0"))
        assertFalse(out.contains("method99"))
    }

    @Test
    fun `帧数边界等于上限时不截断`() {
        val raw = fakeStack(StackTraceFormatter.MAX_FRAMES)
        assertEquals(raw, StackTraceFormatter.truncateFrames(raw))
    }

    // ---------- 集成护栏：Log.kt 必须真的用上这两个策略 ----------

    @Test
    fun `Log 走策略且不再保留反转的旧实现`() {
        val logSource = File("src/main/java/fansirsqi/xposed/sesame/util/Log.kt").readText()
        assertTrue(logSource.contains("ErrorLogPolicy"))
        assertTrue(logSource.contains("StackTraceFormatter.truncateFrames"))
        assertFalse(
            "旧的 shouldPrintError 语义反转（返回 true 表示跳过打印），必须删除",
            logSource.contains("shouldPrintError")
        )
    }
}
