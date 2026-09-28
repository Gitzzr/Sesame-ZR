package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropGiftLoopPolicyTest {

    @Test
    fun `查询失败必须停止`() {
        // 2026-09-28 实测的死循环入口：查询被暂停/拒绝时旧实现会无限重查
        assertFalse(PropGiftLoopPolicy.shouldContinue(queryOk = false, propListSize = 5, holdsNum = 5))
    }

    @Test
    fun `没有可赠送的道具必须停止`() {
        assertFalse(PropGiftLoopPolicy.shouldContinue(queryOk = true, propListSize = 0, holdsNum = 0))
    }

    @Test
    fun `只剩一个且持有不足时停止`() {
        assertFalse(PropGiftLoopPolicy.shouldContinue(queryOk = true, propListSize = 1, holdsNum = 1))
    }

    @Test
    fun `道具足够时继续赠送`() {
        assertTrue(PropGiftLoopPolicy.shouldContinue(queryOk = true, propListSize = 2, holdsNum = 1))
        assertTrue(PropGiftLoopPolicy.shouldContinue(queryOk = true, propListSize = 1, holdsNum = 3))
    }
}
