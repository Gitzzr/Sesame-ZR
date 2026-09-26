package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `AntForest` 收能量流程的两条安全护栏（源码文本断言）。
 *
 * 背景（2026-09-24 22:15 日志事故）：
 * 1. `usePropBeforeCollectEnergy` 里 `useDoubleCard(bagObject!!)` 在背包查询失败（触发安全验证）时抛 NPE；
 * 2. 单球 / 批量两条 `canBeRobbedAgain` 递归续收路径每次都会 `resetTryCount()`，
 *    使 `tryCountInt` 上限失效 —— 服务端持续返回 true 时递归无界，
 *    实测 57 次抛出、单次栈深 5360 帧、约 29MB 日志。
 *
 * 这里把「不再对 bagObject 强解包」和「两条递归路径都受上限约束」钉成回归护栏。
 */
class AntForestCollectGuardTest {

    private val source: String by lazy {
        File("src/main/java/fansirsqi/xposed/sesame/task/antForest/AntForest.kt").readText()
    }

    /** 去掉行注释后的源码，避免注释里提到的写法造成误报。 */
    private val codeOnly: String by lazy {
        source.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun `不再对背包对象做非空强解包`() {
        assertFalse(
            "usePropBeforeCollectEnergy 中不得再出现对 bagObject 的非空断言，背包查询失败时应跳过道具使用",
            codeOnly.contains("bagObject!!")
        )
    }

    @Test
    fun `两条续收递归路径都受链上限约束`() {
        assertEquals(
            "单球路径与批量路径都应调用 DoubleCollectChainPolicy.canContinue",
            2,
            Regex("DoubleCollectChainPolicy\\.canContinue").findAll(codeOnly).count()
        )
    }

    @Test
    fun `续收递归前会累加链深度`() {
        assertEquals(
            "每次递归续收前都应 addChainCount()，否则链上限形同虚设",
            2,
            Regex("addChainCount\\(\\)").findAll(codeOnly).count()
        )
        assertTrue(codeOnly.contains("resetTryCount()"))
    }
}
