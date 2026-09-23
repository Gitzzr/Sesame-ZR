package fansirsqi.xposed.sesame.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.entity.MapperEntity
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.AccountFriendListPolicy
import fansirsqi.xposed.sesame.model.AccountPreset
import fansirsqi.xposed.sesame.model.AccountPresetPolicy
import fansirsqi.xposed.sesame.model.PresetTier
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.ui.widget.ListDialog
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import java.util.LinkedHashSet

/**
 * 「账号档位（大号 / 小号）」一键切换的交互入口。
 *
 * 四步：选账号 → 选档位 → **选关系名单** → 确认并应用。
 *
 * 「选关系名单」是「大号列表配置」的核心：
 * 选一次并保存后，之后可直接复用，不必再把每个功能的好友列表单独配一遍。
 * - 选【小号推荐配置】→ 选的是**大号名单**：小号会把它套用到浇水、赠送道具、帮喂小鸡…
 *   等服务类名单，并写入「不收能量」等排除名单（不偷大号）；
 * - 选【大号推荐配置】→ 选的是**小号名单**：大号会把小号从「不收能量」等排除名单里移除
 *   （大号要收小号的能量）。
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
                val account = accounts[which]
                showTierPicker(context, account, onApplied)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class Account(val uid: String, val showName: String)

    private fun resolveAccounts(userList: List<UserEntity>): List<Account> {
        val fromUi = userList.mapNotNull { user ->
            user.userId?.takeIf { it.isNotBlank() }?.let { Account(it, user.showName.ifBlank { it }) }
        }
        if (fromUi.isNotEmpty()) return fromUi
        // UI 还没载入用户时，退回配置目录里的账号文件夹
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
                showRelationPicker(context, account, tier, onApplied)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    /**
     * 选关系名单。默认勾选上次保存的标记；若从未配过，则把「本机其他已载入账号」作为默认候选
     * （同机双号时这通常就是正确答案；跨设备时靠好友列表手动指定）。
     */
    private fun showRelationPicker(
        context: Context,
        account: Account,
        tier: PresetTier,
        onApplied: () -> Unit,
    ) {
        val isAlt = tier == PresetTier.ALT
        val fieldCode = if (isAlt) {
            AccountFriendListPolicy.MAIN_LIST_FIELD
        } else {
            AccountFriendListPolicy.SUB_LIST_FIELD
        }
        val label = if (isAlt) "大号名单" else "小号名单"
        val otherField = if (isAlt) {
            AccountFriendListPolicy.SUB_LIST_FIELD
        } else {
            AccountFriendListPolicy.MAIN_LIST_FIELD
        }

        val stored = try {
            AccountPreset.readStoredRelationList(account.uid, fieldCode)
        } catch (t: Throwable) {
            emptySet()
        }
        val otherStored = try {
            AccountPreset.readStoredRelationList(account.uid, otherField)
        } catch (t: Throwable) {
            emptySet()
        }

        val friends = try {
            AccountPreset.readFriendList(account.uid)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取好友列表失败", t)
            emptyList()
        }
        if (friends.isEmpty()) {
            ToastUtil.showToast(context, "未读取到好友列表，无法选择$label")
            showConfirm(context, account, tier, emptySet(), otherStored, onApplied)
            return
        }

        val fallback = try {
            AccountPreset.otherAccountIds(account.uid).toSet()
        } catch (t: Throwable) {
            emptySet()
        }
        val preselected = if (stored.isNotEmpty()) stored else fallback
        val holder = SelectModelField(
            "accountPresetRelation",
            "选择$label",
            LinkedHashSet(preselected.filter { id -> friends.any { it.userId == id } }),
            friends.map { SimpleEntity(it.userId, it.name) },
        )

        try {
            ListDialog.show(context, "选择$label（共 ${friends.size} 位好友）", holder)
            val dialogField = ListDialog::class.java.getDeclaredField("listDialog")
            dialogField.isAccessible = true
            val dialog = dialogField.get(null) as? AlertDialog
            if (dialog == null) {
                showConfirm(context, account, tier, emptySet(), otherStored, onApplied)
                return
            }
            // ListDialog 的「关闭」按钮不带回调，沿用 CustomSettings 的既有做法：挂 dismiss 监听读回选中集合。
            dialog.setOnDismissListener {
                val selected = holder.value?.filterNotNull()?.toSet() ?: emptySet()
                if (isAlt) {
                    showConfirm(context, account, tier, selected, otherStored, onApplied)
                } else {
                    showConfirm(context, account, tier, otherStored, selected, onApplied)
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "打开好友列表对话框失败", t)
            showConfirm(context, account, tier, emptySet(), otherStored, onApplied)
        }
    }

    private fun showConfirm(
        context: Context,
        account: Account,
        tier: PresetTier,
        mainList: Set<String>,
        subList: Set<String>,
        onApplied: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle("确认应用【${tier.label}推荐配置】？")
            .setMessage(buildConfirmText(account, tier, mainList, subList))
            .setPositiveButton("确认应用") { _, _ ->
                val result = try {
                    AccountPreset.apply(
                        tier = tier,
                        targetUid = account.uid,
                        context = context,
                        mainList = mainList,
                        subList = subList,
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
    ): String {
        val sb = StringBuilder()
        sb.append("账号：").append(account.showName).append("（").append(account.uid).append("）\n")
        sb.append("当前：").append(AccountPreset.tierSummary(account.uid)).append("\n\n")
        when (tier) {
            PresetTier.MAIN -> {
                sb.append("· 开启各模块的收益与进度类功能（覆盖 ")
                    .append(AccountPresetPolicy.overrideCount(tier)).append(" 项设置）\n")
                sb.append("· 保持贴罚单、丢肥料、请走小摊等纯干扰项关闭\n")
                if (subList.isEmpty()) {
                    sb.append("· 未指定小号名单：不改动排除名单\n")
                } else {
                    sb.append("· 把小号（").append(subList.size).append(" 个）从「不收能量」等 ")
                        .append(AccountFriendListPolicy.mainExclusionListsToPurge().size)
                        .append(" 个排除名单里**移除**\n")
                    sb.append("  ⚠️ 这是刻意的：大号需要收取小号的能量\n")
                }
            }

            PresetTier.ALT -> {
                sb.append("· 索取/干扰类（贴罚单、丢肥料、抢好友…）保持关闭，名单一律清空\n")
                if (mainList.isEmpty()) {
                    sb.append("· 未指定大号名单：所有跨账号功能保持关闭、名单保持现状\n")
                    sb.append("  小号不会去动任何人，但也不会主动服务大号\n")
                } else {
                    sb.append("· 把大号（").append(mainList.size).append(" 个）写入 ")
                        .append(AccountFriendListPolicy.altListsFilledWithMain().size)
                        .append(" 个功能名单并打开相关开关：\n    ")
                        .append(
                            AccountFriendListPolicy.altListsFilledWithMain()
                                .take(6).joinToString("、") { it.label }
                        )
                        .append(" 等\n")
                    sb.append("· 同时把大号写入「不收能量 | 配置列表」→ **不会偷大号的能量**\n")
                }
            }
        }
        sb.append("\n名单：")
        sb.append(
            if (mainList.isEmpty() && subList.isEmpty()) "均未指定（保持账号现状）"
            else buildString {
                if (mainList.isNotEmpty()) append("大号 ${mainList.size} 个")
                if (mainList.isNotEmpty() && subList.isNotEmpty()) append("；")
                if (subList.isNotEmpty()) append("小号 ${subList.size} 个")
            }
        )
        sb.append("\n\n执行后会重载目标应用的配置。")
        return sb.toString()
    }
}

/** [ListDialog] 需要的「id + 名称」条目 */
private class SimpleEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}
