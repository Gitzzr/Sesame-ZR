package fansirsqi.xposed.sesame.ui

import android.content.Context
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.AccountPreset
import fansirsqi.xposed.sesame.model.AccountPresetPolicy
import fansirsqi.xposed.sesame.model.PresetTier
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil

/**
 * 「账号档位（大号 / 小号）」一键切换的交互入口。
 *
 * 三步：选账号 → 选档位 → 确认并应用。
 * 确认页会明确告知将要关闭多少项跨账号功能、以及会把哪些账号写入保护名单。
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
            "切换到【${PresetTier.MAIN.label}】档 · ${PresetTier.MAIN.summary}",
            "切换到【${PresetTier.ALT.label}】档 · ${PresetTier.ALT.summary}",
        )
        AlertDialog.Builder(context)
            .setTitle("账号：${account.showName}")
            .setItems(items) { _, which ->
                val tier = if (which == 0) PresetTier.MAIN else PresetTier.ALT
                showConfirm(context, account, tier, onApplied)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showConfirm(context: Context, account: Account, tier: PresetTier, onApplied: () -> Unit) {
        val otherAccounts = try {
            AccountPreset.otherAccountIds(account.uid)
        } catch (t: Throwable) {
            emptyList()
        }
        val protectCheckBox = CheckBox(context).apply {
            text = "把本机其他账号写入「不收能量 / 小号列表 / 复活能量」保护名单"
            isChecked = otherAccounts.isNotEmpty()
            isEnabled = otherAccounts.isNotEmpty()
        }

        AlertDialog.Builder(context)
            .setTitle("确认切换到【${tier.label}】档？")
            .setMessage(buildConfirmText(account, tier, otherAccounts.size))
            .setView(protectCheckBox)
            .setPositiveButton("确认切换") { _, _ ->
                val result = try {
                    AccountPreset.apply(
                        tier = tier,
                        targetUid = account.uid,
                        context = context,
                        protectOtherAccounts = protectCheckBox.isChecked,
                    )
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "应用档位失败", t)
                    null
                }
                if (result == null) {
                    ToastUtil.showToast(context, "切换失败，请查看运行日志")
                } else {
                    ToastUtil.showToast(context, result.message)
                    if (result.success) onApplied()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildConfirmText(account: Account, tier: PresetTier, otherAccountCount: Int): String {
        val crossTotal = AccountPresetPolicy.CROSS_ACCOUNT_FIELDS.size
        val sb = StringBuilder()
        sb.append("账号：").append(account.showName).append("（").append(account.uid).append("）\n")
        sb.append("当前：").append(AccountPreset.tierSummary(account.uid)).append("\n\n")
        when (tier) {
            PresetTier.MAIN -> sb.append("将开启各模块的收益与进度类功能（覆盖 ")
                .append(AccountPresetPolicy.overrideCount(tier))
                .append(" 项设置），并保持贴罚单、丢肥料等纯干扰项关闭。")

            PresetTier.ALT -> sb.append("将关闭全部 ")
                .append(crossTotal)
                .append(" 项以好友（含大号）为对象的功能：\n")
                .append("· 不收能量、不一键收取、不 Pk 榜收取、不领好友礼盒\n")
                .append("· 不合种浇水、不帮抽卡、不抢/不训练好友、不收好友金币\n")
                .append("· 不贴罚单、不丢肥料、不请走小摊、不雇佣好友小鸡\n")
                .append("· 清空全部好友名单，关闭道具消耗\n")
                .append("小号只做纯本账号的签到、任务与领取，不会动大号任何资源。")
        }
        sb.append("\n\n")
        if (otherAccountCount > 0) {
            sb.append("另有 ").append(otherAccountCount).append(" 个本机账号可加入双向保护名单。")
        } else {
            sb.append("本机只检测到 1 个账号，无法自动生成保护名单。\n")
                .append("若大号是好友，请手动把它加入「不收能量 | 配置列表」。")
        }
        sb.append("\n\n执行后会重载目标应用的配置。")
        return sb.toString()
    }
}
