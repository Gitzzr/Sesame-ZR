package fansirsqi.xposed.sesame.model

/**
 * 自动预置的候选：本机另一个账号，以及**它自己档位记录里的档位**（没有记录时为 null）。
 */
internal data class PresetAutoFillCandidate(val uid: String, val tier: PresetTier?)

/**
 * 「没能自动预置」的原因（结构化，供 UI 渲染）。
 *
 * @param uidsByTier   档位 → 该档位下的本机账号（不含 [PresetTier.MAIN]，因为有 MAIN 就不会产生提示）
 * @param unknownTierUids 没有任何档位记录的本机账号
 */
internal data class AutoFillHint(
    val uidsByTier: Map<PresetTier, List<String>>,
    val unknownTierUids: List<String>,
) {
    /**
     * 渲染成一句话。账号名交给调用方（UI 传 `AccountPreset::displayName`），
     * 测试里传恒等函数即可，从而不把 `UserMap` 依赖带进策略。
     */
    fun render(nameOf: (String) -> String): String = buildString {
        append("未自动预置好友：")
        for ((tier, uids) in uidsByTier) {
            // MAIN 不会出现（hintFor 已早退）；这里显式跳过，避免同模块误构造时输出自相矛盾的文案
            if (tier == PresetTier.MAIN) continue
            append("本机账号 ${uids.joinToString("、", transform = nameOf)} 是${tier.label}档；")
        }
        if (unknownTierUids.isNotEmpty()) {
            append("账号 ${unknownTierUids.joinToString("、", transform = nameOf)} 没有档位记录；")
        }
        append("请手动点选要保护的大号。")
    }
}

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
     * 没找到可预置的大号时，告诉用户"为什么没预置"。
     *
     * 返回**结构化数据**而不是拼好的文案：本策略要保持纯 JVM 可测（不依赖 `UserMap`），
     * 而给用户看的账号名要靠 UI 层的 `AccountPreset.displayName` 渲染 —— 直接摆 16 位 uid
     * 与同一功能里其它位置（确认页用「显示名(uid)」）风格不一致。
     *
     * 无候选、或候选里已有可预置的大号时返回 null（不需要提示）。
     */
    fun hintFor(candidates: List<PresetAutoFillCandidate>): AutoFillHint? {
        if (candidates.isEmpty()) return null
        if (candidates.any { shouldPreselect(it.tier) }) return null

        // 按 PresetTier 穷举分桶：将来新增档位枚举值时，原因会自动出现在提示里，
        // 不会静默退化成"没说原因"。
        val byTier = PresetTier.entries.associateWith { tier ->
            candidates.filter { it.tier == tier }.map { it.uid }
        }
        return AutoFillHint(
            uidsByTier = byTier.filterValues { it.isNotEmpty() },
            unknownTierUids = candidates.filter { it.tier == null }.map { it.uid },
        )
    }
}
