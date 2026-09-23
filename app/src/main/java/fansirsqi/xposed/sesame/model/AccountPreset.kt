package fansirsqi.xposed.sesame.model

import android.content.Context
import android.content.Intent
import com.fasterxml.jackson.databind.JsonNode
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.util.FansirsqiUtil
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import java.io.File

/**
 * 账号档位切换（调试用）。
 *
 * 把 [AccountPresetPolicy] 里的两套预设写进**指定账号**的配置，并持久化、通知宿主重载。
 *
 * 关键点：
 * - 只覆盖 [AccountPresetPolicy.FIELDS] 里登记且当前版本确实存在的字段，其余设置一律不动；
 * - 小号档采用**白名单制**：把「大号名单」写进服务类名单并打开其门控开关
 *   （小号只服务大号），把「大号名单」写进排除类名单（不偷大号），
 *   索取/干扰类一律清空 —— 规则见 [AccountFriendListPolicy]；
 * - 大号档做反向清理：把「小号名单」从排除类名单里**移除**，因为大号要收小号的能量；
 * - 切换目标账号不是当前运行账号时，先快照内存配置，应用完再恢复，避免影响正在跑的账号。
 */
object AccountPreset {
    private const val TAG = "AccountPreset"

    /** 档位记录文件名（与 customset.json 同级，按账号隔离） */
    private const val PRESET_FILE = "account_preset.json"

    /** 好友列表文件名，与 `Files.getFriendIdMapFile` 保持一致 */
    private const val FRIEND_FILE = "friend.json"

    /** 账号配置文件，与 `Files.setConfigV2File` 保持一致 */
    private const val CONFIG_FILE = "config_v2.json"

    /** 宿主重载配置的广播，与 UI 侧保持一致 */
    private const val RESTART_ACTION = "com.eg.android.AlipayGphone.sesame.restart"

    /** 一次档位切换的结果，供 UI 展示 */
    data class ApplyResult(
        val tier: PresetTier,
        val userId: String,
        val appliedCount: Int,
        val skipped: List<String>,
        /** 本次套用了哪些功能的好友名单（用于确认/回执） */
        val filledLists: List<String>,
        /** 本次从哪些排除名单里移除了小号 */
        val purgedLists: List<String>,
        val success: Boolean,
        val message: String,
    )

    /** 已落盘的档位记录 */
    data class PresetRecord(
        val tier: PresetTier,
        val appliedAt: Long,
        val appliedCount: Int,
        val protectedAccounts: List<String>,
        /** 大号名单：每个好友各勾选了哪些功能 */
        val mainSelection: Map<String, Set<String>> = emptyMap(),
        /** 小号名单：每个好友各勾选了哪些功能 */
        val subSelection: Map<String, Set<String>> = emptyMap(),
    )

    // ------------------------------------------------------------------ 账号工具

    /** 本机已载入的账号（除自己以外）——用于互为保护名单 */
    fun otherAccountIds(selfUid: String): List<String> =
        FansirsqiUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
            .filter { it.isNotBlank() && it != selfUid }

    /** 账号显示名（失败时退回 userId） */
    fun displayName(uid: String): String = try {
        UserMap.get(uid)?.showName ?: uid
    } catch (t: Throwable) {
        uid
    }

    private fun labelOf(uid: String): String = "${displayName(uid)}($uid)"

    /**
     * 好友列表条目。用于「选择要保护的账号」——大号与小号常常**不在同一台设备上**，
     * 此时 [otherAccountIds] 拿不到对方 userId，只能靠好友列表来指定保护对象。
     */
    data class Friend(val userId: String, val name: String)

    /**
     * 直接读配置文件里「基础」模块的关系名单（大号名单 / 小号名单），**不改内存配置**。
     *
     * 用于下次打开切换流程时默认勾选上次的标记 —— 走 [Config.load] 会污染当前运行账号的内存配置，
     * 只为了读一个列表不值得。
     */
    @JvmStatic
    fun readStoredRelationList(userId: String, fieldCode: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        return try {
            val file = File(Files.getUserConfigDir(userId), CONFIG_FILE)
            if (!file.exists()) return emptySet()
            val json = Files.readFromFile(file)
            if (json.isBlank()) return emptySet()
            val node = JsonUtil.toNode(json) ?: return emptySet()
            val arr = node.path("modelFieldsMap")
                .path(AccountFriendListPolicy.MAIN_LIST_MODEL)
                .path(fieldCode)
                .path("value")
            if (!arr.isArray) return emptySet()
            arr.mapNotNull { it.asText(null) }.filter { it.isNotBlank() }.toSet()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取关系名单失败", t)
            emptySet()
        }
    }

