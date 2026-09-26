package fansirsqi.xposed.sesame.ui.screen.content

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
import androidx.compose.material.icons.rounded.Agriculture
import androidx.compose.material.icons.rounded.AlignVerticalTop
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fansirsqi.xposed.sesame.ui.MainActivity
import fansirsqi.xposed.sesame.ui.screen.components.MenuButton

/**
 * 日志中心入口：三组分节布局（业务 / 系统 / 汇总）
 *
 * 分类体系与 LogCategory 对应：
 * - 业务：森林(森林生态) / 海洋(海洋垂钓) / 庄园(庄园小鸡) / 果园(果树) / 新村(摆摊) / 生活(会员运动等)
 * - 系统：运行(调度统计) / 错误 / 抓包 / 调试
 * - 汇总：全部(record 镜像)
 */
@Composable
fun LogsContent(
    onEvent: (MainActivity.MainUiEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val modifier = Modifier.weight(1f)

        // ── 业务日志 ──
        SectionLabel("业务日志")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuButton(text = "森林日志", icon = Icons.Rounded.Forest, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenForestLog) }
            MenuButton(text = "海洋日志", icon = Icons.Rounded.Water, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenOceanLog) }
            MenuButton(text = "庄园日志", icon = Icons.Rounded.Agriculture, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenFarmLog) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuButton(text = "果园日志", icon = Icons.Rounded.Park, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenOrchardLog) }
            MenuButton(text = "新村日志", icon = Icons.Rounded.Storefront, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenStallLog) }
            MenuButton(text = "生活日志", icon = Icons.Rounded.FavoriteBorder, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenLifeLog) }
        }

        Spacer(Modifier.height(20.dp))

        // ── 系统日志 ──
        SectionLabel("系统日志")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuButton(text = "运行日志", icon = Icons.Rounded.Settings, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenRuntimeLog) }
            MenuButton(text = "错误日志", icon = Icons.Rounded.BugReport, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenErrorLog) }
            MenuButton(text = "抓包日志", icon = Icons.Rounded.History, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenCaptureLog) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(0.67f)) {
            MenuButton(text = "其他日志", icon = Icons.Rounded.AlignVerticalTop, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenOtherLog) }
            MenuButton(text = "调试日志", icon = Icons.Rounded.Speed, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenDebugLog) }
        }

        Spacer(Modifier.height(20.dp))

        // ── 汇总 ──
        SectionLabel("汇总")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuButton(text = "全部日志", icon = Icons.Rounded.Description, modifier = modifier) { onEvent(MainActivity.MainUiEvent.OpenAllLog) }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp)
    )
}
