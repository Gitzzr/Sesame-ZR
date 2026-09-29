package fansirsqi.xposed.sesame.ui.screen.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fansirsqi.xposed.sesame.task.TaskHealthPolicy
import fansirsqi.xposed.sesame.task.TaskHealthSnapshot
import fansirsqi.xposed.sesame.task.TaskHealthState

/**
 * 任务状态卡：把「任务此刻到底在不在正常跑」摆到主页最上面。
 *
 * 数据来自宿主进程写入的 `task_health.json`，状态由 [TaskHealthPolicy] 按时间戳现算 ——
 * 所以连「宿主进程被杀、文件停在 RUNNING」这种最典型的卡死也能显示成「已 N 分钟无进展」。
 * 卡片只做展示：不提供任何一键干预（判定错了也不至于把正常任务打断）。
 */
@Composable
fun TaskHealthCard(
    tasks: List<TaskHealthSnapshot>,
    now: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务状态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                val abnormal = tasks.count {
                    it.state == TaskHealthState.STALLED ||
                        it.state == TaskHealthState.FAILED ||
                        it.state == TaskHealthState.BLOCKED
                }
                Text(
                    text = if (abnormal > 0) "⚠ $abnormal 项需关注" else "一切正常",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (abnormal > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (tasks.isEmpty()) {
                Text(
                    text = "还没有任务状态记录（任务跑过一轮后出现）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            tasks.forEachIndexed { index, task ->
                if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                TaskHealthRow(task = task, now = now)
            }
        }
    }
}

@Composable
private fun TaskHealthRow(task: TaskHealthSnapshot, now: Long) {
    val state = task.state
    val tint = stateColor(state)
    val desc = TaskHealthPolicy.describe(task, state, now)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = TaskHealthPolicy.labelOf(state),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (desc.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 状态配色：异常用 error，等待/进行用中性色，正常用主色 */
@Composable
private fun stateColor(state: TaskHealthState): Color = when (state) {
    TaskHealthState.STALLED -> MaterialTheme.colorScheme.error
    TaskHealthState.FAILED -> MaterialTheme.colorScheme.error
    TaskHealthState.BLOCKED -> MaterialTheme.colorScheme.tertiary
    TaskHealthState.OK -> MaterialTheme.colorScheme.primary
    TaskHealthState.RUNNING -> MaterialTheme.colorScheme.primary
    TaskHealthState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskHealthState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
