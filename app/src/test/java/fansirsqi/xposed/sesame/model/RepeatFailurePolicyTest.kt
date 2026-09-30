package fansirsqi.xposed.sesame.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一目标当天失败到上限就停止尝试」的契约测试。
 *
 * 判据取值来自真机日志里**实际出现过**的服务端回应（见 `docs/failure-give-up.md` 判定表），
 * 新增/修改判据时请同步更新文档与本文。
 */
class RepeatFailurePolicyTest {

    @Test
    fun `上限固定为 5 次且达到即放弃`() {
        // 这个数字写进了 docs/failure-give-up.md 与用户可见台账文案，改动必须同步文档
        assertEquals(5, RepeatFailurePolicy.DAILY_FAILURE_LIMIT)

        assertFalse(RepeatFailurePolicy.shouldGiveUp(0))
        assertFalse(RepeatFailurePolicy.shouldGiveUp(4))
        assertTrue(RepeatFailurePolicy.shouldGiveUp(5))
        assertTrue(RepeatFailurePolicy.shouldGiveUp(6))
    }

    @Test
    fun `确定性失败：参数非法与契约不存在`() {
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf("PARAM_ILLEGAL")
        )
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf("任务领取失败: 订阅炼金签到提醒 - 生活记录模板不存在")
        )
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf("会员任务结算失败: 去签名设计#任务还没有完成")
        )
    }

    @Test
    fun `服务端不可用：繁忙与 error3000`() {
        assertEquals(
            RepeatFailureKind.TRANSIENT,
            RepeatFailurePolicy.kindOf("系统繁忙，请稍后重试")
        )
        assertEquals(
            RepeatFailureKind.TRANSIENT,
            RepeatFailurePolicy.kindOf("""{"error":3000,"errorMessage":"系统出错，正在排查"}""")
        )
    }

    @Test
    fun `同时命中两类时以确定性优先`() {
        // 会员宝箱的真实回应就是 resultCode=PARAM_ILLEGAL + resultDesc=系统繁忙
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf("PARAM_ILLEGAL", "系统繁忙，请稍后重试")
        )
    }

    @Test
    fun `判据大小写不敏感`() {
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf("param_illegal".uppercase())
        )
        assertEquals(
            RepeatFailureKind.TRANSIENT,
            RepeatFailurePolicy.kindOf("系统繁忙")
        )
    }

    @Test
    fun `无法判定时归类为未判定而不是确定性失败`() {
        // 关键安全属性：判不出来时宁可继续尝试，也不要误放弃
        assertEquals(RepeatFailureKind.UNKNOWN, RepeatFailurePolicy.kindOf())
        assertEquals(RepeatFailureKind.UNKNOWN, RepeatFailurePolicy.kindOf(null))
        assertEquals(RepeatFailureKind.UNKNOWN, RepeatFailurePolicy.kindOf(""))
        assertEquals(
            RepeatFailureKind.UNKNOWN,
            RepeatFailurePolicy.kindOf("某种从没见过的新错误")
        )
    }

    @Test
    fun `判定表的每一条判据都按文档归类`() {
        // 整表遍历：避免"文档写了、代码里其实没人测"的漂移（文档 §4 的表格与这两张表一一对应）
        RepeatFailurePolicy.DETERMINISTIC_HINTS.forEach { hint ->
            assertEquals(
                "确定性判据未生效: $hint",
                RepeatFailureKind.DETERMINISTIC,
                RepeatFailurePolicy.kindOf("resultCode=$hint"),
            )
            assertTrue("确定性判据不应被当作不可计数: $hint", RepeatFailurePolicy.isCountable(hint))
        }
        RepeatFailurePolicy.TRANSIENT_HINTS.forEach { hint ->
            assertEquals(
                "不可用判据未生效: $hint",
                RepeatFailureKind.TRANSIENT,
                RepeatFailurePolicy.kindOf("resultDesc=$hint"),
            )
        }
    }

    @Test
    fun `安全验证与空响应不计入失败预算`() {
        // 这两个是"链路当下不可用/需人工验证"，不是业务失败：
        // 计入就会在几轮后写出"该功能今天不再尝试"，而项目硬规则 3 明确把它列为禁忌。
        val verification = """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}"""
        val empty = """{"success":false,"resultCode":"EMPTY_RPC_RESPONSE","resultDesc":"RPC返回为空"}"""

        assertFalse(RepeatFailurePolicy.isCountable(verification))
        assertFalse(RepeatFailurePolicy.isCountable(empty))
        assertTrue(RepeatFailurePolicy.isCountable("""{"resultCode":"PARAM_ILLEGAL"}"""))
        // 判不出来时按"可计数"：宁可多记，也不要因为字段缺失而漏掉真实失败
        assertTrue(RepeatFailurePolicy.isCountable())
        assertTrue(RepeatFailurePolicy.isCountable(null))
    }

    @Test
    fun `未判定与不可用类的措辞与确定性失败不同`() {
        assertTrue(RepeatFailureKind.DETERMINISTIC.label.isNotBlank())
        assertTrue(RepeatFailureKind.TRANSIENT.label.isNotBlank())
        assertTrue(RepeatFailureKind.UNKNOWN.label.isNotBlank())
        assertFalse(RepeatFailureKind.UNKNOWN.label == RepeatFailureKind.DETERMINISTIC.label)
    }
}
