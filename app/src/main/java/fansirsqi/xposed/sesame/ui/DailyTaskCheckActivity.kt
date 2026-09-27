package fansirsqi.xposed.sesame.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.ui.screen.DailyTaskCheckScreen
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.util.maps.UserMap

/** 今日任务完成核对页，入口在日志中心的「今日完成」。 */
class DailyTaskCheckActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本页只读落盘记录，但记录是按账号分目录存的，必须先确定当前账号。
        // currentUid 只在内存里，覆盖安装 APK 或进程被回收后就是 null，
        // 此时页面会显示「未载入账号」且整页空白（2026-09-27 两台手机覆盖安装后复现）。
        val uid = UserMap.restoreActiveUserIfAbsent()

        // 开关状态来自任务模型字段，而模型只在支付宝进程与账号设置页里初始化过。
        // 本进程要自己初始化一次，否则「需要处理」永远为空、绿色行动会误报「未开启」——
        // 用户得先绕去账号设置页再回来，体验很差。
        // 配置走只读加载（persist = false）：看一眼页面不该回写 config_v2.json。
        if (uid != null) {
            runCatching {
                Model.initAllModel()
                Config.load(uid, false)
            }
        }

        setContent {
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()
            AppTheme(dynamicColor = isDynamicColor) {
                DailyTaskCheckScreen(onBack = { finish() })
            }
        }
    }
}
