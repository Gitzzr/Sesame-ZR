package fansirsqi.xposed.sesame.model

/**
 * 自动预置的候选：本机另一个账号，以及**它自己档位记录里的档位**（没有记录时为 null）。
 */
internal data class PresetAutoFillCandidate(val uid: String, val tier: PresetTier?)

/**
 * 账号档位「开箱即用」预置判定。
 *
 * ## 为什么要有它（2026-09-30 K50 实测 bug）
 *
 * 原来只要目标账号自己没有档位记录，就把「本机其他账号」当成大号、按推荐项**全量预置** ——
 * 注释写的是"同机双号时开箱即用"，隐含假设是"本机另一个账号 = 大号"。
 * 但本机另一个账号完全可能是**另一个小号**：K50 上新增第三个小号时，兜底把旧小号当成了大号，
 * 于是在新账号的好友列表里默认勾了 12 项（旧小号恰好排在第 3 行，用户看到的就是
 * "第三个好友被默认配置了 12 项，可它并不是我的大号"）。
 *
 * ## 判据
 *
 * **只有候选账号自己的档位记录表明它是大号（`tier == MAIN`）时才预置**：
 * - `MAIN` → 预置推荐项（它确实被当大号配过）；
 * - `ALT` → 跳过（它是另一个小号，不该被当成大号保护）；
 * - `null`（没有记录）→ 也不预置 —— **宁可不猜**，让用户在界面上明确点选。
 *
 * 这样"该预置谁"完全由**已存在的事实**决定，不再依赖对账号角色的猜测。
 */
internal object AccountPresetAutoFillPolicy {

    /** 该候选是否应当被自动预置推荐项。 */
    fun shouldPreselect(candidateTier: PresetTier?): Boolean = candidateTier == PresetTier.MAIN

    /** 从候选里挑出该自动预置的账号（保持输入顺序）。 */
    fun preselectTargets(candidates: List<PresetAutoFillCandidate>): List<String> =
        candidates.filter { shouldPreselect(it.tier) }.map { it.uid }

    /**
     * 没找到可预置的大号时，给用户一句说明，避免误以为"功能没生效"。
     *
     * 无候选、或候选里已有可预置的大号时返回 null（不需要提示）。
     */
    fun hintFor(candidates: List<PresetAutoFillCandidate>): String? {
        if (candidates.isEmpty()) return null
        if (candidates.any { shouldPreselect(it.tier) }) return null

        val alts = candidates.filter { it.tier == PresetTier.ALT }.map { it.uid }
        val unknown = candidates.filter { it.tier == null }.map { it.uid }
        return buildString {
            append("未自动预置好友：")
            if (alts.isNotEmpty()) append("本机账号 ${alts.joinToString("、")} 是小号档；")
            if (unknown.isNotEmpty()) append("账号 ${unknown.joinToString("、")} 没有档位记录；")
            append("请手动点选要保护的大号。")
        }
    }
}