    /**
     * 读取某账号的本地好友列表（`friend.json`）。
     *
     * 直接读文件而不是走 `AlipayUser.getList()`：后者的数据来自内存里的 `UserMap`，
     * 而本功能的入口在模块自己的进程，那里并不保证好友映射已加载。
     *
     * @return 按名称排序的好友列表；读不到时返回空列表（调用方需容忍）
     */
    @JvmStatic
    fun readFriendList(userId: String): List<Friend> {
        if (userId.isBlank()) return emptyList()
        return try {
            val file = File(Files.getUserConfigDir(userId), FRIEND_FILE)
            if (!file.exists()) return emptyList()
            val json = Files.readFromFile(file)
            if (json.isBlank()) return emptyList()
            val node = JsonUtil.toNode(json) ?: return emptyList()
            val result = mutableListOf<Friend>()
            val it = node.fields()
            while (it.hasNext()) {
                val (id, value) = it.next()
                if (id == userId) continue // 跳过自己
                val name = value.path("showName").asText("")
                    .ifBlank { value.path("nickName").asText("") }
                    .ifBlank { id }
                result += Friend(id, name)
            }
            result.sortBy { it.name }
            result
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取好友列表失败", t)
            emptyList()
        }
    }

    // ------------------------------------------------------------------ 切换

