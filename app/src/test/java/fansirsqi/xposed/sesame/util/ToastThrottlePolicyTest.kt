package fansirsqi.xposed.sesame.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastThrottlePolicyTest {

    private val dedupe = ToastThrottlePolicy.DEDUPE_WINDOW_MS
    private val rateWindow = ToastThrottlePolicy.RATE_WINDOW_MS
    private val rateLimit = ToastThrottlePolicy.RATE_LIMIT

    @Test
    fun `相同文案在去重窗口内只放行一次`() {
        val policy = ToastThrottlePolicy()
        assertTrue(policy.shouldShow(0L, "找能量收取🌽2g[翔|*翔]"))
        assertFalse(policy.shouldShow(1L, "找能量收取🌽2g[翔|*翔]"))
        assertFalse(policy.shouldShow(dedupe - 1, "找能量收取🌽2g[翔|*翔]"))
        assertEquals(2, policy.suppressedCount())
    }

    @Test
    fun `超过去重窗口后相同文案可再次放行`() {
        val policy = ToastThrottlePolicy()
        assertTrue(policy.shouldShow(0L, "同一句话"))
        assertTrue(policy.shouldShow(dedupe, "同一句话"))
    }

    @Test
    fun `不同文案不受去重影响`() {
        val policy = ToastThrottlePolicy()
        assertTrue(policy.shouldShow(0L, "文案A"))
        assertTrue(policy.shouldShow(0L, "文案B"))
    }

    @Test
    fun `滑动窗口内超过上限的文案被抑制`() {
        val policy = ToastThrottlePolicy()
        var allowed = 0
        repeat(rateLimit + 5) { i ->
            if (policy.shouldShow(1_000L, "第${i}条")) allowed++
        }
        assertEquals(rateLimit, allowed)
        assertEquals(5, policy.suppressedCount())
    }

    @Test
    fun `窗口滚动后配额恢复`() {
        val policy = ToastThrottlePolicy()
        repeat(rateLimit) { i -> assertTrue(policy.shouldShow(0L, "第${i}条")) }
        assertFalse(policy.shouldShow(1L, "再来一条"))
        // 窗口整体滚过后，旧记录被淘汰，配额恢复
        assertTrue(policy.shouldShow(rateWindow, "再来一条"))
    }

    @Test
    fun `空文案不放行`() {
        val policy = ToastThrottlePolicy()
        assertFalse(policy.shouldShow(0L, null))
    }

    @Test
    fun `重置后恢复初始状态`() {
        val policy = ToastThrottlePolicy()
        assertTrue(policy.shouldShow(0L, "文案"))
        assertFalse(policy.shouldShow(1L, "文案"))
        policy.reset()
        assertEquals(0, policy.suppressedCount())
        assertTrue(policy.shouldShow(1L, "文案"))
    }

    @Test
    fun `默认限流低于系统配额留出余量`() {
        // 系统按包统计 Toast 配额；限流值应明显小于常见配额，给宿主自身留出余量
        assertTrue(rateLimit in 1..10)
        assertTrue(rateWindow >= dedupe)
    }
}
