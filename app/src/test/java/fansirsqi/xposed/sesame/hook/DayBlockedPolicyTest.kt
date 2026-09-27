package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayBlockedPolicyTest {

    @Test
    fun `实测样本判定为今日被限制`() {
        // 2026-09-27 蚂蚁森林赠送道具的真实响应
        assertTrue(
            DayBlockedPolicy.isDayBlocked(
                "ROBOT_PUNISHMENT",
                "您近期操作存在异常，请明天再试"
            )
        )
    }

    @Test
    fun `只给文案不给码也成立`() {
        assertTrue(DayBlockedPolicy.isDayBlocked(null, "您近期操作存在异常，请明天再试"))
    }

    @Test
    fun `系统繁忙与安全验证都不算今日被限制`() {
        // 系统繁忙隔一会儿还有机会，安全验证要用户去处理，两者都不该整天停掉
        assertFalse(DayBlockedPolicy.isDayBlocked("1009", "系统繁忙，请稍后再试。"))
        assertFalse(DayBlockedPolicy.isDayBlocked("1009", "为了保障您的操作安全，请进行验证后继续。"))
    }

    @Test
    fun `空值不误判`() {
        assertFalse(DayBlockedPolicy.isDayBlocked(null, null))
        assertFalse(DayBlockedPolicy.isDayBlocked("", ""))
    }
}
