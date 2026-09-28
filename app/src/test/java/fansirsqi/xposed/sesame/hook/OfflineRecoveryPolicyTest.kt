package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRecoveryPolicyTest {

    @Test
    fun `探测间隔按 2-5-15-30 分钟递增并封顶`() {
        assertEquals(120_000L, OfflineRecoveryPolicy.probeDelayMs(1))
        assertEquals(300_000L, OfflineRecoveryPolicy.probeDelayMs(2))
        assertEquals(900_000L, OfflineRecoveryPolicy.probeDelayMs(3))
        assertEquals(1_800_000L, OfflineRecoveryPolicy.probeDelayMs(4))
        // 封顶后再探测也不会越等越久
        assertEquals(1_800_000L, OfflineRecoveryPolicy.probeDelayMs(9))
        // 非法入参按第一次算，不能返回 0 导致忙等
        assertEquals(120_000L, OfflineRecoveryPolicy.probeDelayMs(0))
    }

    @Test
    fun `重开支付宝只在前几次探测时发生`() {
        assertTrue(OfflineRecoveryPolicy.shouldReopenApp(1))
        assertFalse(OfflineRecoveryPolicy.shouldReopenApp(2))
        assertFalse(OfflineRecoveryPolicy.shouldReopenApp(99))
    }

    @Test
    fun `探测日志会静默但探测继续`() {
        assertTrue(OfflineRecoveryPolicy.shouldLogProbe(1))
        assertTrue(OfflineRecoveryPolicy.shouldLogProbe(OfflineRecoveryPolicy.QUIET_AFTER_ATTEMPTS))
        assertFalse(OfflineRecoveryPolicy.shouldLogProbe(OfflineRecoveryPolicy.QUIET_AFTER_ATTEMPTS + 1))
    }
}
