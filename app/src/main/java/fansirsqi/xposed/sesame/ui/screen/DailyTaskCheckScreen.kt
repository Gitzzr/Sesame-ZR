package fansirsqi.xposed.sesame.ui.screen

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import fansirsqi.xposed.sesame.task.antOcean.AntOcean
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

            if (snapshot.attention.isNotEmpty()) {
                SectionCard(title = "需要处理", tone = SectionTone.ALERT) {
                    snapshot.attention.forEach { StatusRow(it) }
                }
            }

            SectionCard(title = "能量雨", tone = SectionTone.NORMAL) {
                if (snapshot.energyRain.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    snapshot.energyRain.forEach { EnergyRainRow(it) }
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
                if (snapshot.rewards.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    ModuleGroups(snapshot.rewards, { it.module }) { RewardRow(it) }
                }
            }

            SectionCard(title = "任务与道具", tone = SectionTone.NORMAL) {
                if (snapshot.actions.isEmpty()) {
                    EmptyHint("今日无记录")
                } else {
                    ModuleGroups(snapshot.actions, { it.module }) { ActionRow(it) }
                }
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
    val giftSuccess: Int,
    val giftFail: Int,
    val rewardSuccess: Int,
    val rewardFail: Int,
    val actionSuccess: Int,
    val actionFail: Int
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
        giftSuccess = log.gifts.count { it.success },
        giftFail = log.gifts.count { !it.success },
        rewardSuccess = log.rewards.count { it.success },
        rewardFail = log.rewards.count { !it.success },
        actionSuccess = log.actions.count { it.success },
        actionFail = log.actions.count { !it.success }
    )
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

private enum class SectionTone { NORMAL, ALERT }

@Composable
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
