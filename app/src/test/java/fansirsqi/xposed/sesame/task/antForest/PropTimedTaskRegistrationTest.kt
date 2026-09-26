package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「定时使用道具」子任务注册的回归护栏。
 *
 * 背景：`AntForest.useCardBoot` 曾写成 `if (!hasChildTask(id)) { addChildTask(...) } else { addChildTask(...) }`，
 * 两个分支都注册。由于 `ModelTask.addChildTaskSuspend` 是「先 cancel 旧任务再替换」的语义，
 * 反复调用会形成「调度→取消」空转风暴（实测峰值 7.5 次/秒、四天累计 19328 次），
 * 且正在等待中的任务会被反复取消，永远等不到目标时刻执行。
 *
 * 这里用源码文本断言把「同 ID 只注册一次」的不变量钉死：`useCardBoot` 内只允许存在一处 `addChildTask(`。
 */
class PropTimedTaskRegistrationTest {

    private val source: String by lazy {
        File("src/main/java/fansirsqi/xposed/sesame/task/antForest/AntForest.kt").readText()
    }

    /** 取某个顶层函数的函数体（以下一个 4 空格缩进的函数声明为边界）。 */
    private fun topLevelFunctionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未在 AntForest.kt 中找到函数：$signature", start >= 0)
        val nextFun = Regex("\n {4}(?:@\\w+\\s+)*(?:private\\s+|internal\\s+|public\\s+|protected\\s+)?fun ")
            .find(source, start + signature.length)
        return if (nextFun == null) source.substring(start) else source.substring(start, nextFun.range.first)
    }

    @Test
    fun `定时道具子任务同 ID 只注册一次`() {
        val body = topLevelFunctionBody("fun useCardBoot(")

        assertEquals(
            "useCardBoot 内只允许一处 addChildTask 调用；出现两处即代表又写回了重复注册分支",
            1,
            Regex("addChildTask\\(").findAll(body).count()
        )
    }

    @Test
    fun `已存在同 ID 子任务时跳过注册而不是重新注册`() {
        val body = topLevelFunctionBody("fun useCardBoot(")

        assertTrue("应按 targetTaskId 判断是否已存在", body.contains("hasChildTask(targetTaskId)"))
        assertTrue("已存在时应 continue 跳过，不得再次 addChildTask", body.contains("continue"))
    }
}
