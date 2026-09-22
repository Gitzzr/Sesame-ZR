package fansirsqi.xposed.sesame.model

import android.content.Context
import android.content.Intent
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
 * - 「不收能量名单 / 小号列表 / 复活能量好友列表」会被写入**本机其他已载入账号**，
 *   让大小号互为保护名单，形成双向隔离（这是小号隔离档之外的第二道保险）；
 * - 切换目标账号不是当前运行账号时，先快照内存配置，应用完再恢复，避免影响正在跑的账号。
 */
object AccountPreset {
    private const val TAG = "AccountPreset"

    /** 档位记录文件名（与 customset.json 同级，按账号隔离） */
    private const val PRESET_FILE = "account_preset.json"

    /** 宿主重载配置的广播，与 UI 侧保持一致 */
    private const val RESTART_ACTION = "com.eg.android.AlipayGphone.sesame.restart"

    /** 需要写入「其他账号」保护名单的字段 */
    private val PROTECT_LIST_FIELDS = listOf(
        "AntForest" to "dontCollectList",
        "AntForest" to "alternativeAccountList",
        "AntForest" to "helpFriendCollectList",
    )

    /** 一次档位切换的结果，供 UI 展示 */
    data class ApplyResult(
        val tier: PresetTier,
        val userId: String,
        val appliedCount: Int,
        val skipped: List<String>,
        val protectedAccounts: List<String>,
        val success: Boolean,
        val message: String,
    )

    /** 已落盘的档位记录 */
    data class PresetRecord(
        val tier: PresetTier,
        val appliedAt: Long,
        val appliedCount: Int,
        val protectedAccounts: List<String>,
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

    // ------------------------------------------------------------------ 切换

    /**
     * 把 [tier] 档位应用到 [targetUid]。
     *
     * @param context              用于发送宿主重载广播；为空时只落盘不广播
     * @param protectOtherAccounts 是否把本机其他账号写入「不收能量 / 小号列表 / 复活能量」保护名单
     */
    @JvmStatic
    @Synchronized
    fun apply(
        tier: PresetTier,
        targetUid: String,
        context: Context? = null,
        protectOtherAccounts: Boolean = true,
    ): ApplyResult {
        if (targetUid.isBlank()) {
            return ApplyResult(tier, targetUid, 0, emptyList(), emptyList(), false, "无效的账号 ID")
        }

        val activeUid = UserMap.currentUid
        val needRestore = !activeUid.isNullOrEmpty() && activeUid != targetUid
        val snapshot = if (needRestore) Config.toSaveStr() else null

        val skipped = mutableListOf<String>()
        var appliedCount = 0
        val protectSet: Set<String> =
            if (protectOtherAccounts) otherAccountIds(targetUid).toSet() else emptySet()
        // 本机只检测到目标账号自己时，没有可保护的账号 —— 此时**不动**用户手工配置的名单，
        // 而不是把它清空。
        val writeProtectList = protectOtherAccounts && protectSet.isNotEmpty()
        val protectedLabels = protectSet.map { labelOf(it) }

        try {
            // 1. 载入目标账号配置（会把字段值推进 ModelConfig）
            Config.load(targetUid)
            val modelConfigMap = Model.getModelConfigMap()

            // 2. 逐项写入档位值
            for (field in AccountPresetPolicy.overridesFor(tier)) {
                val fieldName = "${field.modelCode}.${field.fieldCode}"
                val modelConfig = modelConfigMap[field.modelCode]
                if (modelConfig == null) {
                    skipped += fieldName
                    continue
                }
                val modelField = modelConfig.getModelField(field.fieldCode)
                if (modelField == null) {
                    skipped += fieldName
                    continue
                }
                val raw = field.valueFor(tier)
                if (raw === AutoProtectAccounts) {
                    if (!writeProtectList) {
                        // 没有可保护的账号，或用户选择不动名单：保持账号现状
                        continue
                    }
                    modelField.setObjectValue(protectSet)
                } else {
                    modelField.setObjectValue(raw)
                }
                appliedCount++
            }

            // 3. 名单类字段兜底：确认页勾选了「保护其他账号」时，三份名单统一写入同一集合
            if (writeProtectList) {
                for ((modelCode, fieldCode) in PROTECT_LIST_FIELDS) {
                    val modelField = modelConfigMap[modelCode]?.getModelField(fieldCode) ?: continue
                    modelField.setObjectValue(protectSet)
                }
            }

            // 4. 落盘 + 通知宿主重载
            val saved = Config.save(targetUid, true)
            if (saved) {
                writeRecord(targetUid, tier, appliedCount, protectSet.toList())
                broadcastRestart(context, targetUid)
            }
            val message = buildMessage(tier, targetUid, appliedCount, skipped.size, protectedLabels, saved)
            Log.record(TAG, message)
            return ApplyResult(tier, targetUid, appliedCount, skipped, protectedLabels, saved, message)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "应用账号档位失败", t)
            return ApplyResult(
                tier, targetUid, appliedCount, skipped, protectedLabels, false,
                "应用失败：${t.message ?: t.javaClass.simpleName}",
            )
        } finally {
            // 5. 恢复当前运行账号的内存配置
            if (snapshot != null) {
                restoreSnapshot(snapshot)
            }
        }
    }

    private fun buildMessage(
        tier: PresetTier,
        targetUid: String,
        appliedCount: Int,
        skippedCount: Int,
        protectedLabels: List<String>,
        saved: Boolean,
    ): String {
        val head = if (saved) {
            "已把【${tier.label}】档位应用到 ${labelOf(targetUid)}"
        } else {
            "【${tier.label}】档位写入失败"
        }
        val parts = mutableListOf(head, "覆盖 $appliedCount 项设置")
        if (skippedCount > 0) parts += "跳过 $skippedCount 项（当前版本无此设置）"
        if (protectedLabels.isNotEmpty()) {
            parts += "保护名单 ${protectedLabels.size} 个账号：${protectedLabels.joinToString("、")}"
        }
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
    ) {
        try {
            val file = Files.getTargetFileofUser(userId, PRESET_FILE) ?: return
            val data = linkedMapOf<String, Any?>(
                "tier" to tier.code,
                "tierLabel" to tier.label,
                "appliedAt" to System.currentTimeMillis(),
                "appliedCount" to appliedCount,
                "protectedAccounts" to protectedAccounts,
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
