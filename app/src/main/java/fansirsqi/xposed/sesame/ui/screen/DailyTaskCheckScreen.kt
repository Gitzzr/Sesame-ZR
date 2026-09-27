package fansirsqi.xposed.sesame.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.ActionEntry
import fansirsqi.xposed.sesame.task.DailyTaskLog
import fansirsqi.xposed.sesame.task.DailyTaskLogPolicy
import fansirsqi.xposed.sesame.task.DailyTaskLogRecorder
import fansirsqi.xposed.sesame.task.EnergyRainEntry
import fansirsqi.xposed.sesame.task.ExpectedTaskStatus
import fansirsqi.xposed.sesame.task.GiftEntry
import fansirsqi.xposed.sesame.task.GiftTally
import fansirsqi.xposed.sesame.task.RewardEntry
import fansirsqi.xposed.sesame.task.antFarm.AntFarm
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.antForest.EcoLife
import fansirsqi.xposed.sesame.task.antForest.PlatePhoto
import fansirsqi.xposed.sesame.task.antOcean.AntOcean
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.maps.UserMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 今日任务完成核对。
 *
 * 只看今天、只看当前账号：数据来自 `daily_tasks.json`，开关来自各任务模型。
 * 失败记录和「开了开关但今天一条记录都没有」的项排在最前，方便一眼判断缺了什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyTaskCheckScreen(onBack: () -> Unit) {
    val snapshot = remember { loadSnapshot() }
    // 明细可能上百条（实测单日「任务与道具」113 条），默认折叠，需要时再展开
    var rainExpanded by remember { mutableStateOf(false) }
    var rewardExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日完成") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${snapshot.day}　${snapshot.accountName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SummaryCard(snapshot)

            if (!snapshot.switchesLoaded) {
                // 本页所在进程没加载任务模型，开关快照为空。
                // 不能把「读不到开关」显示成「都没开」——那会让人误以为配置丢了。
                SectionCard(title = "开关状态未读取", tone = SectionTone.NORMAL) {
                    EmptyHint(
                        "本次没读到任务开关，所以「需要处理」与「未开启」暂时为空，" +
                            "下面只展示今天已经记录到的结果。打开一次账号设置页后再进来即可读到。"
                    )
                }
            }

            if (snapshot.attention.isNotEmpty()) {
                SectionCard(title = "需要处理", tone = SectionTone.ALERT) {
                    snapshot.attention.forEach { StatusRow(it) }
                }
            }

            SectionCard(title = "能量雨", tone = SectionTone.NORMAL) {
                if (snapshot.energyRain.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    // 默认只给汇总：完成次数与总收获克数；逐次克数点「明细」再看
                    ExpandRow(
                        summary = buildString {
                            append("完成 ${snapshot.rainSuccess} 次　共 ${snapshot.rainGrams}g")
                            if (snapshot.rainFail > 0) append("　失败 ${snapshot.rainFail} 次")
                        },
                        summaryColor = if (snapshot.rainFail > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        expanded = rainExpanded,
                        detailLabel = "明细 ${snapshot.energyRain.size} 条",
                        onToggle = { rainExpanded = !rainExpanded }
                    )
                    if (rainExpanded) snapshot.energyRain.forEach { EnergyRainRow(it) }
                }
            }

            SectionCard(title = "能量赠送", tone = SectionTone.NORMAL) {
                if (snapshot.giftTallies.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    snapshot.giftTallies.forEach { GiftTallyRow(it) }
                    if (snapshot.giftFailures.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        snapshot.giftFailures.forEach { GiftFailureRow(it) }
                    }
                }
            }

            SectionCard(title = "任务奖励", tone = SectionTone.NORMAL) {
                val failed = snapshot.rewards.filter { !it.success }
                val succeeded = snapshot.rewards.filter { it.success }
                if (snapshot.rewards.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    // 失败始终展示，成功的明细折叠起来
                    ModuleGroups(failed, { it.module }) { RewardRow(it) }
                    if (succeeded.isNotEmpty()) {
                        ExpandRow(
                            summary = "成功 ${succeeded.size} 条",
                            summaryColor = MaterialTheme.colorScheme.onSurface,
                            expanded = rewardExpanded,
                            detailLabel = "成功明细",
                            onToggle = { rewardExpanded = !rewardExpanded }
                        )
                        if (rewardExpanded) ModuleGroups(succeeded, { it.module }) { RewardRow(it) }
                    }
                }
            }

            SectionCard(title = "任务与道具", tone = SectionTone.NORMAL) {
                val failed = snapshot.actions.filter { !it.success }
                val succeeded = snapshot.actions.filter { it.success }
                if (snapshot.actions.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    ModuleGroups(failed, { it.module }) { ActionRow(it) }
                    if (succeeded.isNotEmpty()) {
                        ExpandRow(
                            summary = "成功 ${succeeded.size} 条",
                            summaryColor = MaterialTheme.colorScheme.onSurface,
                            expanded = actionExpanded,
                            detailLabel = "成功明细",
                            onToggle = { actionExpanded = !actionExpanded }
                        )
                        if (actionExpanded) ModuleGroups(succeeded, { it.module }) { ActionRow(it) }
                    }
                }
            }

            SectionCard(title = "绿色行动", tone = SectionTone.NORMAL) {
                snapshot.ecoTasks.forEach { EcoTaskRow(it) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                PlatePhotoArea(snapshot.platePhotos)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 一次渲染所需的全部数据，读取放在组合之外，避免滚动时反复读盘。 */
private data class CheckSnapshot(
    val day: String,
    val accountName: String,
    val attention: List<ExpectedTaskStatus>,
    val energyRain: List<EnergyRainEntry>,
    val giftTallies: List<GiftTally>,
    val giftFailures: List<GiftEntry>,
    val rewards: List<RewardEntry>,
    val actions: List<ActionEntry>,
    val rainSuccess: Int,
    val rainFail: Int,
    /** 今日能量雨累计收获克数，用于汇总行 */
    val rainGrams: Int,
    val giftSuccess: Int,
    val giftFail: Int,
    val rewardSuccess: Int,
    val rewardFail: Int,
    val actionSuccess: Int,
    val actionFail: Int,
    /** 绿色行动两项任务各自的完成情况 */
    val ecoTasks: List<EcoTaskView>,
    /** 已抓取的光盘行动照片，按抓取时间倒序 */
    val platePhotos: List<PlatePhoto>,
    /** 是否成功读到任务开关；为空说明模型没加载，页面需要如实说明而不是当成「都没开」 */
    val switchesLoaded: Boolean
)

/**
 * 绿色行动里一项任务的完成情况。
 *
 * 「绿色打卡」与「光盘行动」是两个独立选项，这里让它们各自成行，
 * 不再合并成一个看不出缺哪个的「绿色行动」。
 */
private data class EcoTaskView(
    val name: String,
    /**
     * 该选项当前是否开启。
     *
     * null 表示**未知**：本页所在进程没加载模型（`Model.initAllModel()` 只在支付宝进程与
     * 账号设置页里执行），此时开关快照是空的，不能凭空说「未开启」—— 那是在撒谎。
     */
    val enabled: Boolean?,
    val successCount: Int,
    val failCount: Int,
    val lastReason: String?,
    /** 最近一次动作时间，0 表示今天没有记录 */
    val lastAt: Long
)

private fun loadSnapshot(): CheckSnapshot {
    val log = runCatching { DailyTaskLogRecorder.readToday(UserMap.currentUid) }.getOrNull()
        ?: DailyTaskLog(day = DailyTaskLogRecorder.dayKey(System.currentTimeMillis()))
    val enabled = runCatching { readEnabledSwitches() }.getOrDefault(emptyMap())

    val attention = DailyTaskLogPolicy.expectedStatus(enabled) { name ->
        DailyTaskLogPolicy.countFor(name, log)
    }.sortedWith(compareBy({ !it.missing && it.failCount == 0 }, { !it.missing }))

    return CheckSnapshot(
        day = log.day.ifEmpty { DailyTaskLogRecorder.dayKey(System.currentTimeMillis()) },
        accountName = UserMap.getCurrentMaskName()?.takeIf { it.isNotEmpty() } ?: "未载入账号",
        attention = attention.filter { it.missing || it.failCount > 0 },
        energyRain = log.energyRain.sortedByDescending { it.at },
        giftTallies = DailyTaskLogPolicy.giftTallies(log),
        giftFailures = log.gifts.filter { !it.success }.sortedByDescending { it.at },
        rewards = log.rewards.sortedWith(compareBy({ it.success }, { -it.at })),
        actions = log.actions.sortedWith(compareBy({ it.success }, { -it.at })),
        rainSuccess = log.energyRain.count { it.success },
        rainFail = log.energyRain.count { !it.success },
        rainGrams = log.energyRain.filter { it.success }.sumOf { it.grams },
        giftSuccess = log.gifts.count { it.success },
        giftFail = log.gifts.count { !it.success },
        rewardSuccess = log.rewards.count { it.success },
        rewardFail = log.rewards.count { !it.success },
        actionSuccess = log.actions.count { it.success },
        actionFail = log.actions.count { !it.success },
        ecoTasks = listOf(
            ecoTaskView(EcoLife.TITLE_CHECK_IN, enabled, log.actions),
            ecoTaskView(EcoLife.TITLE_PLATE, enabled, log.actions)
        ),
        platePhotos = runCatching { readPlatePhotos() }.getOrDefault(emptyList()),
        switchesLoaded = enabled.isNotEmpty()
    )
}

/** 汇总绿色行动某一项的今日成败与最近一次动作时间；开关取不到时 [EcoTaskView.enabled] 为 null。 */
private fun ecoTaskView(
    name: String,
    enabled: Map<String, Boolean>,
    actions: List<ActionEntry>
): EcoTaskView {
    val items = actions.filter { it.title == name }
    return EcoTaskView(
        name = name,
        enabled = enabled[name],
        successCount = items.count { it.success },
        failCount = items.count { !it.success },
        lastReason = items.lastOrNull { !it.success }?.reason,
        lastAt = items.maxOfOrNull { it.at } ?: 0L
    )
}

/**
 * 读取已缓存的光盘行动照片。
 *
 * `plate` 是模块自己写入的缓存，键名与 [PlatePhoto] 的字段一一对应；
 * 解析不出来的条目直接丢弃，不让一条坏数据把整页拖垮。
 */
private fun readPlatePhotos(): List<PlatePhoto> {
    val raw = DataStore.getOrCreate(
        "plate",
        object : TypeReference<MutableList<MutableMap<String?, String?>>>() {}
    )
    return raw.mapNotNull { PlatePhoto.fromRaw(it) }.sortedByDescending { it.at }
}

/** 汇总各任务模型里与今日核对相关的开关；模型未初始化时跳过。 */
private fun readEnabledSwitches(): Map<String, Boolean> {
    val switches = linkedMapOf<String, Boolean>()
    Model.getModel(AntForest::class.java)?.dailyCheckSwitches()?.let { switches.putAll(it) }
    Model.getModel(AntFarm::class.java)?.dailyCheckSwitches()?.let { switches.putAll(it) }
    Model.getModel(AntOcean::class.java)?.dailyCheckSwitches()?.let { switches.putAll(it) }
    return switches
}

/** 按模块分组展示；调用前已把失败记录排在前面。 */

@Composable
private fun SummaryCard(snapshot: CheckSnapshot) {
    SectionCard(title = "今日汇总", tone = SectionTone.NORMAL) {
        SummaryLine("能量雨", snapshot.rainSuccess, snapshot.rainFail)
        SummaryLine("能量赠送", snapshot.giftSuccess, snapshot.giftFail)
        SummaryLine("任务奖励", snapshot.rewardSuccess, snapshot.rewardFail)
        SummaryLine("任务与道具", snapshot.actionSuccess, snapshot.actionFail)
        if (snapshot.attention.isEmpty() &&
            snapshot.rainSuccess + snapshot.giftSuccess + snapshot.rewardSuccess + snapshot.actionSuccess == 0
        ) {
            EmptyHint("今天还没有任务记录")
        }
    }
}

@Composable
private fun SummaryLine(label: String, success: Int, fail: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (fail > 0) "成功 $success　失败 $fail" else "成功 $success",
            style = MaterialTheme.typography.bodyMedium,
            color = if (fail > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(status: ExpectedTaskStatus) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = status.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = if (status.missing) "今日无记录" else "失败 ${status.failCount} 次",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!status.missing && !status.lastFailReason.isNullOrBlank()) {
            Text(
                text = status.lastFailReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EnergyRainRow(entry: EnergyRainEntry) {
    RecordRow(
        title = if (entry.success) "收获 ${entry.grams}g" else "结算失败",
        trailing = formatTime(entry.at),
        failed = !entry.success,
        detail = entry.reason
    )
}

@Composable
private fun GiftTallyRow(tally: GiftTally) {
    val who = tally.targetName.ifEmpty { tally.targetUserId.ifEmpty { "未知接收方" } }
    RecordRow(
        title = "${giftKindLabel(tally.kind)} → $who",
        trailing = buildString {
            append("成功 ${tally.successCount}")
            if (tally.failCount > 0) append("　失败 ${tally.failCount}")
        },
        failed = tally.successCount == 0,
        detail = null
    )
}

@Composable
private fun GiftFailureRow(entry: GiftEntry) {
    val who = entry.targetName.ifEmpty { entry.targetUserId.ifEmpty { "未知接收方" } }
    RecordRow(
        title = "${giftKindLabel(entry.kind)} → $who　未送达",
        trailing = formatTime(entry.at),
        failed = true,
        detail = entry.reason
    )
}

@Composable
private fun <T> ModuleGroups(
    entries: List<T>,
    moduleOf: (T) -> String,
    row: @Composable (T) -> Unit
) {
    MODULE_ORDER.forEach { module ->
        val group = entries.filter { moduleOf(it) == module }
        if (group.isNotEmpty()) {
            Text(
                text = moduleLabel(module),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            group.forEach { row(it) }
        }
    }
}

@Composable
private fun ActionRow(entry: ActionEntry) {
    val amount = entry.detail.takeIf { it.isNotBlank() }?.let { "　$it" } ?: ""
    RecordRow(
        title = entry.title.ifBlank { "未命名动作" } + amount,
        trailing = formatTime(entry.at),
        failed = !entry.success,
        detail = entry.reason
    )
}

@Composable
private fun RewardRow(entry: RewardEntry) {
    val amount = entry.detail.takeIf { it.isNotBlank() }?.let { "　$it" } ?: ""
    RecordRow(
        title = entry.title.ifBlank { "未命名奖励" } + amount,
        trailing = formatTime(entry.at),
        failed = !entry.success,
        detail = entry.reason
    )
}

@Composable
private fun RecordRow(title: String, trailing: String, failed: Boolean, detail: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 折叠区的汇总行：左侧一句话汇总，右侧按钮展开或收起明细。 */
@Composable
private fun ExpandRow(
    summary: String,
    summaryColor: Color,
    expanded: Boolean,
    detailLabel: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = summaryColor,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggle) {
            Text(if (expanded) "收起" else detailLabel)
        }
    }
}

/**
 * 绿色行动里的一项任务。
 *
 * 三种状态分开说：开关没开、开了但今天没记录、有记录但失败了 ——
 * 合并成一句「今日无记录」会让用户分不清是不是自己没开。
 */
@Composable
private fun EcoTaskRow(task: EcoTaskView) {
    val label = when {
        task.enabled == false -> "未开启"
        task.successCount == 0 && task.failCount == 0 -> "今日无记录"
        task.failCount > 0 -> "成功 ${task.successCount}　失败 ${task.failCount}"
        else -> "成功 ${task.successCount} 次"
    }
    val labelColor = when {
        task.enabled == false -> MaterialTheme.colorScheme.onSurfaceVariant
        task.failCount > 0 || task.successCount == 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
        }
        val detail = listOfNotNull(
            formatTime(task.lastAt).takeIf { it.isNotEmpty() }?.let { "最近 $it" },
            task.lastReason?.takeIf { it.isNotBlank() }
        ).joinToString("　")
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 光盘行动照片区。
 *
 * 用户原话：「手动提交一次后可以完成任务了，但无法准确看到是否已经抓取到图片」。
 * 这里给出已缓存的组数、最近抓取时间，并允许逐组打开餐前/餐后照片；
 * 一组都没有时直接说明该怎么办，而不是只显示一行空白。
 */
@Composable
private fun PlatePhotoArea(photos: List<PlatePhoto>) {
    val context = LocalContext.current
    if (photos.isEmpty()) {
        EmptyHint("光盘照片：还没抓到。先在支付宝完成一次光盘打卡，模块会自动缓存餐前/餐后照片")
        return
    }
    val latest = photos.first().at
    Text(
        text = if (latest > 0) {
            "光盘照片：已缓存 ${photos.size} 组，最近抓取 ${formatTime(latest)}"
        } else {
            "光盘照片：已缓存 ${photos.size} 组"
        },
        style = MaterialTheme.typography.bodyMedium
    )
    photos.forEach { photo ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(photo.at).ifEmpty { "旧记录" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (photo.beforeUrl.isNotEmpty()) {
                    TextButton(onClick = { openUrl(context, photo.beforeUrl) }) { Text("餐前") }
                }
                if (photo.afterUrl.isNotEmpty()) {
                    TextButton(onClick = { openUrl(context, photo.afterUrl) }) { Text("餐后") }
                }
                if (!photo.hasPreview) {
                    // 旧记录只存了上传用的图片 ID，没有地址可打开
                    Text(
                        text = "仅图片 ID，无法预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 交给系统打开图片地址；没有可用应用时给一句提示，不让点击悄无声息。 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没有可以打开图片的应用", Toast.LENGTH_SHORT).show()
    }
}

private enum class SectionTone { NORMAL, ALERT }@Composable
private fun SectionCard(title: String, tone: SectionTone, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tone == SectionTone.ALERT) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private val MODULE_ORDER = listOf(
    DailyTaskLogPolicy.MODULE_FOREST,
    DailyTaskLogPolicy.MODULE_FARM,
    DailyTaskLogPolicy.MODULE_OCEAN
)

private fun moduleLabel(module: String): String = when (module) {
    DailyTaskLogPolicy.MODULE_FOREST -> "森林"
    DailyTaskLogPolicy.MODULE_FARM -> "庄园"
    DailyTaskLogPolicy.MODULE_OCEAN -> "海洋"
    else -> module
}

private fun giftKindLabel(kind: String): String = when (kind) {
    DailyTaskLogPolicy.GIFT_WATER -> "浇水"
    DailyTaskLogPolicy.GIFT_RAIN_CHANCE -> "能量雨机会"
    DailyTaskLogPolicy.GIFT_PROP -> "道具"
    else -> kind.ifEmpty { "赠送" }
}

private val timeFormat: SimpleDateFormat =
    SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("GMT+8") }

private fun formatTime(at: Long): String =
    if (at <= 0) "" else runCatching { timeFormat.format(Date(at)) }.getOrDefault("")
