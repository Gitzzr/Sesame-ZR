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
    private class EditorState(
        val mainList: MutableSet<String>,
        val subList: MutableSet<String>,
        val mainSelection: MutableMap<String, MutableSet<String>>,
        val subSelection: MutableMap<String, MutableSet<String>>,
    ) {
        fun members(isMainList: Boolean): MutableSet<String> = if (isMainList) mainList else subList
        fun selection(isMainList: Boolean): MutableMap<String, MutableSet<String>> =
            if (isMainList) mainSelection else subSelection

        fun checkedIds(isMainList: Boolean, friend: String): MutableSet<String> =
            selection(isMainList).getOrPut(friend) { LinkedHashSet() }
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
        // 大号名单用于小号档，小号名单用于大号档
        val isMainList = tier == PresetTier.ALT
        val listField = if (isMainList) {
            AccountFriendListPolicy.MAIN_LIST_FIELD
        } else {
            AccountFriendListPolicy.SUB_LIST_FIELD
        }

        val friends = try {
            AccountPreset.readFriendList(account.uid)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取好友列表失败", t)
            emptyList()
        }
        if (friends.isEmpty()) {
            ToastUtil.showToast(context, "未读取到好友列表，本次不改动任何名单")
            showConfirm(context, account, tier, emptySet(), emptySet(), emptyMap(), emptyMap(), onApplied)
            return
        }

        val record = try {
            AccountPreset.readRecord(account.uid)
        } catch (t: Throwable) {
            null
        }
        val ids = friends.map { it.userId }.toSet()

        fun storedList(field: String) = try {
            AccountPreset.readStoredRelationList(account.uid, field).filter { it in ids }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }

        // 从未配过时，默认把「本机其他已载入账号」勾为名单成员
        val fallback = try {
            AccountPreset.otherAccountIds(account.uid).filter { it in ids }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
        val activeStored = storedList(listField)
        val activeMembers = LinkedHashSet(if (activeStored.isNotEmpty()) activeStored else fallback)
        val otherField = if (isMainList) {
            AccountFriendListPolicy.SUB_LIST_FIELD
        } else {
            AccountFriendListPolicy.MAIN_LIST_FIELD
        }
        val otherMembers = LinkedHashSet(storedList(otherField))

        val mainMembers = if (isMainList) activeMembers else otherMembers
        val subMembers = if (isMainList) otherMembers else activeMembers

        // 初始化勾选：优先沿用上次保存的，缺失的按推荐项补全（"默认自动勾选所有推荐功能"）
        val recommended = AccountFriendListPolicy.recommendedIds(isMainList)
        fun initSelection(stored: Map<String, Set<String>>?, members: Set<String>) =
            LinkedHashMap<String, MutableSet<String>>().apply {
                members.forEach { uid ->
                    put(uid, LinkedHashSet(stored?.get(uid) ?: recommended))
                }
            }

        val state = EditorState(
            mainList = LinkedHashSet(mainMembers),
            subList = LinkedHashSet(subMembers),
            mainSelection = initSelection(
                if (isMainList) record?.mainSelection else record?.subSelection, activeMembers,
            ),
            subSelection = LinkedHashMap(),
        )
        // 另一个方向若原本有勾选，原样保留（不参与本次编辑）
        if (isMainList) {
            otherMembers.forEach { uid -> state.subSelection[uid] = LinkedHashSet(record?.subSelection?.get(uid).orEmpty()) }
        } else {
            otherMembers.forEach { uid -> state.mainSelection[uid] = LinkedHashSet(record?.mainSelection?.get(uid).orEmpty()) }
        }

        showFriendDialog(context, account, tier, isMainList, friends, state, onApplied)
    }

    /**
     * 一级：好友列表。点行 = 切换是否列入名单；点「N 项」= 进入该账号的功能勾选。
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
        val label = if (isMainList) "大号名单" else "小号名单"
        val adapter = FriendAdapter(context, friends, state, isMainList)
        val listView = ListView(context).apply {
            this.adapter = adapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                val uid = friends[position].userId
                val members = state.members(isMainList)
                if (!members.remove(uid)) {
                    members.add(uid)
                    // 新加入名单的账号按推荐项初始化勾选
                    state.checkedIds(isMainList, uid).apply {
                        if (isEmpty()) addAll(AccountFriendListPolicy.recommendedIds(isMainList))
                    }
                }
                adapter.notifyDataSetChanged()
            }
        }
        // 裸 ListView 放进 AlertDialog 会因 wrap_content 塌成 0 高度，套一层固定高度的容器
        val height = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val holder = android.widget.FrameLayout(context).apply {
            addView(
                listView,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, height,
                ),
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("$label：共 ${friends.size} 位好友")
            .setView(holder)
            .setPositiveButton("下一步") { _, _ ->
                showConfirm(
                    context, account, tier,
                    state.mainList, state.subList,
                    state.mainSelection, state.subSelection,
                    onApplied,
                )
            }
            .setNeutralButton("按推荐重置", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // 覆盖中立按钮的默认行为：只重置勾选，不关闭对话框
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val members = state.members(isMainList)
                members.forEach { uid ->
                    state.checkedIds(isMainList, uid).apply {
                        clear()
                        addAll(AccountFriendListPolicy.recommendedIds(isMainList))
                    }
                }
                adapter.notifyDataSetChanged()
                ToastUtil.showToast(context, "已把 ${members.size} 个账号的勾选重置为推荐项")
            }
        }
        dialog.show()
    }

    /** 一级列表的适配器：每行 = 勾选框 + 名称 + 「N 项」（点击进入功能勾选） */
    private class FriendAdapter(
        private val context: Context,
        private val friends: List<AccountPreset.Friend>,
        private val state: EditorState,
        private val isMainList: Boolean,
    ) : BaseAdapter() {

        override fun getCount() = friends.size
        override fun getItem(position: Int) = friends[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = parent?.context ?: context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val friend = friends[position]
            val members = state.members(isMainList)

            row.addView(CheckBox(ctx).apply {
                isChecked = friend.userId in members
                isFocusable = false
                isClickable = false
            })
            row.addView(TextView(ctx).apply {
                text = friend.name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 只读，避免在渲染时为每个好友创建空条目
            val count = state.selection(isMainList)[friend.userId]?.size ?: 0
            row.addView(TextView(ctx).apply {
                text = if (friend.userId in members) "已启用 $count 项  ▸" else "未列入  ▸"
                textSize = 13f
                setPadding(dp(8), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    // 未列入名单则先自动列入，再配置功能
                    if (friend.userId !in members) members.add(friend.userId)
                    showFeatureDialog(
                        ctx, friend, isMainList,
                        state.checkedIds(isMainList, friend.userId),
                    ) { notifyDataSetChanged() }
                }
            })
            return row
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

        val groups = listOf(
            FriendListKind.SERVICE to "服务 TA（消耗自己、利于对方）",
            FriendListKind.EXPLOIT to "对 TA 索取 / 干扰（损人利己）",
            FriendListKind.EXCLUSION to "TA 的豁免项（填人 = 不做该动作）",
            FriendListKind.PROTECT to "独立体系",
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
            .setTitle("${friend.name}：选择要启用的功能")
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

    private fun buildFeatureText(ref: FriendListRef, isMainList: Boolean): String {
        val suffix = when {
            !AccountFriendListPolicy.isSelectable(ref, isMainList) -> "（大号要收小号能量，不可选）"
            !ref.effective -> "（当前版本不生效）"
            ref.isCounted -> "（每日 ${ref.countDefault} 次）"
            else -> ""
        }
        return ref.featureLabel + suffix
    }

    // ================================================================== 确认

    private fun showConfirm(
        context: Context,
        account: Account,
        tier: PresetTier,
        mainList: Set<String>,
        subList: Set<String>,
        mainSelection: Map<String, Set<String>>,
        subSelection: Map<String, Set<String>>,
        onApplied: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle("确认应用【${tier.label}推荐配置】？")
            .setMessage(buildConfirmText(account, tier, mainList, subList, mainSelection, subSelection))
            .setPositiveButton("确认应用") { _, _ ->
                val result = try {
                    AccountPreset.apply(
                        tier = tier,
                        targetUid = account.uid,
                        context = context,
                        mainList = mainList,
                        subList = subList,
                        mainSelection = mainSelection,
                        subSelection = subSelection,
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
        mainList: Set<String>,
        subList: Set<String>,
        mainSelection: Map<String, Set<String>>,
        subSelection: Map<String, Set<String>>,
    ): String {
        val isMainTier = tier == PresetTier.MAIN
        val activeList = if (isMainTier) subList else mainList
        val activeSel = if (isMainTier) subSelection else mainSelection
        val activeLabel = if (isMainTier) "小号名单" else "大号名单"

        // 聚合：哪些功能会被写入
        val enabled = LinkedHashMap<String, MutableSet<String>>()
        activeList.forEach { uid ->
            activeSel[uid].orEmpty().forEach { fid ->
                val ref = AccountFriendListPolicy.byId(fid) ?: return@forEach
                if (!AccountFriendListPolicy.isSelectable(ref, !isMainTier)) return@forEach
                enabled.getOrPut(fid) { LinkedHashSet() }.add(uid)
            }
        }

        val sb = StringBuilder()
        sb.append("账号：").append(account.showName).append("（").append(account.uid).append("）\n")
        sb.append("当前：").append(AccountPreset.tierSummary(account.uid)).append("\n\n")
        sb.append("$activeLabel：").append(activeList.size).append(" 个账号")
        if (activeList.isEmpty()) {
            sb.append("（未指定 → 不改动任何功能名单）\n\n")
        } else {
            sb.append("，共启用 ").append(enabled.size).append(" 项功能：\n")
            enabled.entries.take(8).forEach { (fid, uids) ->
                val ref = AccountFriendListPolicy.byId(fid) ?: return@forEach
                sb.append("· ").append(ref.featureLabel)
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
                if (subList.isEmpty()) {
                    sb.append("· 未指定小号名单：不改动排除名单\n")
                } else {
                    sb.append("· 把小号（").append(subList.size).append(" 个）从「不收能量」等 ")
                        .append(AccountFriendListPolicy.exclusionLists().size)
                        .append(" 个排除名单里**移除**\n")
                    sb.append("  ⚠️ 这是刻意的：大号需要收取小号的能量\n")
                }
            }

            PresetTier.ALT -> {
                sb.append("· 索取/干扰类（贴罚单、丢肥料、抢好友…）保持关闭，名单一律清空\n")
                sb.append("· 未勾选的服务类功能保持关闭，不动其名单\n")
            }
        }
        sb.append("\n执行后会重载目标应用的配置。")
        return sb.toString()
    }
}
