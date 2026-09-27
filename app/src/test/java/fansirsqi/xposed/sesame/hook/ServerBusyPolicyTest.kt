package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBusyPolicyTest {
    @Test
    fun `明确的系统繁忙文案不再重试`() {
        assertTrue(ServerBusyPolicy.isBusy("系统繁忙，请稍后再试。"))
    }

    @Test
    fun `普通1009和验证文案不按繁忙处理`() {
        assertFalse(ServerBusyPolicy.isBusy("为了保障您的操作安全，请进行验证后继续。"))
        assertFalse(ServerBusyPolicy.isBusy("非好友"))
        assertFalse(ServerBusyPolicy.isBusy(null))
        assertFalse(ServerBusyPolicy.isBusy(""))
    }
}
