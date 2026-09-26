package fansirsqi.xposed.sesame.task.antFarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FarmToolFailureSuppressPolicyTest {

    private val bigEater = "BIG_EATER_TOOL"
    private val cooldown = FarmToolFailureSuppressPolicy.DEFAULT_COOLDOWN_MS

    @Test
    fun `首次允许尝试`() {
        val policy = FarmToolFailureSuppressPolicy()
        assertTrue(policy.shouldAttempt(bigEater, 0L))
        assertTrue(policy.failedTools(0L).isEmpty())
        assertEquals(0L, policy.remainingCooldownMs(bigEater, 0L))
    }

    @Test
    fun `失败后冷却期内不再尝试`() {
        // 实测失败是「每次调用各一次」（蹲点投喂 31 次/天 + 主轮次 13 次/天），
        // 所以必须按时间冷却，只做同轮抑制抓不到真实模式。
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater, 0L)
        assertFalse(policy.shouldAttempt(bigEater, 0L))
        assertFalse(policy.shouldAttempt(bigEater, cooldown - 1))
        assertEquals(setOf(bigEater), policy.failedTools(0L))
        assertEquals(cooldown, policy.remainingCooldownMs(bigEater, 0L))
    }

    @Test
    fun `冷却结束后恢复尝试`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater, 0L)
        assertTrue(policy.shouldAttempt(bigEater, cooldown))
        assertTrue(policy.failedTools(cooldown).isEmpty())
        assertEquals(0L, policy.remainingCooldownMs(bigEater, cooldown))
    }

    @Test
    fun `不同道具互不影响`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater, 0L)
        assertFalse(policy.shouldAttempt(bigEater, 0L))
        assertTrue(policy.shouldAttempt("ACCELERATETOOL", 0L))
    }

    @Test
    fun `再次失败会刷新冷却起点`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater, 0L)
        policy.markFailed(bigEater, cooldown - 1)
        assertFalse("应以最近一次失败为准", policy.shouldAttempt(bigEater, cooldown))
        assertTrue(policy.shouldAttempt(bigEater, cooldown * 2 - 1))
    }

    @Test
    fun `冷却时长不超过 30 分钟上界`() {
        // 与 VerificationPausePolicy.VERIFICATION_TTL_MS 一致：不得写入更长的暂停语义
        assertTrue(FarmToolFailureSuppressPolicy.DEFAULT_COOLDOWN_MS <= 30 * 60 * 1000L)
    }

    @Test
    fun `并发标记后判定一致`() {
        val policy = FarmToolFailureSuppressPolicy()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map {
                executor.submit {
                    start.await()
                    policy.markFailed(bigEater, 0L)
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertFalse(policy.shouldAttempt(bigEater, 0L))
            assertEquals(1, policy.failedTools(0L).size)
        } finally {
            executor.shutdownNow()
        }
    }
}
