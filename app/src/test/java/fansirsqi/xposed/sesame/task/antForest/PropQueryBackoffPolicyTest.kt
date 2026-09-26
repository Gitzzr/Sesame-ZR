package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PropQueryBackoffPolicyTest {

    private val threshold = PropQueryBackoffPolicy.DEFAULT_FAILURE_THRESHOLD
    private val cooldown = PropQueryBackoffPolicy.DEFAULT_COOLDOWN_MS

    @Test
    fun `默认允许查询`() {
        val policy = PropQueryBackoffPolicy()
        assertTrue(policy.canQuery(0L))
        assertEquals(0L, policy.remainingCooldownMs(0L))
    }

    @Test
    fun `未达阈值时仍允许查询`() {
        val policy = PropQueryBackoffPolicy()
        repeat(threshold - 1) { assertFalse("未达阈值不应进入退避", policy.recordFailure(0L)) }
        assertTrue(policy.canQuery(0L))
    }

    @Test
    fun `连续失败达阈值后进入冷却并停止查询`() {
        val policy = PropQueryBackoffPolicy()
        var entered = false
        repeat(threshold) { entered = policy.recordFailure(0L) || entered }
        assertTrue("达到阈值那一次应返回 true（用于只记一条日志）", entered)
        assertFalse(policy.canQuery(0L))
        assertFalse(policy.canQuery(cooldown - 1))
        assertEquals(cooldown, policy.remainingCooldownMs(0L))
    }

    @Test
    fun `冷却期内继续失败不会重复公告`() {
        val policy = PropQueryBackoffPolicy()
        repeat(threshold) { policy.recordFailure(0L) }
        assertFalse("冷却期内再失败不应重复返回 true", policy.recordFailure(1_000L))
        assertFalse(policy.recordFailure(2_000L))
    }

    @Test
    fun `冷却结束后恢复查询`() {
        val policy = PropQueryBackoffPolicy()
        repeat(threshold) { policy.recordFailure(0L) }
        assertTrue(policy.canQuery(cooldown))
        assertEquals(0L, policy.remainingCooldownMs(cooldown))
    }

    @Test
    fun `一次成功立即解除退避`() {
        val policy = PropQueryBackoffPolicy()
        repeat(threshold) { policy.recordFailure(0L) }
        assertFalse(policy.canQuery(1L))
        policy.recordSuccess()
        assertTrue(policy.canQuery(1L))
        // 解除后计数归零，需要重新累计到阈值才再次退避
        repeat(threshold - 1) { assertFalse(policy.recordFailure(2L)) }
        assertTrue(policy.canQuery(2L))
    }

    @Test
    fun `退避时长小于验证暂停的 30 分钟上界`() {
        assertTrue(
            "冷却时长必须小于 VerificationPausePolicy 的 TTL 上界，避免长时间暂停",
            PropQueryBackoffPolicy.DEFAULT_COOLDOWN_MS < 30 * 60 * 1000L
        )
        assertTrue(threshold >= 2)
    }

    @Test
    fun `并发失败不会漏计`() {
        val policy = PropQueryBackoffPolicy()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map {
                executor.submit {
                    start.await()
                    policy.recordFailure(0L)
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertFalse("并发失败后应已进入冷却", policy.canQuery(0L))
        } finally {
            executor.shutdownNow()
        }
    }
}
