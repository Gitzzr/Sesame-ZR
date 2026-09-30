package fansirsqi.xposed.sesame.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号档位「开箱即用」预置判定的契约测试。
 *
 * 背景（2026-09-30 K50 实测 bug）：新小号自己没有档位记录时，原实现把「本机其他账号」
 * 一律当成大号预置推荐项 —— 本机另一个账号其实是**旧小号**，于是新账号的好友列表里
 * 默认给旧小号勾了 12 项（它恰好排在第 3 行，用户看到的就是"第三个好友被配置了 12 项"）。
 *
 * 契约：
 * | 候选自己的档位记录 | 是否预置 |
 * | ----------------- | ------- |
 * | MAIN（大号档）     | 是      |
 * | ALT（小号档）      | 否      |
 * | null（无记录）     | 否（宁可不猜） |
 */
class AccountPresetAutoFillPolicyTest {

    private fun candidate(uid: String, tier: PresetTier?) = PresetAutoFillCandidate(uid, tier)

    @Test
    fun `候选账号自己是大号档时预置推荐项`() {
        assertTrue(AccountPresetAutoFillPolicy.shouldPreselect(PresetTier.MAIN))
        assertEquals(
            listOf("main-uid"),
            AccountPresetAutoFillPolicy.preselectTargets(
                listOf(candidate("main-uid", PresetTier.MAIN))
            )
        )
    }

    @Test
    fun `候选账号是另一个小号时跳过`() {
        // K50 的真实场景：本机另一个账号记录里是 alt 档 → 不该被当成大号
        assertFalse(AccountPresetAutoFillPolicy.shouldPreselect(PresetTier.ALT))
        assertTrue(
            AccountPresetAutoFillPolicy.preselectTargets(
                listOf(candidate("2088922617455078", PresetTier.ALT))
            ).isEmpty()
        )
    }

    @Test
    fun `候选账号没有档位记录时不猜`() {
        assertFalse(AccountPresetAutoFillPolicy.shouldPreselect(null))
        assertTrue(
            AccountPresetAutoFillPolicy.preselectTargets(
                listOf(candidate("unknown-uid", null))
            ).isEmpty()
        )
    }

    @Test
    fun `混合候选时只预置大号档并保持顺序`() {
        val targets = AccountPresetAutoFillPolicy.preselectTargets(
            listOf(
                candidate("alt-a", PresetTier.ALT),
                candidate("main-b", PresetTier.MAIN),
                candidate("unknown-c", null),
                candidate("main-d", PresetTier.MAIN),
            )
        )

        assertEquals(listOf("main-b", "main-d"), targets)
    }

    @Test
    fun `没有候选时不提示`() {
        assertNull(AccountPresetAutoFillPolicy.hintFor(emptyList()))
    }

    @Test
    fun `已有可预置的大号时不提示`() {
        assertNull(
            AccountPresetAutoFillPolicy.hintFor(
                listOf(candidate("main-uid", PresetTier.MAIN))
            )
        )
    }

    @Test
    fun `全是小号档时提示说明并引导手动选择`() {
        val hint = requireNotNull(
            AccountPresetAutoFillPolicy.hintFor(
                listOf(candidate("2088922617455078", PresetTier.ALT))
            )
        )

        assertEquals(listOf("2088922617455078"), hint.uidsByTier[PresetTier.ALT])
        assertTrue(hint.unknownTierUids.isEmpty())

        val text = hint.render { it }   // 测试里用恒等渲染，避免引入 UserMap 依赖
        assertTrue(text.contains("2088922617455078"))
        assertTrue(text.contains("小号档"))
        assertTrue(text.contains("请手动点选"))
    }

    @Test
    fun `全部候选都没有档位记录时也要说清原因`() {
        // 同类设备上最常见的形态：本机另一个账号从未配过任何档位（没有记录文件）
        val hint = requireNotNull(
            AccountPresetAutoFillPolicy.hintFor(
                listOf(candidate("no-record-uid", null))
            )
        )

        assertTrue(hint.uidsByTier.isEmpty())
        assertEquals(listOf("no-record-uid"), hint.unknownTierUids)

        val text = hint.render { it }
        assertTrue(text.contains("no-record-uid"))
        assertTrue(text.contains("没有档位记录"))
        assertTrue(text.contains("请手动点选"))
    }

    @Test
    fun `无记录与小号档混合时提示里都要说清`() {
        val hint = requireNotNull(
            AccountPresetAutoFillPolicy.hintFor(
                listOf(
                    candidate("alt-uid", PresetTier.ALT),
                    candidate("no-record-uid", null),
                )
            )
        )

        assertEquals(listOf("alt-uid"), hint.uidsByTier[PresetTier.ALT])
        assertEquals(listOf("no-record-uid"), hint.unknownTierUids)

        val text = hint.render { it }
        assertTrue(text.contains("alt-uid"))
        assertTrue(text.contains("小号档"))
        assertTrue(text.contains("no-record-uid"))
        assertTrue(text.contains("没有档位记录"))
    }

    @Test
    fun `提示渲染使用调用方给的账号名而不是原始 uid`() {
        // UI 会传 AccountPreset::displayName，策略侧不该把 16 位 uid 直接给用户看
        val hint = requireNotNull(
            AccountPresetAutoFillPolicy.hintFor(
                listOf(candidate("2088922617455078", PresetTier.ALT))
            )
        )

        val text = hint.render { "梓锐" }
        assertTrue(text.contains("梓锐"))
        assertFalse(text.contains("2088922617455078"))
    }
}
