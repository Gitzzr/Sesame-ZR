package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleCollectChainPolicyTest {

    @Test
    fun `未达上限时允许继续续收`() {
        assertTrue(DoubleCollectChainPolicy.canContinue(0))
        assertTrue(DoubleCollectChainPolicy.canContinue(1))
        assertTrue(DoubleCollectChainPolicy.canContinue(DoubleCollectChainPolicy.MAX_CHAIN - 1))
    }

    @Test
    fun `达到上限后不再续收`() {
        assertFalse(DoubleCollectChainPolicy.canContinue(DoubleCollectChainPolicy.MAX_CHAIN))
        assertFalse(DoubleCollectChainPolicy.canContinue(DoubleCollectChainPolicy.MAX_CHAIN + 1))
    }

    @Test
    fun `上限为 0 时首次续收即被拒绝`() {
        assertFalse(DoubleCollectChainPolicy.canContinue(0, maxChain = 0))
    }

    @Test
    fun `默认上限足以容纳正常双击链`() {
        // 正常双击链 2~4 次，不应被上限误伤
        assertTrue(DoubleCollectChainPolicy.MAX_CHAIN >= 4)
    }
}
