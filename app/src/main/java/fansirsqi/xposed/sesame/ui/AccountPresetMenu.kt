package fansirsqi.xposed.sesame.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.AccountFriendListPolicy
import fansirsqi.xposed.sesame.model.AccountPreset
import fansirsqi.xposed.sesame.model.AccountPresetPolicy
import fansirsqi.xposed.sesame.model.FriendListKind
import fansirsqi.xposed.sesame.model.FriendListRef
import fansirsqi.xposed.sesame.model.PresetTier
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import java.util.LinkedHashSet

/**
 * 「账号档位（大号 / 小号）」一键切换的交互入口。
 *
 * 关键流程：
 *
 * ```
 * 选账号 → 选档位（大号推荐配置 / 小号推荐配置）
 *   → 名单 + 功能配置页
 *        一级：好友列表，每行 = [勾选是否列入名单] 名称「已启用 N 项 →」
 *              点行空白 → 切换是否列入名单；点「N 项」→ 进入二级
 *        二级：该账号的**完整功能清单**（26 项，按 4 组分节）
 *              默认勾选全部推荐项；可「全选推荐 / 全不选」
 *   → 确认页（汇总：N 个账号、共 M 项功能将被写入）
 *   → 应用
 * ```
 *
 * 功能清单的数据来源是 [AccountFriendListPolicy.FRIEND_LISTS]（一个好友名单字段 = 一项功能），
 * 推荐项见 [AccountFriendListPolicy.recommendedIds]。
 */
object AccountPresetMenu {
    private const val TAG = "AccountPresetMenu"

