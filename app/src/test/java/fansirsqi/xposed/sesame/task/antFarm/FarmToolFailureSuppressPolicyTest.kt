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

    @Test
    fun `首次允许尝试`() {
        val policy = FarmToolFailureSuppressPolicy()
        assertTrue(policy.shouldAttempt(bigEater))
        assertTrue(policy.failedTools().isEmpty())
    }

    @Test
    fun `失败后同一轮内不再尝试`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater)
        assertFalse(policy.shouldAttempt(bigEater))
        assertFalse(policy.shouldAttempt(bigEater))
        assertEquals(setOf(bigEater), policy.failedTools())
    }

    @Test
    fun `进入新一轮后恢复尝试`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater)
        assertFalse(policy.shouldAttempt(bigEater))
        policy.startRound()
        assertTrue(policy.shouldAttempt(bigEater))
        assertTrue(policy.failedTools().isEmpty())
    }

    @Test
    fun `不同道具互不影响`() {
        val policy = FarmToolFailureSuppressPolicy()
        policy.markFailed(bigEater)
        assertFalse(policy.shouldAttempt(bigEater))
        assertTrue(policy.shouldAttempt("ACCELERATETOOL"))
    }

    @Test
    fun `并发标记后同轮判定一致`() {
        val policy = FarmToolFailureSuppressPolicy()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map {
                executor.submit {
                    start.await()
                    policy.markFailed(bigEater)
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertFalse(policy.shouldAttempt(bigEater))
            assertEquals(1, policy.failedTools().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
