package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationPausePolicyTest {

    @Test
    fun `通用业务拒绝码1009不再判定为需要安全验证`() {
        assertFalse(VerificationPausePolicy.requiresVerification("1009", ""))
        assertFalse(VerificationPausePolicy.requiresVerification("1009", "系统繁忙"))
        assertFalse(VerificationPausePolicy.requiresVerification("1009", "访问被拒绝"))
    }

    @Test
    fun `风控文案命中即判定为需要安全验证`() {
        assertTrue(
            VerificationPausePolicy.requiresVerification(
                "1009",
                "为保障您的正常访问，请进行验证后继续。"
            )
        )
        assertTrue(
            VerificationPausePolicy.requiresVerification(
                null,
                "为了保障您的操作安全，请进行验证后继续"
            )
        )
        assertTrue(VerificationPausePolicy.requiresVerification(null, "请进行验证后继续"))
    }

    @Test
    fun `风控专用错误码单独成立`() {
        assertTrue(VerificationPausePolicy.requiresVerification("RPC_VERIFICATION_REQUIRED", null))
        assertFalse(VerificationPausePolicy.requiresVerification("200", "成功"))
        assertFalse(VerificationPausePolicy.requiresVerification(null, null))
    }

    @Test
    fun `2026-09-21 实测样本仍能命中真实风控`() {
        // 实机日志原文：真实风控同时带 error=1009 与风控文案。
        assertTrue(
            VerificationPausePolicy.requiresVerification(
                "1009",
                "为了保障您的操作安全，请进行验证后继续。"
            )
        )
        // 同一天的普通系统错误，必须排除。
        assertFalse(VerificationPausePolicy.requiresVerification("3000", "系统出错，正在排查"))
    }

    @Test
    fun `触发风控的RPC方法能映射到应打开的应用入口`() {
        // 2026-09-21 日志中真实触发风控的方法。
        assertEquals(
            "蚂蚁庄园",
            VerificationPausePolicy.entryHintFor("com.alipay.antfarm.doFarmTask")
        )
        assertEquals(
            "蚂蚁森林",
            VerificationPausePolicy.entryHintFor("alipay.antforest.forest.h5.collectEnergy")
        )
        assertEquals(
            "神奇海洋",
            VerificationPausePolicy.entryHintFor("alipay.antocean.ocean.h5.queryHome")
        )
        assertEquals(
            "会员中心",
            VerificationPausePolicy.entryHintFor("alipay.membertangram.biz.rpc.student.queryCheckInModel")
        )
        assertEquals(
            "蚂蚁新村",
            VerificationPausePolicy.entryHintFor("alipay.antdodo.rpc.enterVillage")
        )
    }

    @Test
    fun `无法识别的RPC方法不猜测入口`() {
        assertNull(VerificationPausePolicy.entryHintFor(null))
        assertNull(VerificationPausePolicy.entryHintFor(""))
        assertNull(VerificationPausePolicy.entryHintFor("com.alipay.unknown.facade"))
    }

    @Test
    fun `暂停标志超过保留时长后自动过期`() {
        val markedAt = 1_000_000L
        assertFalse(
            VerificationPausePolicy.isMarkExpired(
                markedAt,
                markedAt + VerificationPausePolicy.VERIFICATION_TTL_MS
            )
        )
        assertTrue(
            VerificationPausePolicy.isMarkExpired(
                markedAt,
                markedAt + VerificationPausePolicy.VERIFICATION_TTL_MS + 1
            )
        )
    }

    @Test
    fun `旧版本写入的无时间戳标志视为已过期`() {
        // 旧版本只写令牌不写时间戳。若判为未过期，升级上来的用户会继续被永久卡住。
        assertTrue(VerificationPausePolicy.isMarkExpired(0L, System.currentTimeMillis()))
        assertTrue(VerificationPausePolicy.isMarkExpired(-1L, System.currentTimeMillis()))
    }

    @Test
    fun `启动时按标志状态决定清除标志还是重建暂停`() {
        val now = System.currentTimeMillis()

        // 没有标志：什么都不做
        assertEquals(
            PauseRestoreAction.NONE,
            VerificationPausePolicy.restoreActionFor(hasMark = false, markedAt = 0L, now = now)
        )
        // 有标志且在保留期内：重建暂停
        assertEquals(
            PauseRestoreAction.RESTORE_PAUSE,
            VerificationPausePolicy.restoreActionFor(
                hasMark = true,
                markedAt = now - 60_000L,
                now = now
            )
        )
        // 有标志但已超时：清除
        assertEquals(
            PauseRestoreAction.CLEAR_MARK,
            VerificationPausePolicy.restoreActionFor(
                hasMark = true,
                markedAt = now - VerificationPausePolicy.VERIFICATION_TTL_MS - 1,
                now = now
            )
        )
    }

    @Test
    fun `旧版本遗留的无时间戳标志在启动时被清除`() {
        // 这条路径正是「升级后仍被永久卡住」的必经分支，必须有测试兜住。
        assertEquals(
            PauseRestoreAction.CLEAR_MARK,
            VerificationPausePolicy.restoreActionFor(
                hasMark = true,
                markedAt = 0L,
                now = System.currentTimeMillis()
            )
        )
    }

    // ---------- 两套字段（error/errorMessage 与 resultCode/resultDesc） ----------

    @Test
    fun `风控藏在 resultCode 字段时同样判定成立`() {
        // 实测响应：alipay.antforest.forest.h5.queryPropList
        // {"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}
        // 旧实现只读 error/errorMessage，导致 4 天 8552 次背包查询被拒却从未触发熔断。
        assertTrue(
            VerificationPausePolicy.requiresVerificationIn(
                null, null, "RPC_VERIFICATION_REQUIRED", "触发安全验证，请人工验证后继续"
            )
        )
        // 只有文案、没有错误码时也必须成立（AntForest 记录的正是这段 resultDesc）
        assertTrue(
            VerificationPausePolicy.requiresVerificationIn(
                null, null, null, "触发安全验证，请人工验证后继续"
            )
        )
    }

    @Test
    fun `触发安全验证这段文案单独成立`() {
        // 这段措辞此前不在 VERIFICATION_TEXTS 列表里，只靠错误码会漏判
        assertTrue(VerificationPausePolicy.requiresVerification(null, "触发安全验证，请人工验证后继续"))
        assertTrue(VerificationPausePolicy.requiresVerification("1009", "触发安全验证"))
    }

    @Test
    fun `标准字段成立时不受 resultCode 影响`() {
        assertTrue(
            VerificationPausePolicy.requiresVerificationIn(
                "RPC_VERIFICATION_REQUIRED", "请进行验证后继续", null, null
            )
        )
    }

    @Test
    fun `两套字段都不像风控时不判定`() {
        assertFalse(VerificationPausePolicy.requiresVerificationIn(null, null, null, null))
        assertFalse(
            VerificationPausePolicy.requiresVerificationIn(
                "1009", "系统繁忙，请稍后再试。", "1009", "返回数据为空"
            )
        )
        assertFalse(
            VerificationPausePolicy.requiresVerificationIn(
                null, null, "SUCCESS", "操作成功"
            )
        )
    }
}