    fun show(context: Context, userList: List<UserEntity>, onApplied: () -> Unit) {
        val accounts = resolveAccounts(userList)
        if (accounts.isEmpty()) {
            ToastUtil.showToast(context, "未发现任何账号配置")
            return
        }
        AlertDialog.Builder(context)
            .setTitle("选择要切换档位的账号")
            .setItems(accounts.map { "${it.showName}  ·  ${AccountPreset.tierSummary(it.uid)}" }.toTypedArray()) { _, which ->
                showTierPicker(context, accounts[which], onApplied)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class Account(val uid: String, val showName: String)

    /**
     * 编辑过程中的状态：两份名单 + 两个方向各自的「逐好友功能勾选」。
     * 只编辑与当前档位对应的那个方向，另一个方向原样带到确认页。
     */
    /**
     * 编辑过程中的状态：**只有一份勾选表**（好友 → 要启用的功能 id）。
     *
     * 没有"成员名单"这一层 —— 值为空集合的好友就是不处理；好友集合与功能集合
     * 来自同一份数据，因此不会出现"名单与勾选不一致"。
     */
    private class EditorState(
        val selection: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
    ) {
        /**
         * 取该好友的勾选集合。
         *
         * **首次打开某个好友时按推荐项预置** —— 这就是"默认自动勾选所有推荐功能"。
         * 已经配过的好友沿用上次的勾选，不会被重置。
         */
        fun checkedIds(friend: String, recommended: Set<String>): MutableSet<String> =
            selection.getOrPut(friend) { LinkedHashSet(recommended) }

        /** 会被处理的好友（至少勾了 1 项功能） */
        fun activeFriends(): Set<String> = selection.filterValues { it.isNotEmpty() }.keys
    }

    private fun resolveAccounts(userList: List<UserEntity>): List<Account> {
        val fromUi = userList.mapNotNull { user ->
            user.userId?.takeIf { it.isNotBlank() }?.let { Account(it, user.showName.ifBlank { it }) }
        }
        if (fromUi.isNotEmpty()) return fromUi
        return try {
            AccountPreset.otherAccountIds("").map { Account(it, AccountPreset.displayName(it)) }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取账号列表失败", t)
            emptyList()
        }
    }

    private fun showTierPicker(context: Context, account: Account, onApplied: () -> Unit) {
        val items = arrayOf(
            "【${PresetTier.MAIN.label}推荐配置】 · ${PresetTier.MAIN.summary}",
            "【${PresetTier.ALT.label}推荐配置】 · ${PresetTier.ALT.summary}",
        )
        AlertDialog.Builder(context)
            .setTitle("账号：${account.showName}")
            .setItems(items) { _, which ->
                val tier = if (which == 0) PresetTier.MAIN else PresetTier.ALT
                openEditor(context, account, tier, onApplied)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    // ================================================================== 名单 + 功能配置

    private fun openEditor(context: Context, account: Account, tier: PresetTier, onApplied: () -> Unit) {
        // 沿用既有命名：isMainList = true 表示"本机是小号、要服务大号"这个方向，
        // 它决定推荐项与"豁免项是否可选"，与"名单"无关。
        val isMainList = tier == PresetTier.ALT

        val friends = try {
            AccountPreset.readFriendList(account.uid)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取好友列表失败", t)
            emptyList()
        }
        if (friends.isEmpty()) {
            ToastUtil.showToast(context, "未读取到好友列表，无法配置")
            showConfirm(context, account, tier, emptyMap(), onApplied)
            return
        }

        val ids = friends.map { it.userId }.toSet()
        val recommended = AccountFriendListPolicy.recommendedIds(isMainList)
        val state = EditorState()

        // 上次该档位的勾选（只是默认值，不是配置）；只保留当前仍是好友的
        val stored = try {
            AccountPreset.readRecord(account.uid)?.selectionFor(tier).orEmpty()
        } catch (t: Throwable) {
            emptyMap()
        }.filterKeys { it in ids }

        if (stored.isNotEmpty()) {
            stored.forEach { (uid, sel) -> state.selection[uid] = LinkedHashSet(sel) }
        } else {
            // 从未配过：把「本机其他已载入账号」按推荐项预置好，同机双号时开箱即用
            try {
                AccountPreset.otherAccountIds(account.uid)
                    .filter { it in ids }
                    .forEach { uid -> state.selection[uid] = LinkedHashSet(recommended) }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "读取本机账号失败", t)
            }
        }

        showFriendDialog(context, account, tier, isMainList, friends, state, onApplied)
    }

    /**
     * 一级：好友列表。点整行 = 进入该账号的功能勾选（没有"是否列入名单"这一层 ——
     * 名单的成员就是勾了至少 1 项功能的好友）。
     */
    private fun showFriendDialog(
        context: Context,
        account: Account,
        tier: PresetTier,
        isMainList: Boolean,
        friends: List<AccountPreset.Friend>,
        state: EditorState,
        onApplied: () -> Unit,
    ) {
        val adapter = FriendAdapter(context, friends, state)
        val listView = ListView(context).apply {
            this.adapter = adapter
            // 点某一行 = 为该账号选择要启用哪些功能（不再有"是否列入名单"这一层）
            setOnItemClickListener { _, _, position, _ ->
                val friend = friends[position]
                val recommended = AccountFriendListPolicy.recommendedIds(isMainList)
                showFeatureDialog(
                    context, friend, isMainList,
                    state.checkedIds(friend.userId, recommended),
                ) {
                    adapter.notifyDataSetChanged()
                }
            }
        }

        // 裸 ListView 放进 AlertDialog 会因 wrap_content 塌成 0 高度：
        // 套一层纵向容器，里面放「说明文字 + 固定高度的列表」。
        val height = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(TextView(context).apply {
                text = EXPLAIN_TEXT
                textSize = 13f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, pad)
            })
            addView(
                listView,
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, height,
                ),
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("批量启用功能 · ${tier.label}推荐配置（共 ${friends.size} 位好友）")
            .setView(holder)
            .setPositiveButton("下一步") { _, _ ->
                showConfirm(
                    context, account, tier,
                    state.selection.mapValues { it.value.toSet() },
                    onApplied,
                )
            }
            .setNeutralButton("按推荐重置", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // 覆盖中立按钮的默认行为：只重置勾选，不关闭对话框
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val active = state.activeFriends()
                val recommended = AccountFriendListPolicy.recommendedIds(isMainList)
                active.forEach { uid ->
                    state.selection[uid] = LinkedHashSet(recommended)
                }
                adapter.notifyDataSetChanged()
                ToastUtil.showToast(context, "已把 ${active.size} 个账号的勾选重置为推荐项")
            }
        }
        dialog.show()
    }

    /** 一级列表的适配器：每行 = 勾选框 + 名称 + 「N 项」（点击进入功能勾选） */
    /** 一级列表：每行 = 好友名 + 「已配置 N 项」（点击进入功能清单） */
    private class FriendAdapter(
        private val context: Context,
        private val friends: List<AccountPreset.Friend>,
        private val state: EditorState,
    ) : BaseAdapter() {

        override fun getCount() = friends.size
        override fun getItem(position: Int) = friends[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = parent?.context ?: context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val friend = friends[position]
            // 只读，避免在渲染时为每个好友创建空条目
            val count = state.selection[friend.userId]?.size ?: 0
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(TextView(ctx).apply {
                    text = friend.name
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    )
                })
                addView(TextView(ctx).apply {
                    text = if (count > 0) "已配置 $count 项  ▸" else "未配置  ▸"
                    textSize = 13f
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                })
            }
        }
    }

    /**
     * 二级：该账号的**完整功能清单**（26 项，按语义分 4 组），默认可为推荐项。
     * 不可选的方向（大号档下的排除项）会置灰并说明原因。
     */
    private fun showFeatureDialog(
        context: Context,
        friend: AccountPreset.Friend,
        isMainList: Boolean,
        checked: MutableSet<String>,
        onChanged: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val snapshot = LinkedHashSet(checked)
        val boxes = LinkedHashMap<String, CheckBox>()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        container.addView(TextView(context).apply {
            text = featureExplainText(friend.name)
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, dp(4))
        })

        val groups = listOf(
            FriendListKind.SERVICE to "服务 TA —— 消耗自己、利于对方",
            FriendListKind.EXPLOIT to "对 TA 索取 / 干扰 —— 损人利己",
            FriendListKind.EXCLUSION to "TA 的豁免项 —— 勾上表示「不对 TA 做这件事」",
            FriendListKind.PROTECT to "独立体系 —— 复活能量保护名单，与上面互不影响",
        )
        for ((kind, title) in groups) {
            val items = AccountFriendListPolicy.ofKind(kind)
            if (items.isEmpty()) continue
            container.addView(TextView(context).apply {
                text = title
                textSize = 13f
                setPadding(0, dp(12), 0, dp(4))
                setTextColor(0xFF888888.toInt())
            })
            for (ref in items) {
                val selectable = AccountFriendListPolicy.isSelectable(ref, isMainList)
                val box = CheckBox(context).apply {
                    text = buildFeatureText(ref, isMainList)
                    textSize = 15f
                    isChecked = ref.id in snapshot
                    isEnabled = selectable
                    alpha = if (selectable) 1f else 0.45f
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) snapshot.add(ref.id) else snapshot.remove(ref.id)
                    }
                }
                container.addView(box)
                boxes[ref.id] = box
            }
        }

        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("批量启用功能 · ${friend.name}")
            .setView(scroll)
            .setPositiveButton("确定") { _, _ ->
                checked.clear()
                checked.addAll(snapshot)
                onChanged()
            }
            .setNeutralButton("全选推荐", null)
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val recommended = AccountFriendListPolicy.recommendedIds(isMainList)
                snapshot.clear()
                snapshot.addAll(recommended)
                boxes.forEach { (id, box) ->
                    if (box.isEnabled) box.isChecked = id in recommended
                }
            }
        }
        dialog.show()
    }

    /**
     * 单个功能项的文案。
     *
     * 刻意**不写**「（每日 N 次）」这类参数描述 —— 本页只决定"启用哪些功能"，
     * 参数的默认值在 [featureExplainText] 里统一交代，避免让人以为是在这里配参数。
     */
    private fun buildFeatureText(ref: FriendListRef, isMainList: Boolean): String {
        val suffix = when {
            !AccountFriendListPolicy.isSelectable(ref, isMainList) -> "（大号要收小号能量，不可选）"
            !ref.effective -> "（当前版本不生效）"
            else -> ""
        }
        return ref.featureLabel + suffix
    }

    /** 一级页的说明：讲清"只批量启用、不配参数" */
    private const val EXPLAIN_TEXT =
        "点某个账号，为它选择要启用哪些功能（默认已按推荐勾好）。\n" +
            "这里只负责「批量启用功能」，不涉及具体参数 —— 参数仍在各模块的账号配置里设置。"

    /** 二级页的说明：把"批量启用"与"具体参数配置"的边界写清楚 */
    private fun featureExplainText(friendName: String): String = buildString {
        append("勾选 = 把「$friendName」加入该功能的「好友列表」，并打开它所需的开关；")
        append("不勾则该功能保持账号当前配置，不做任何改动。\n\n")
        append("本页只决定「启用哪些功能」，不配置参数 —— 例如浇水的克数、各功能的执行时间等，")
        append("仍在「蚂蚁森林 / 小鸡庄园 / …」的账号配置里单独修改。\n\n")
        append("浇水 / 帮喂小鸡 / 送麦子是「选择 + 计数」型名单：勾选后会带一个默认次数 1，")
        append("次数同样可在账号配置里调整。\n\n")
        append("注：浇水 / 帮喂小鸡 / 送卡片 / 助力好友 没有独立开关，名单里有谁就会对谁执行；")
        append("取消勾选只是不动它，需要停用请到该功能的账号配置里清空名单。")
    }

    // ================================================================== 确认

    private fun showConfirm(
        context: Context,
        account: Account,
        tier: PresetTier,
        selection: Map<String, Set<String>>,
        onApplied: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle("确认应用【${tier.label}推荐配置】？")
            .setMessage(buildConfirmText(account, tier, selection))
            .setPositiveButton("确认应用") { _, _ ->
                val result = try {
                    AccountPreset.apply(
                        tier = tier,
                        targetUid = account.uid,
                        context = context,
                        selection = selection,
                    )
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "应用档位失败", t)
                    null
                }
                if (result == null) {
                    ToastUtil.showToast(context, "应用失败，请查看运行日志")
                } else {
                    ToastUtil.showToast(context, result.message)
                    if (result.success) onApplied()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildConfirmText(
        account: Account,
        tier: PresetTier,
        selection: Map<String, Set<String>>,
    ): String {
        val isMainTier = tier == PresetTier.MAIN
        val active = selection.filterValues { it.isNotEmpty() }

        // 聚合：哪些功能会被写入、各涉及多少个账号
        val enabled = LinkedHashMap<String, MutableSet<String>>()
        active.forEach { (uid, ids) ->
            ids.forEach { fid ->
                val ref = AccountFriendListPolicy.byId(fid) ?: return@forEach
                if (!AccountFriendListPolicy.isSelectable(ref, !isMainTier)) return@forEach
                enabled.getOrPut(fid) { LinkedHashSet() }.add(uid)
            }
        }

        val sb = StringBuilder()
        sb.append("账号：").append(account.showName).append("（").append(account.uid).append("）\n")
        sb.append("当前：").append(AccountPreset.tierSummary(account.uid)).append("\n\n")

        if (active.isEmpty()) {
            sb.append("未选择任何账号 → 不改动任何功能名单，也不打开任何开关\n\n")
        } else {
            sb.append("将对 ").append(active.size).append(" 个账号批量启用 ")
                .append(enabled.size).append(" 项功能\n")
            sb.append("（只写入下列好友列表并打开对应开关，不改具体参数）\n")
            enabled.entries.take(8).forEach { (fid, uids) ->
                val ref = AccountFriendListPolicy.byId(fid) ?: return@forEach
                sb.append("· ").append(ref.label)
                    .append("（").append(uids.size).append(" 个账号）\n")
            }
            if (enabled.size > 8) sb.append("· …另有 ").append(enabled.size - 8).append(" 项\n")
            sb.append("\n")
        }

        when (tier) {
            PresetTier.MAIN -> {
                sb.append("· 开启各模块的收益与进度类功能（覆盖 ")
                    .append(AccountPresetPolicy.overrideCount(tier)).append(" 项设置）\n")
                sb.append("· 保持贴罚单、丢肥料、请走小摊等纯干扰项关闭\n")
                if (active.isEmpty()) {
                    sb.append("· 未选择账号：不改动排除名单\n")
                } else {
                    sb.append("· 把本次选中的账号（").append(active.size).append(" 个）从「不收能量」等 ")
                        .append(AccountFriendListPolicy.exclusionLists().size)
                        .append(" 个排除名单里移除\n")
                    sb.append("  ⚠️ 这是刻意的：大号需要收取小号的能量\n")
                }
            }

            PresetTier.ALT -> {
                sb.append("· 索取/干扰类（贴罚单、丢肥料、抢好友…）的开关保持关闭（不改动其名单）\n")
                sb.append("· 未勾选的功能一律不动：名单保持现状、开关保持关闭\n")
            }
        }
        sb.append("\n执行后会重载目标应用的配置。")
        return sb.toString()
    }
}