    /**
     * 把 [tier] 档位应用到 [targetUid]。
     *
     * @param context        用于发送宿主重载广播；为空时只落盘不广播
     * @param mainList       大号名单（好友 userId）。小号档据它聚合要写入的功能名单
     * @param subList        小号名单（好友 userId）。大号档据它清理排除名单
     * @param mainSelection  大号名单下**每个好友各勾选了哪些功能**（friendId → 功能 id）
     * @param subSelection   小号名单下每个好友各勾选了哪些功能
     */
    @JvmStatic
    @Synchronized
    fun apply(
        tier: PresetTier,
        targetUid: String,
        context: Context? = null,
        mainList: Set<String> = emptySet(),
        subList: Set<String> = emptySet(),
        mainSelection: Map<String, Set<String>> = emptyMap(),
        subSelection: Map<String, Set<String>> = emptyMap(),
    ): ApplyResult {
        if (targetUid.isBlank()) {
            return ApplyResult(tier, targetUid, 0, emptyList(), emptyList(), emptyList(), false, "无效的账号 ID")
        }

        val activeUid = UserMap.currentUid
        val needRestore = !activeUid.isNullOrEmpty() && activeUid != targetUid
        val snapshot = if (needRestore) Config.toSaveStr() else null

        val skipped = mutableListOf<String>()
        val filledLists = mutableListOf<String>()
        val purgedLists = mutableListOf<String>()
        var appliedCount = 0

        try {
            // 0. 模型注册表必须先就绪，否则下面的 load/save 都是有害操作（见函数注释）
            ensureModelRegistry()
            if (Model.getModelConfigMap().isEmpty()) {
                val msg = "模型注册表不可用，已中止且未写入任何配置"
                Log.error(TAG, msg)
                return ApplyResult(tier, targetUid, 0, skipped, filledLists, purgedLists, false, msg)
            }

            // 1. 载入目标账号配置（会把字段值推进 ModelConfig）
            Config.load(targetUid)
            val modelConfigMap = Model.getModelConfigMap()

            fun fieldOf(modelCode: String, fieldCode: String): ModelField<*>? =
                modelConfigMap[modelCode]?.getModelField(fieldCode)

            /** 写入单个字段；返回是否成功（字段不存在则记入 skipped） */
            fun write(modelCode: String, fieldCode: String, value: Any?): Boolean {
                val f = fieldOf(modelCode, fieldCode)
                if (f == null) {
                    skipped += "$modelCode.$fieldCode"
                    return false
                }
                f.setObjectValue(value)
                return true
            }

            // 2. 静态档位表（基线：开关类 / 固定取值）
            for (row in AccountPresetPolicy.overridesFor(tier)) {
                val raw = row.valueFor(tier)
                if (raw === WriteMainAccountList) {
                    // 由「大号名单」驱动：未指定大号就保持账号现状
                    if (mainList.isNotEmpty()) {
                        if (write(row.modelCode, row.fieldCode, mainList)) appliedCount++
                    }
                    continue
                }
                if (write(row.modelCode, row.fieldCode, raw)) appliedCount++
            }

            // 3. 关系名单本身落盘，供下次复用
            write(AccountFriendListPolicy.MAIN_LIST_MODEL, AccountFriendListPolicy.MAIN_LIST_FIELD, mainList)
            write(AccountFriendListPolicy.SUB_LIST_MODEL, AccountFriendListPolicy.SUB_LIST_FIELD, subList)

            // 4. 按好友的勾选聚合写入功能名单（覆盖第 2 步的基线；未勾选的保持基线）
            val isMainTier = tier == PresetTier.MAIN
            val activeList = if (isMainTier) subList else mainList
            val activeSelection = if (isMainTier) subSelection else mainSelection
            for ((featureId, friends) in aggregateSelection(activeList, activeSelection)) {
                val ref = AccountFriendListPolicy.byId(featureId)
                if (ref == null) {
                    skipped += featureId
                    continue
                }
                if (!AccountFriendListPolicy.isSelectable(ref, !isMainTier)) {
                    skipped += "$featureId(该方向不可选)"
                    continue
                }
                // 名单填了不等于生效：连带打开门控开关
                for (sw in ref.gateSwitches) {
                    if (write(sw.modelCode, sw.fieldCode, sw.value)) appliedCount++
                }
                val value: Any = if (ref.isCounted) {
                    friends.associateWith { ref.countDefault ?: 1 }
                } else {
                    friends
                }
                if (write(ref.modelCode, ref.fieldCode, value)) {
                    appliedCount++
                    filledLists += ref.id
                }
            }

            // 5. 大号档：把小号从排除类名单里移除 —— 大号要收小号的能量
            if (tier == PresetTier.MAIN && subList.isNotEmpty()) {
                for (ref in AccountFriendListPolicy.exclusionLists()) {
                    val f = fieldOf(ref.modelCode, ref.fieldCode) ?: run {
                        skipped += ref.id
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val current = (f.value as? Set<String>) ?: continue
                    val remain = current - subList
                    if (remain.size == current.size) continue
                    f.setObjectValue(remain)
                    appliedCount++
                    purgedLists += ref.id
                }
            }

            // 6. 落盘 + 通知宿主重载
            val saved = Config.save(targetUid, true)
            if (saved) {
                writeRecord(
                    targetUid, tier, appliedCount, mainList.toList(), mainSelection, subSelection,
                )
                broadcastRestart(context, targetUid)
            }
            val message = buildMessage(tier, targetUid, appliedCount, skipped, filledLists, purgedLists, saved)
            Log.record(TAG, message)
            return ApplyResult(
                tier, targetUid, appliedCount, skipped, filledLists, purgedLists, saved, message,
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "应用账号档位失败", t)
            return ApplyResult(
                tier, targetUid, appliedCount, skipped, filledLists, purgedLists, false,
                "应用失败：${t.message ?: t.javaClass.simpleName}",
            )
        } finally {
            // 7. 恢复当前运行账号的内存配置
            if (snapshot != null) {
                restoreSnapshot(snapshot)
            }
        }
    }

    private fun buildMessage(
        tier: PresetTier,
        targetUid: String,
        appliedCount: Int,
        skipped: List<String>,
        filledLists: List<String>,
        purgedLists: List<String>,
        saved: Boolean,
    ): String {
        val head = if (saved) {
            "已把【${tier.label}】档位应用到 ${labelOf(targetUid)}"
        } else {
            "【${tier.label}】档位写入失败"
        }
        val parts = mutableListOf(head, "覆盖 $appliedCount 项设置")
        if (filledLists.isNotEmpty()) parts += "批量启用 ${filledLists.size} 项功能"
        if (purgedLists.isNotEmpty()) parts += "把小号移出排除名单 ${purgedLists.size} 处"
        if (skipped.isNotEmpty()) parts += "跳过 ${skipped.size} 项（当前版本无此设置）"
        return parts.joinToString("；")
    }

    private fun restoreSnapshot(snapshot: String) {
        try {
            // 与 Config.load 走同一条反序列化链路：readerForUpdating 会经 setModelFieldsMap
            // 把值重新推回 ModelConfig 的字段对象。
            JsonUtil.copyMapper().readerForUpdating(Config.INSTANCE).readValue<Any>(snapshot)
            Log.record(TAG, "已恢复当前运行账号的内存配置")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "恢复当前账号内存配置失败，建议重启目标应用", t)
        }
    }

