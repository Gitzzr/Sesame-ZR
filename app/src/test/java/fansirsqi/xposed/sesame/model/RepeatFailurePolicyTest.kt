package fansirsqi.xposed.sesame.model

import fansirsqi.xposed.sesame.hook.VerificationPausePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一目标当天失败到上限就停止尝试」的契约测试。
 *
 * 判据取值来自**服务端回应本身**的字段/文案（含少量"当前无接入点、为同类场景预留"的判据），
 * 见 `docs/failure-give-up.md` 判定表；新增/修改判据时请同步更新文档与本文。
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
    fun `服务端明确声明不可重试时归为确定性失败`() {
        // 运动球任务的真实回应：errorCode=CAMP_TRIGGER_ERROR / errorMsg=海豚活动触发不可重试错误
        assertEquals(
            RepeatFailureKind.DETERMINISTIC,
            RepeatFailurePolicy.kindOf(
                "CAMP_TRIGGER_ERROR",
                "海豚活动触发不可重试错误",
                """{"errorCode":"CAMP_TRIGGER_ERROR","retryable":false}""",
            )
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
    fun `不计入的判据每一条都生效`() {
        // 逐条遍历：单条判据失效（例如文案被改）必须在这里红，而不是线上静默失效
        RepeatFailurePolicy.NON_COUNTED_HINTS.forEach { hint ->
            assertFalse("豁免判据未生效: $hint", RepeatFailurePolicy.isCountable("resultCode=$hint"))
        }
    }

    @Test
    fun `仅凭错误码或仅凭文案都能识出安全验证`() {
        // 拆成两个样本：只带码、只带文案。合并成一个样本会让"删掉任一判据仍然绿"（假锁死）
        val onlyCode = """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED"}"""
        val onlyText = """{"success":false,"errorMessage":"为了保障您的操作安全，请进行验证后继续。"}"""
        val emptyResponse = """{"success":false,"resultCode":"EMPTY_RPC_RESPONSE"}"""

        assertFalse(RepeatFailurePolicy.isCountable(onlyCode))
        assertFalse(RepeatFailurePolicy.isCountable(onlyText))
        assertFalse(RepeatFailurePolicy.isCountable(emptyResponse))
        assertTrue(RepeatFailurePolicy.isCountable("""{"resultCode":"PARAM_ILLEGAL"}"""))
        // 判不出来时按"可计数"：宁可多记，也不要因为字段缺失而漏掉真实失败
        assertTrue(RepeatFailurePolicy.isCountable())
        assertTrue(RepeatFailurePolicy.isCountable(null))
    }

    @Test
    fun `风控暂停用的每一条文案都被排除在失败预算之外`() {
        // 交叉断言：RepeatFailurePolicy 的豁免清单与 VerificationPausePolicy 的文案是两处维护，
        // 一旦漂移（例如风控加了新文案），风控响应就会被计入失败预算 → 写出"今天不再尝试"，
        // 那是项目硬规则 3 的禁忌。这里把"风控文案 ⊆ 不计入"钉死。
        VerificationPausePolicy.VERIFICATION_TEXTS.forEach { text ->
            assertFalse(
                "风控文案未被排除: $text",
                RepeatFailurePolicy.isCountable(text)
            )
        }
        assertFalse(
            "风控错误码未被排除",
            RepeatFailurePolicy.isCountable(VerificationPausePolicy.VERIFICATION_ERROR_CODE)
        )
    }

    @Test
    fun `未判定与不可用类的措辞与确定性失败不同`() {
        assertTrue(RepeatFailureKind.DETERMINISTIC.label.isNotBlank())
        assertTrue(RepeatFailureKind.TRANSIENT.label.isNotBlank())
        assertTrue(RepeatFailureKind.UNKNOWN.label.isNotBlank())
        assertFalse(RepeatFailureKind.UNKNOWN.label == RepeatFailureKind.DETERMINISTIC.label)
    }
}
