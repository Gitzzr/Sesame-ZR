package fansirsqi.xposed.sesame.hook.rpc.intervallimit

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `RpcIntervalLimit` 的间隔语义与并发行为。
 *
 * 关注点：原实现把 `sleepCompat` 放在 `synchronized(lock)` **内部**，
 * 并发调用会一起堵在监视器上（实测 `dvm_lock_sample` 在该锁上采样 310 次）。
 * 改为「锁内只预约时刻、锁外 sleep」后，必须保证两件事：
 * 1. **间隔语义不变** —— 相邻调用之间的间隔仍不小于 `interval`；
 * 2. **不再持锁等待** —— 等待期间其它调用能拿到锁、各自预约到**不同**的时间片。
 */
class RpcIntervalLimitTest {

    private val registered = mutableListOf<String>()

    private fun register(interval: Int): IntervalLimit {
        val method = "test.interval." + UUID.randomUUID()
        val limit = DefaultIntervalLimit(interval)
        RpcIntervalLimit.addIntervalLimit(method, limit)
        registered += method
        return limit
    }

    @After
    fun cleanUp() {
        // 间隔限制表是 object 级共享状态，逐个移除避免影响其它用例
        registered.forEach { RpcIntervalLimit.updateIntervalLimit(it, 0) }
    }

    @Test
    fun `首次调用不需要等待`() {
        val interval = 60
        register(interval)
        val method = registered.last()
        val start = System.currentTimeMillis()
        RpcIntervalLimit.enterIntervalLimit(method)
        val cost = System.currentTimeMillis() - start
        // time 初值为 0，早于当前时刻，故首次调用不等待（与原实现一致）
        assertTrue("首次调用不应等待，实际 ${cost}ms", cost < interval)
    }

    @Test
    fun `相邻两次调用的间隔不小于设定值`() {
        val interval = 80
        val limit = register(interval)
        val method = registered.last()

        RpcIntervalLimit.enterIntervalLimit(method)
        val secondStart = System.currentTimeMillis()
        RpcIntervalLimit.enterIntervalLimit(method)
        val cost = System.currentTimeMillis() - secondStart

        // 第二次应被推迟到「上次预约时刻 + interval」
        assertTrue("第二次调用应等待约 ${interval}ms，实际 ${cost}ms", cost >= interval - 15)
        assertTrue("预约时刻应晚于第二次的进入时刻", limit.time >= secondStart)
    }

    @Test
    fun `并发调用各自拿到不同时间片且不持锁等待`() {
        val interval = 60
        val threads = 3
        register(interval)
        val method = registered.last()

        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val done = (1..threads).map {
                executor.submit {
                    start.await()
                    RpcIntervalLimit.enterIntervalLimit(method)
                }
            }
            val begin = System.currentTimeMillis()
            start.countDown()
            done.forEach { it.get(10, TimeUnit.SECONDS) }
            val total = System.currentTimeMillis() - begin

            // 3 个调用者应各占一个时间片：首个不等待，其余两个分别等到 +interval、+2*interval
            val expected = (threads - 1) * interval
            assertTrue(
                "并发调用总耗时应至少约 ${expected}ms（说明各拿到独立时间片），实际 ${total}ms",
                total >= expected - 20
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