    private fun broadcastRestart(context: Context?, userId: String) {
        if (context == null) return
        try {
            val intent = Intent(RESTART_ACTION).apply { putExtra("userId", userId) }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "发送重载广播失败", t)
        }
    }

    // ------------------------------------------------------------------ 注册表就绪

    /**
     * 确保 [Model] 的注册表已就绪。
     *
     * 本功能的入口在**主界面**（`MainActivity` → `SettingsContent`）的设置页里，而
     * `Model.initAllModel()` 只被 `ApplicationHook`（支付宝进程）与三个设置 Activity
     * （`SettingActivity` / `WebSettingsActivity` / `ManualTaskActivity`）调用过，
     * 模块自己的进程从主界面进来时注册表是空的。若在这时直接 `Config.load` / `Config.save`：
     *
     * - 轻则字段一条都写不进去（`getModelField` 全部返回 null）；
     * - **重则 `Config.setModelFieldsMap` 拿到空注册表，`modelFieldsMap` 变成空 Map，
     *   `Config.save` 会把空配置覆盖到账号的 `config_v2.json` 上，等于清空用户配置。**
     *
     * 因此写入前必须先按设置页的惯例初始化一次；初始化失败则中止，绝不落盘。
     */
    private fun ensureModelRegistry() {
        if (Model.getModelConfigMap().isNotEmpty()) return
        Log.record(TAG, "模型注册表为空，按设置页惯例先执行 Model.initAllModel()")
        try {
            Model.initAllModel()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "初始化模型注册表失败", t)
        }
        Log.record(TAG, "模型注册表就绪：${Model.getModelConfigMap().size} 个模型")
    }

    // ------------------------------------------------------------------ 勾选聚合

    /**
     * 把「每个好友各勾选了哪些功能」反转成「每个功能被哪些好友勾选」。
     *
     * 名单字段的最终取值就是这个反转结果：同一项功能，勾选它的好友合并成一个集合写进去。
     * 只遍历 [list] 里的好友 —— 名单外的好友即使有历史勾选也不参与。
     */
    private fun aggregateSelection(
        list: Set<String>,
        selection: Map<String, Set<String>>,
    ): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        for (friend in list) {
            for (featureId in selection[friend].orEmpty()) {
                if (featureId.isBlank()) continue
                out.getOrPut(featureId) { linkedSetOf() }.add(friend)
            }
        }
        return out
    }

    /** 解析 `featureSelection` 下的一个方向：`{好友uid: [功能id...]}` */
    private fun parseSelection(node: JsonNode): Map<String, Set<String>> {
        if (!node.isObject) return emptyMap()
        val out = linkedMapOf<String, Set<String>>()
        val it = node.fields()
        while (it.hasNext()) {
            val (uid, arr) = it.next()
            if (!arr.isArray) continue
            val ids = arr.mapNotNull { it.asText(null) }.filter { it.isNotBlank() }.toSet()
            if (ids.isNotEmpty()) out[uid] = ids
        }
        return out
    }

    // ------------------------------------------------------------------ 记录读写

    private fun recordFile(userId: String): File? {
        if (userId.isBlank()) return null
        return try {
            File(Files.getUserConfigDir(userId), PRESET_FILE)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "获取档位记录文件失败", t)
            null
        }
    }

    private fun writeRecord(
        userId: String,
        tier: PresetTier,
        appliedCount: Int,
        protectedAccounts: List<String>,
        mainSelection: Map<String, Set<String>>,
        subSelection: Map<String, Set<String>>,
    ) {
        try {
            val file = Files.getTargetFileofUser(userId, PRESET_FILE) ?: return
            val data = linkedMapOf<String, Any?>(
                "tier" to tier.code,
                "tierLabel" to tier.label,
                "appliedAt" to System.currentTimeMillis(),
                "appliedCount" to appliedCount,
                "protectedAccounts" to protectedAccounts,
                // 逐好友的功能勾选：Set<String> 型设置项表达不了这层嵌套，只能随档位记录落盘
                "featureSelection" to linkedMapOf(
                    "main" to mainSelection.mapValues { it.value.toList() },
                    "sub" to subSelection.mapValues { it.value.toList() },
                ),
            )
            val json = JsonUtil.formatJson(data)
            if (json != null) Files.write2File(json, file)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "写入档位记录失败", t)
        }
    }

    /** 读取某账号上次应用的档位；没有记录时返回 null */
    @JvmStatic
    fun readRecord(userId: String): PresetRecord? {
        val file = recordFile(userId) ?: return null
        if (!file.exists()) return null
        val json = Files.readFromFile(file)
        if (json.isBlank()) return null
        return try {
            val node = JsonUtil.toNode(json) ?: return null
            val tier = PresetTier.fromCode(node.path("tier").asText(null)) ?: return null
            PresetRecord(
                tier = tier,
                appliedAt = node.path("appliedAt").asLong(0L),
                appliedCount = node.path("appliedCount").asInt(0),
                protectedAccounts = node.path("protectedAccounts")
                    .mapNotNull { it.asText(null) },
                mainSelection = parseSelection(node.path("featureSelection").path("main")),
                subSelection = parseSelection(node.path("featureSelection").path("sub")),
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "解析档位记录失败", t)
            null
        }
    }

    /** 该账号当前档位的展示文案 */
    @JvmStatic
    fun tierSummary(userId: String): String {
        val record = readRecord(userId) ?: return "未设置档位"
        return "当前：${record.tier.label}档（覆盖 ${record.appliedCount} 项）"
    }
}
